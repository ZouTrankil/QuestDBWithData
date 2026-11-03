package com.zoutrankil.data.margin.application;

import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.stock.margin.MarginAll;
import com.zoutrankil.data.domain.stock.margin.MarginAllDataset;
import com.zoutrankil.data.domain.stock.margin.MarginAllKey;
import com.zoutrankil.data.margin.domain.MarginAllState;
import com.zoutrankil.data.margin.port.MarginAllTarget;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DailySyncEndDate;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.FrozenRunRequest;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Bounded plan/run/resume facade for D028; default empty-target bootstrap follows Python's 2026-01-01 fallback. */
@Service
public class MarginAllJobService {
    public static final LocalDate BOOTSTRAP_START = LocalDate.of(2026, 1, 1);

    public record Plan(
            SyncJobDefinition.FrozenRequest request,
            String targetId,
            String physicalTargetId,
            LocalDate checkpointAnchor,
            LocalDate checkpointBefore,
            MarginAllState.TargetRange physicalRange,
            boolean bootstrap,
            boolean cappedByBudget) {
        public Plan {
            Objects.requireNonNull(request);
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(physicalTargetId);
            Objects.requireNonNull(physicalRange);
            if (!targetId.equals(request.parameters().get("targetId"))
                    || !physicalTargetId.equals(request.parameters().get("physicalTargetId")))
                throw new IllegalArgumentException("Frozen D028 target identity mismatch");
        }
    }

    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final MarginAllTarget target;
    private final Path ledgerPath;
    private final String table;
    private final ExchangeCalendarReadPort calendars;

    @Autowired
    public MarginAllJobService(
            @Lazy SyncJobRegistry jobs,
            TusharePageService pages,
            MarginAllTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            ExchangeCalendarReadPort calendars) {
        this(jobs, pages, target, Path.of(ledgerPath), calendars);
    }

    public MarginAllJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            MarginAllTarget target,
            Path ledgerPath) {
        this(jobs, pages, target, ledgerPath, null);
    }

    public MarginAllJobService(
            SyncJobRegistry jobs,
            TusharePageService pages,
            MarginAllTarget target,
            Path ledgerPath,
            ExchangeCalendarReadPort calendars) {
        this.calendars = calendars;
        this.jobs = Objects.requireNonNull(jobs);
        this.pages = Objects.requireNonNull(pages);
        this.target = Objects.requireNonNull(target);
        this.ledgerPath = ledgerPath.toAbsolutePath().normalize();
        String table = target.tableName();
        MarginAllDataset.requireExecutionTable(table);
        this.table = table;
    }

    public String tableName() {
        return table;
    }

    public String targetId() {
        return target.logicalTargetId(table);
    }

    public String physicalTargetId() {
        var snapshot = snapshot();
        return target.physicalTargetId(table, snapshot.identity());
    }

    private MarginAllState.Snapshot snapshot() {
        try {
            return target.open(table).snapshot();
        } catch (Exception e) {
            throw new IllegalStateException("D028 full target snapshot failed", e);
        }
    }

    /** Incremental bootstrap is bounded to at most 366 days; initialized targets overlap the verified checkpoint by five days. */
    public Plan plan(
            Mode requestedMode,
            LocalDate requestedFrom,
            LocalDate requestedThrough,
            LocalDate logicalDate)
            throws Exception {
        Objects.requireNonNull(logicalDate, "Frozen D028 logical date required");
        ZonedDateTime now = ZonedDateTime.now(DailySyncEndDate.ZONE);
        LocalDate completedCeiling = DailySyncEndDate.resolve(null, now);
        LocalDate frozenLogical = logicalDate;
        LocalDate to = DailySyncEndDate.resolve(requestedThrough, now);
        if (to.isAfter(completedCeiling)) to = completedCeiling;
        if (to.isAfter(frozenLogical)) to = frozenLogical;
        Mode mode = requestedMode == null ? executionDefinition().defaultMode() : requestedMode;
        if (!MarginAllSyncJobOwner.DEFINITION.supportedModes().contains(mode))
            throw new IllegalArgumentException("Unsupported D028 mode");
        if (to == null) throw new IllegalArgumentException("D028 completed --to date is required");
        if ((mode == Mode.BACKFILL || mode == Mode.RECONCILE)
                && (requestedFrom == null || requestedThrough == null))
            throw new IllegalArgumentException(
                    "D028 BACKFILL/RECONCILE requires explicit --from and --to");
        if (formalTarget()) {
            if (mode != Mode.BACKFILL) throw new IllegalArgumentException("正式汇总只允许显式BACKFILL");
            return planFormal(requestedFrom, requestedThrough, logicalDate);
        }
        String logical = targetId();
        var port = target.newWriter(physicalTargetId());
        port.preflight();
        var full = snapshot();
        var range = port.readTargetRange();
        if (range.rows() != full.rows().size())
            throw new IllegalStateException(
                    "D028 physical count differs from bounded full snapshot");
        String physical = target.physicalTargetId(table, full.identity());
        new MarginAllPublication(target.newPublicationTables(), ledgerPath)
                .requireNoPendingPublication();
        var saved = MarginAllCoverage.checkpoint(ledgerPath, logical);
        if (saved != null) MarginAllCoverage.validateTarget(ledgerPath, logical, full);
        LocalDate from = requestedFrom, anchor = null, before = null;
        boolean bootstrap = false, capped = false;
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("targetId", logical);
        parameters.put("physicalTargetId", physical);
        parameters.put("targetRowsBefore", full.rows().size());
        parameters.put("targetFingerprint", full.fingerprint());
        if (range.min() != null) parameters.put("targetMinBefore", range.min());
        if (range.max() != null) parameters.put("targetMaxBefore", range.max());
        if (mode == Mode.INCREMENTAL) {
            if (range.max() != null && range.max().isAfter(frozenLogical))
                throw new IllegalStateException(
                        "D028 isolated target has data after completed logical date");
            if (saved == null) {
                if (!full.rows().isEmpty())
                    throw new IllegalStateException(
                            "D028 nonempty target lacks same-target receipt-backed incremental coverage");
                from = requestedFrom == null ? BOOTSTRAP_START : requestedFrom;
                anchor = from;
                bootstrap = true;
                if (from.isAfter(to))
                    throw new IllegalArgumentException(
                            "D028 bootstrap starts after completed --to");
            } else {
                if (requestedFrom != null)
                    throw new IllegalArgumentException(
                            "D028 --from is used only for empty-target bootstrap; use BACKFILL for a later window");
                var checkpoint = saved;
                before = checkpoint.through();
                anchor = checkpoint.anchor();
                if (to.isBefore(before))
                    throw new IllegalArgumentException(
                            "D028 end precedes receipt-backed checkpoint");
                from = before.minusDays(MarginAllSyncJobOwner.REVISION_DAYS);
                if (from.isBefore(anchor)) from = anchor;
                if (range.max() != null && range.max().isAfter(to))
                    throw new IllegalStateException(
                            "D028 incremental cannot end before existing physical dates");
                parameters.put("checkpointBefore", before);
            }
            LocalDate max = from.plusDays(MarginAllSyncJobOwner.MAX_WINDOW_DAYS - 1L);
            if (to.isAfter(max)) {
                to = max;
                capped = true;
            }
            parameters.put("checkpointAnchor", anchor);
        } else if (mode == Mode.BACKFILL || mode == Mode.RECONCILE) {
            if (from == null || from.isAfter(to))
                throw new IllegalArgumentException(
                        "D028 explicit ordered historical window required");
            if (saved == null)
                throw new IllegalStateException(
                        "D028 historical repair requires an established incremental receipt chain");
            MarginAllCoverage.validateTarget(ledgerPath, logical, full);
            var cp = saved;
            if (from.isBefore(cp.anchor()) || to.isAfter(cp.through()))
                throw new IllegalArgumentException(
                        "D028 repair window must remain within receipt-backed incremental coverage");
            if (ChronoUnit.DAYS.between(from, to) + 1 > MarginAllSyncJobOwner.MAX_WINDOW_DAYS)
                throw new IllegalArgumentException("D028 repair window exceeds 366-day bound");
        } else
            throw new IllegalArgumentException(
                    "D028 supports bounded incremental/backfill/reconcile only");
        if (from == null
                || from.isAfter(to)
                || to.isAfter(frozenLogical)
                || ChronoUnit.DAYS.between(from, to) + 1 > MarginAllSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException(
                    "D028 resolved window outside finite/logical bounds");
        var request =
                jobs.prepare(
                        MarginAllSyncJobOwner.DEFINITION.jobId(),
                        MarginAllSyncJobOwner.DEFINITION.version(),
                        mode,
                        parameters,
                        from,
                        to,
                        frozenLogical);
        MarginAllSyncAdapter.validateRequest(request);
        adapter(
                        table,
                        physical,
                        ledgerPath.getParent().resolve("sync-evidence").resolve("preflight"),
                        logical)
                .preflight(request);
        if (!logical.equals(targetId()) || !physical.equals(physicalTargetId()))
            throw new IllegalStateException("D028 isolated target changed while planning");
        return new Plan(request, logical, physical, anchor, before, range, bootstrap, capped);
    }

    private boolean formalTarget() {
        return "margin_all".equals(table);
    }

    private SyncJobDefinition executionDefinition() {
        return formalTarget()
                ? MarginAllSyncJobOwner.FORMAL_DEFINITION
                : MarginAllSyncJobOwner.DEFINITION;
    }

    private void requireExecutionRequest(SyncJobDefinition.FrozenRequest request) {
        if (!executionDefinition().equals(request.definition()))
            throw new IllegalArgumentException("汇总物理目标与作业版本不匹配");
        MarginAllSyncAdapter.validateRequest(request);
    }

    /** 正式v2冻结完整日历和旧多重集合，不用旧最大日期建立增量覆盖。 */
    private Plan planFormal(LocalDate from, LocalDate to, LocalDate logicalDate) throws Exception {
        var completed = DailySyncEndDate.resolve(null, ZonedDateTime.now(DailySyncEndDate.ZONE));
        if (from == null
                || to == null
                || from.isAfter(to)
                || to.isAfter(logicalDate)
                || to.isAfter(completed)
                || logicalDate.isAfter(LocalDate.now(DailySyncEndDate.ZONE)))
            throw new IllegalArgumentException("正式汇总需要明确已完成日期窗");
        var calendar = new MarginAllCalendar(calendars).read(from, to);
        requireNoPendingForPlan();
        String logical = targetId();
        var full = snapshot();
        String physical = target.physicalTargetId(table, full.identity());
        var port = target.newWriter(physical);
        port.preflight();
        var range = port.readTargetRange();
        if (range.rows() != full.rows().size()) throw new IllegalStateException("汇总全表行数与稳定快照不符");
        var p = new LinkedHashMap<String, Object>();
        p.put("targetId", logical);
        p.put("physicalTargetId", physical);
        p.put("targetRowsBefore", full.rows().size());
        p.put("targetFingerprint", full.fingerprint());
        if (range.min() != null) {
            p.put("targetMinBefore", range.min());
            p.put("targetMaxBefore", range.max());
        }
        p.put("calendarPolicy", MarginAllCalendar.POLICY);
        p.put("calendarDays", String.join(",", calendar.encoded()));
        p.put("calendarFingerprint", calendar.fingerprint());
        var d = executionDefinition();
        var request = jobs.prepare(d.jobId(), d.version(), Mode.BACKFILL, p, from, to, logicalDate);
        requireExecutionRequest(request);
        port.preflight();
        requireTarget(logical, physical);
        return new Plan(request, logical, physical, null, null, range, false, false);
    }

    private void requireNoPendingForPlan() throws Exception {
        if (Files.exists(ledgerPath)) {
            new MarginAllPublication(target.newPublicationTables(), ledgerPath)
                    .requireNoPendingPublication();
            return;
        }
        // 没有账本时也不能接管遗留stage；只读计划不创建新的正式运行账本。
        var root = ledgerPath.getParent().resolve("sync-evidence");
        if (!Files.exists(root)) return;
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root))
            throw new IllegalStateException("汇总证据目录无效");
        int count = 0;
        try (var runs = Files.newDirectoryStream(root)) {
            for (var dir : runs) {
                if (++count > 50000) throw new IllegalStateException("汇总遗留stage扫描超预算");
                if (Files.isSymbolicLink(dir)) continue;
                if (Files.isDirectory(dir) && MarginAllStaging.hasStageIntent(dir))
                    throw new IllegalStateException("汇总缺账本且存在未核对stage：" + dir.getFileName());
            }
        }
    }

    /** 单日只读核对日历、来源交易所与旧键，不推进检查点。 */
    public Map<String, Object> checkSource(LocalDate date, LocalDate logical) throws Exception {
        if (date == null
                || logical == null
                || date.isAfter(logical)
                || logical.isAfter(LocalDate.now(DailySyncEndDate.ZONE))
                || date.isAfter(
                        DailySyncEndDate.resolve(null, ZonedDateTime.now(DailySyncEndDate.ZONE))))
            throw new IllegalArgumentException("需要明确已完成来源日和业务日");
        var calendar = new MarginAllCalendar(calendars).read(date, date);
        var before = snapshot();
        String id = target.physicalTargetId(table, before.identity());
        var page =
                new MarginAllSource(
                                pages,
                                ledgerPath
                                        .getParent()
                                        .resolve(
                                                "sync-evidence/margin-all-source-check-"
                                                        + UUID.randomUUID()))
                        .fetch(date, () -> Thread.currentThread().isInterrupted());
        var exchanges = page.rows().stream().map(MarginAll::exchangeId).collect(Collectors.toSet());
        if (!exchanges.equals(calendar.openExchanges().get(date)))
            throw new IllegalStateException("来源交易所与完整日历不符");
        var old = before.rows().stream().filter(r -> r.tradeDate().equals(date)).toList();
        var keys = page.rows().stream().map(MarginAll::key).collect(Collectors.toSet());
        long missing = old.stream().filter(r -> !keys.contains(r.key())).count();
        var after = snapshot();
        if (!before.identity().equals(after.identity())
                || !before.fingerprint().equals(after.fingerprint()))
            throw new IllegalStateException("只读来源检查期间物理代、WAL或完整值改变");
        var result = new LinkedHashMap<String, Object>();
        result.put("productionWritten", false);
        result.put("sourceCoverageVerified", true);
        result.put("targetDataVerified", false);
        result.put("date", date);
        result.put("targetId", id);
        result.put("sourceRows", page.rows().size());
        result.put("sourceExchanges", exchanges);
        result.put("oldScopeRows", old.size());
        result.put("oldKeysMissingInSource", missing);
        result.put(
                "source",
                Map.of(
                        "sourceFingerprint",
                        page.sourceFingerprint(),
                        "responseEvidence",
                        page.responseEvidence()));
        return result;
    }

    public SyncJobRunner.Result run(Plan plan) throws Exception {
        return runRequest(Objects.requireNonNull(plan).request(), null);
    }

    public SyncJobRunner.Result runRequest(SyncJobDefinition.FrozenRequest request, String parentId)
            throws Exception {
        MarginAllSyncAdapter.validateRequest(request);
        String logical = frozenTarget(request),
                physical = (String) request.parameters().get("physicalTargetId");
        requireTarget(logical, physical);
        verifyBaseline(request);
        return execute(
                "margin-all-" + UUID.randomUUID(), parentId, null, logical, physical, request);
    }

    public SyncJobRunner.Result runAsGroupChild(
            String runId,
            String parentRunId,
            String priorRunId,
            String expectedTarget,
            SyncJobDefinition.FrozenRequest request)
            throws Exception {
        String logical = frozenTarget(request),
                physical = (String) request.parameters().get("physicalTargetId");
        if (!Objects.equals(expectedTarget, logical))
            throw new IllegalStateException(
                    "D028 group child logical target differs from its frozen request");
        requireTarget(logical, physical);
        verifyBaseline(request);
        return execute(runId, parentRunId, priorRunId, logical, physical, request);
    }

    public SyncJobRunner.Result resume(String priorRunId) throws Exception {
        var restored = FrozenRunRequest.restore(ledgerPath, priorRunId, executionDefinition());
        var request = restored.request();
        String logical = restored.targetId();
        if (new MarginAllPublication(target.newPublicationTables(), ledgerPath)
                .findForRun(priorRunId)
                .isPresent())
            throw new IllegalStateException(
                    "D028 publication recovery must finish before source resume");
        if (MarginAllStaging.hasStageIntent(
                ledgerPath.getParent().resolve("sync-evidence").resolve(priorRunId)))
            throw new IllegalStateException(
                    "D028 stage-only artifact must be reconciled/discarded before resume");
        String physical = (String) request.parameters().get("physicalTargetId");
        requireTarget(logical, physical);
        verifyBaseline(request);
        return execute(
                "margin-all-" + UUID.randomUUID(),
                priorRunId,
                priorRunId,
                logical,
                physical,
                request);
    }

    /** 与物理恢复分开，完整认证原运行后才解除原锁，不发送任何数据。 */
    public Map<String, Object> reconcileRun(String runId, boolean writerStopped) throws Exception {
        return MarginAllRunRecovery.reconcile(
                target.newPublicationTables(), ledgerPath, table, runId, writerStopped);
    }

    public MarginAllPublication.Result finishInterrupted(String runId, boolean writerStopped)
            throws Exception {
        var publication = new MarginAllPublication(target.newPublicationTables(), ledgerPath);
        if (publication.findForRun(runId).isPresent())
            return publication.finish(runId, writerStopped);
        var run = SyncRunLedger.openReadOnly(ledgerPath).getRun(runId);
        String logical = run.targetId();
        return discardAndReconcile(runId, logical, writerStopped, publication);
    }

    private MarginAllPublication.Result discardAndReconcile(
            String runId, String logical, boolean stopped, MarginAllPublication publication)
            throws Exception {
        if (!stopped) throw new IllegalStateException("D028 writer-stopped proof required");
        String current = targetId();
        if (!logical.equals(current))
            throw new IllegalStateException("D028 recovery logical target differs");
        publication.discardStageOnly(table, runId, true);
        // 未发布分支没有journal；返回原布局，原运行已明确取消，不宣称发布成功。
        return new MarginAllPublication.Result(
                null,
                MarginAllPublication.Layout.ORIGINAL,
                target.newPublicationTables().open(table).snapshot(),
                null);
    }

    public SyncRunLedger.Entry status(String runId) throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).get(runId);
    }

    public List<SyncRunLedger.Entry> entries(String runId, String afterId, int limit)
            throws Exception {
        return SyncRunLedger.openReadOnly(ledgerPath).entries(runId, afterId, limit);
    }

    public boolean cancel(String runId) throws Exception {
        return new SyncRunLedger(ledgerPath).requestCancellation(runId);
    }

    private void verifyBaseline(SyncJobDefinition.FrozenRequest request) throws Exception {
        requireExecutionRequest(request);
        String logical = frozenTarget(request);
        if (!logical.equals(targetId()))
            throw new IllegalStateException("D028 stable logical target changed");
        var actual = snapshot();
        var p = request.parameters();
        if (actual.rows().size() != ((Integer) p.get("targetRowsBefore"))
                || !actual.fingerprint().equals(p.get("targetFingerprint"))
                || !target.physicalTargetId(table, actual.identity())
                        .equals(p.get("physicalTargetId")))
            throw new IllegalStateException(
                    "D028 frozen physical baseline changed; replan or recover first");
        if (formalTarget()) return;
        var cp = MarginAllCoverage.checkpoint(ledgerPath, logical);
        if (request.mode() == Mode.INCREMENTAL) {
            LocalDate expected = (LocalDate) p.get("checkpointBefore"),
                    anchor = (LocalDate) p.get("checkpointAnchor");
            if ((cp != null) != (expected != null)
                    || cp != null
                            && (!cp.through().equals(expected) || !cp.anchor().equals(anchor)))
                throw new IllegalStateException("D028 incremental checkpoint changed after plan");
        } else if (cp == null
                || request.from().isBefore(cp.anchor())
                || request.to().isAfter(cp.through()))
            throw new IllegalStateException(
                    "D028 BACKFILL/RECONCILE must stay within receipt-backed incremental coverage");
        if (cp != null) MarginAllCoverage.validateTarget(ledgerPath, logical, actual);
        else if (!actual.rows().isEmpty())
            throw new IllegalStateException(
                    "D028 nonempty bootstrap target has no verified receipt chain");
    }

    private SyncJobRunner.Result execute(
            String runId,
            String parentId,
            String priorRunId,
            String logical,
            String physical,
            SyncJobDefinition.FrozenRequest request)
            throws Exception {
        requireExecutionRequest(request);
        requireTarget(logical, physical);
        var ledger = new SyncRunLedger(ledgerPath);
        var adapter =
                adapter(
                        table,
                        physical,
                        ledgerPath.getParent().resolve("sync-evidence").resolve(runId),
                        logical);
        var runner =
                new SyncJobRunner<MarginAll, MarginAllKey>(
                        ledger, new DatasetIntervalLock(ledgerPath));
        // 丢弃stage后旧分片只保留历史，普通resume和组子任务都必须整窗重抓。
        if (priorRunId != null && MarginAllStageRecovery.requiresRefetch(ledgerPath, priorRunId))
            priorRunId = null;
        return priorRunId == null
                ? runner.run(
                        runId,
                        parentId,
                        logical,
                        request,
                        adapter,
                        () -> Thread.currentThread().isInterrupted())
                : runner.resume(
                        runId,
                        parentId,
                        priorRunId,
                        logical,
                        request,
                        adapter,
                        () -> Thread.currentThread().isInterrupted());
    }

    private MarginAllSyncAdapter adapter(
            String table, String physical, Path evidence, String logical) throws Exception {
        var port = target.newWriter(physical);
        return new MarginAllSyncAdapter(
                new MarginAllSource(pages, evidence.resolve("source")),
                port,
                new MarginAllStaging(target.newStaging()),
                new MarginAllPublication(target.newPublicationTables(), ledgerPath),
                evidence,
                target);
    }

    private void requireTarget(String logical, String physical) {
        if (!Objects.equals(logical, targetId()) || !Objects.equals(physical, physicalTargetId()))
            throw new IllegalStateException(
                    "D028 logical or physical isolated target generation changed");
    }

    private static String frozenTarget(SyncJobDefinition.FrozenRequest request) {
        Object raw = request.parameters().get("targetId");
        if (!(raw instanceof String value))
            throw new IllegalArgumentException("Frozen D028 logical target required");
        return value;
    }
}
