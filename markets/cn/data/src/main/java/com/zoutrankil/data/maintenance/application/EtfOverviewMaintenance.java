package com.zoutrankil.data.maintenance.application;

import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.checkpointName;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.definitionHash;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.requireDefinition;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.snapshot;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.versionIfActivated;
import static com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard.view;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.data.domain.temporal.TemporalValues.CalendarTimestamp;
import com.zoutrankil.data.domain.temporal.TemporalValues.EpochUnit;
import com.zoutrankil.data.maintenance.storage.EtfOverviewRefreshJournal;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints.Checkpoint;
import com.zoutrankil.data.repository.EtfMarketOverviewMaterializedViewReadGuard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 份额变动使用原生增量刷新；JOIN 右侧数据修订时，须全量重建关联结果。 */
@Service
@Lazy
public final class EtfOverviewMaintenance {
    private final JdbcTemplate jdbc;
    private final Path ledger;
    private final Duration timeout;
    private final EtfOverviewScope scope;

    public EtfOverviewMaintenance(
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.materialized-views.etf-overview.refresh-timeout:2m}") Duration timeout) {
        this(
                new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())),
                Path.of(ledger),
                timeout);
        this.jdbc.setQueryTimeout(120);
    }

    @Autowired
    public EtfOverviewMaintenance(
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.materialized-views.etf-overview.refresh-timeout:2m}") Duration timeout,
            EtfOverviewScope scope) {
        this(
                new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())),
                Path.of(ledger),
                timeout,
                scope);
        this.jdbc.setQueryTimeout(120);
    }

    EtfOverviewMaintenance(JdbcTemplate jdbc, Path ledger, Duration timeout) {
        this(jdbc, ledger, timeout, EtfOverviewScope.LEGACY);
    }

    public EtfOverviewMaintenance(
            JdbcTemplate jdbc, Path ledger, Duration timeout, EtfOverviewScope scope) {
        this.scope = Objects.requireNonNull(scope);
        this.jdbc = jdbc;
        this.ledger = ledger.toAbsolutePath().normalize();
        this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive refresh timeout required");
    }

    /** 数据库尚未使用该产品时，后台检查不会自行安装相关对象。 */
    public Map<String, Object> refreshIfInstalled() throws Exception {
        if (jdbc.queryForList(
                        "SELECT view_name FROM materialized_views() WHERE view_name='"
                                + scope.outputTable()
                                + "'")
                .isEmpty()) return Map.of("status", "ABSENT");
        return refresh();
    }

    /**
     * 先根据两侧来源和输出身份选择刷新方式，再逐窗核验整个历史，最后保存本地证书。
     * 校验期间任何来源或输出变化，均不得把检查点推进到未经验证的版本。
     */
    public Map<String, Object> refresh() throws Exception {
        Files.createDirectories(ledger.getParent());
        try (var channel =
                        FileChannel.open(
                                ledger.resolveSibling(ledger.getFileName() + ".etf-mv.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("ETF MV refresh is already running");
            var journal = new EtfOverviewRefreshJournal(ledger);
            EtfOverviewRefreshJournal.Operation operation = null;
            String key = checkpointName(jdbc, scope);
            var pending = journal.pending(key);
            if (pending.isPresent())
                throw new IllegalStateException("ETF 刷新未决，须按原意图显式核验：" + pending.get().id());
            if (!scope.legacy()) requireSourceLayouts();
            String leftBefore = scopedSnapshot(scope.shareTable()),
                    rightBefore = scopedSnapshot(scope.dailyTable());
            var priorView =
                    jdbc.queryForList(
                            "SELECT * FROM materialized_views() WHERE view_name='"
                                    + scope.outputTable()
                                    + "'");
            String before = "ABSENT";
            if (!priorView.isEmpty()) {
                var previousView = priorView.getFirst();
                requireDefinition(previousView, scope);
                before = scopedSnapshot(scope.outputTable());
                var certificates = new MaterializedViewCheckpoints(ledger);
                var receipt = certificates.find(key);
                if (receipt.isPresent()
                        && "immediate".equals(previousView.get("refresh_type"))
                        && current(receipt.get(), leftBefore, rightBefore, before, previousView)) {
                    if (!certificates.hasDateVerification(key, receipt.get())) {
                        long rows = verifyHistory();
                        if (!leftBefore.equals(scopedSnapshot(scope.shareTable()))
                                || !rightBefore.equals(scopedSnapshot(scope.dailyTable()))
                                || !before.equals(scopedSnapshot(scope.outputTable())))
                            throw new IllegalStateException("ETF 独立日期核验期间版本变化");
                        certificates.saveDateVerification(key, receipt.get(), rows);
                    }
                    versionIfActivated(jdbc, ledger, scope);
                    return Map.of(
                            "status", "CURRENT", "output", scope.outputTable(), "mode", "REUSED");
                }
            } else if (!jdbc.queryForList(
                            "SELECT table_name FROM tables() WHERE table_name='"
                                    + scope.outputTable()
                                    + "'")
                    .isEmpty()) throw new IllegalStateException("保留现有普通表，不作为 ETF MV 接管");
            if (!scope.legacy()) verifySourceBudget();
            if (!leftBefore.equals(scopedSnapshot(scope.shareTable()))
                    || !rightBefore.equals(scopedSnapshot(scope.dailyTable())))
                throw new IllegalStateException("ETF 来源在意图保存前变化，未提交刷新");
            operation = journal.begin(key, scope, leftBefore, rightBefore, before, scopedSql());
            install();
            var view = scopedView();
            requireDefinition(view, scope);
            String left = scopedSnapshot(scope.shareTable()),
                    right = scopedSnapshot(scope.dailyTable());
            if (operation != null
                    && (!operation.left().equals(left) || !operation.right().equals(right)))
                throw new IllegalStateException("ETF 来源在提交原意图后变化，保留未决记录");
            String checkpoint = checkpointName(jdbc, scope);
            var store = new MaterializedViewCheckpoints(ledger);
            var old = store.find(checkpoint);
            String output = scopedSnapshot(scope.outputTable());
            if (old.isPresent() && current(old.get(), left, right, output, view)) {
                // 旧证书没有证明修正后的日期窗口；用当前完整读回另留新证明，不改旧记录。
                if (!store.hasDateVerification(checkpoint, old.get())) {
                    long rows = verifyHistory();
                    if (!left.equals(scopedSnapshot(scope.shareTable()))
                            || !right.equals(scopedSnapshot(scope.dailyTable()))
                            || !output.equals(scopedSnapshot(scope.outputTable())))
                        throw new IllegalStateException("ETF 新日期核验期间版本变化，保留原记录");
                    store.saveDateVerification(checkpoint, old.get(), rows);
                }
                if (operation != null) journal.complete(operation.id(), output);
                versionIfActivated(jdbc, ledger, scope);
                return Map.of("status", "CURRENT", "output", scope.outputTable(), "mode", "REUSED");
            }
            // 右表修订不会由基表增量自动覆盖；证书缺失、定义变化或输出重建也须全量重算。
            boolean full =
                    old.isEmpty()
                            || !old.get().definitionHash().equals(definitionHash(scope))
                            || !old.get().rightVersion().equals(right)
                            || !generation(old.get().outputVersion()).equals(generation(output))
                            || !"valid".equals(view.get("view_status"))
                            || nonempty(view.get("invalidation_reason"));
            // 原生增量若已追平基表，直接核验现有结果，避免等待一个实际不会发生的空刷新。
            boolean nativeCurrent =
                    !full
                            && "valid".equals(view.get("view_status"))
                            && !nonempty(view.get("invalidation_reason"))
                            && Objects.equals(
                                    view.get("refresh_base_table_txn"), view.get("base_table_txn"))
                            && !Objects.toString(view.get("last_refresh_finish_timestamp"), "")
                                    .isBlank();
            String mode = full ? "FULL" : nativeCurrent ? "NATIVE_INCREMENTAL" : "INCREMENTAL";
            String previousFinish = Objects.toString(view.get("last_refresh_finish_timestamp"), "");
            Map<String, Object> refreshed = view;
            if (!nativeCurrent) {
                jdbc.execute("REFRESH MATERIALIZED VIEW " + scope.outputTable() + " " + mode);
                long deadline = System.nanoTime() + timeout.toNanos();
                while (true) {
                    refreshed = scopedView();
                    requireDefinition(refreshed, scope);
                    if (!Objects.toString(refreshed.get("last_refresh_finish_timestamp"), "")
                                    .isBlank()
                            && !previousFinish.equals(
                                    Objects.toString(
                                            refreshed.get("last_refresh_finish_timestamp"), ""))
                            && "valid".equals(refreshed.get("view_status"))
                            && !nonempty(refreshed.get("invalidation_reason"))
                            && Objects.equals(
                                    refreshed.get("refresh_base_table_txn"),
                                    refreshed.get("base_table_txn"))) break;
                    if (System.nanoTime() > deadline)
                        throw new IllegalStateException(
                                "ETF native refresh did not complete before timeout");
                    Thread.sleep(25);
                }
            }
            String verifiedOutput = scopedSnapshot(scope.outputTable());
            long rows = verifyHistory();
            if (!left.equals(scopedSnapshot(scope.shareTable()))
                    || !right.equals(scopedSnapshot(scope.dailyTable()))
                    || !verifiedOutput.equals(scopedSnapshot(scope.outputTable())))
                throw new IllegalStateException(
                        "ETF sources/output changed during refresh verification; no checkpoint was advanced");
            store.save(
                    checkpoint,
                    new Checkpoint(
                            definitionHash(scope),
                            left,
                            right,
                            verifiedOutput,
                            Objects.toString(refreshed.get("last_refresh_finish_timestamp"))));
            store.saveDateVerification(
                    checkpoint,
                    new Checkpoint(
                            definitionHash(scope),
                            left,
                            right,
                            verifiedOutput,
                            Objects.toString(refreshed.get("last_refresh_finish_timestamp"))),
                    rows);
            if (operation != null) journal.complete(operation.id(), verifiedOutput);
            versionIfActivated(jdbc, ledger, scope);
            return Map.of(
                    "status",
                    "VERIFIED",
                    "output",
                    scope.outputTable(),
                    "mode",
                    mode,
                    "verifiedRows",
                    rows,
                    "ledger",
                    ledger.toString());
        }
    }

    private void install() {
        var existing =
                jdbc.queryForList(
                        "SELECT * FROM materialized_views() WHERE view_name='"
                                + scope.outputTable()
                                + "'");
        if (existing.isEmpty()) {
            if (!jdbc.queryForList(
                            "SELECT table_name FROM tables() WHERE table_name='"
                                    + scope.outputTable()
                                    + "'")
                    .isEmpty())
                throw new IllegalStateException(
                        "ETF output is an ordinary table; preserve and migrate its data explicitly");
            jdbc.execute(
                    "CREATE MATERIALIZED VIEW "
                            + scope.outputTable()
                            + " WITH BASE "
                            + scope.shareTable()
                            + " REFRESH MANUAL DEFERRED AS ("
                            + scopedSql()
                            + ") PARTITION BY MONTH");
        } else requireDefinition(existing.getFirst(), scope);
        // QuestDB 维护基表增量；Java 检查器额外处理关联表的版本变化。
        if (!"immediate".equals(scopedView().get("refresh_type")))
            jdbc.execute(
                    "ALTER MATERIALIZED VIEW " + scope.outputTable() + " SET REFRESH IMMEDIATE");
    }

    private boolean current(
            Checkpoint old, String left, String right, String output, Map<String, Object> view) {
        return old.definitionHash().equals(definitionHash(scope))
                && old.leftVersion().equals(left)
                && old.rightVersion().equals(right)
                && old.outputVersion().equals(output)
                && old.refreshFinished()
                        .equals(Objects.toString(view.get("last_refresh_finish_timestamp"), ""))
                && "valid".equals(view.get("view_status"))
                && !nonempty(view.get("invalidation_reason"))
                && Objects.equals(view.get("refresh_base_table_txn"), view.get("base_table_txn"));
    }

    private long verifyHistory() {
        var bounds =
                jdbc.queryForMap(
                        "SELECT cast(min("
                                + scope.shareDate()
                                + ") AS long) AS lo,cast(max("
                                + scope.shareDate()
                                + ") AS long) AS hi FROM "
                                + scope.shareTable());
        if (bounds.get("lo") == null) {
            if (!jdbc.queryForList("SELECT trade_date FROM " + scope.outputTable() + " LIMIT 1")
                    .isEmpty())
                throw new IllegalStateException("ETF output has rows without sources");
            return 0;
        }
        LocalDate start = date(bounds.get("lo")), end = date(bounds.get("hi")).plusDays(1);
        if (ChronoUnit.DAYS.between(start, end) > 36600)
            throw new IllegalStateException("ETF history exceeds verification budget");
        var outputBounds =
                jdbc.queryForMap(
                        "SELECT cast(min(trade_date) AS long) AS lo,cast(max(trade_date) AS long) AS hi FROM "
                                + scope.outputTable());
        if (outputBounds.get("lo") != null
                && (date(outputBounds.get("lo")).isBefore(start)
                        || !date(outputBounds.get("hi")).isBefore(end)))
            throw new IllegalStateException("ETF output contains dates outside source history");
        long count = 0;
        // 双侧 JOIN 都施加日期边界，将全历史核验拆成有限窗口，避免单次扫描整个关联历史。
        for (LocalDate lo = start; lo.isBefore(end); lo = lo.plusDays(31)) {
            LocalDate hi = lo.plusDays(31).isBefore(end) ? lo.plusDays(31) : end;
            if (!scope.legacy()) {
                requireSourceWindow(scope.shareTable(), scope.shareDate(), lo, hi);
                requireSourceWindow(scope.dailyTable(), scope.dailyDate(), lo, hi);
            }
            String direct =
                    scopedSql()
                            .replace(
                                    "SAMPLE BY",
                                    "WHERE s."
                                            + scope.shareDate()
                                            + ">=cast(? AS TIMESTAMP) AND s."
                                            + scope.shareDate()
                                            + "<cast(? AS TIMESTAMP) "
                                            + "AND d."
                                            + scope.dailyDate()
                                            + ">=cast(? AS TIMESTAMP"
                                            + (scope.dailyVersion() == 2 ? "_NS" : "")
                                            + ") AND d."
                                            + scope.dailyDate()
                                            + "<cast(? AS TIMESTAMP"
                                            + (scope.dailyVersion() == 2 ? "_NS" : "")
                                            + ") SAMPLE BY");
            var expected =
                    jdbc.queryForList(
                            "SELECT cast(trade_date AS long) AS trade_date,etf_count,total_share,total_size_yi FROM ("
                                    + direct
                                    + ") ORDER BY trade_date",
                            lo.toString(),
                            hi.toString(),
                            lo.toString(),
                            hi.toString());
            var actual =
                    jdbc.queryForList(
                            "SELECT cast(trade_date AS long) AS trade_date,etf_count,total_share,total_size_yi FROM "
                                    + scope.outputTable()
                                    + " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date",
                            lo.toString(),
                            hi.toString());
            requireParity(expected, actual);
            count += actual.size();
        }
        return count;
    }

    private String scopedSql() {
        return EtfMarketOverviewMaterializedViewReadGuard.sql(scope);
    }

    private Map<String, Object> scopedView() {
        return view(jdbc, scope);
    }

    private String scopedSnapshot(String table) {
        return snapshot(jdbc, table, scope);
    }

    private void requireSourceLayouts() {
        EtfMarketOverviewMaterializedViewReadGuard.requireSourceLayouts(jdbc, scope);
    }

    /** 有界逐窗清点先于新版 DDL，不凭 MAX 日期或输出桶数量证明来源可完整核验。 */
    private void verifySourceBudget() {
        var bounds =
                jdbc.queryForMap(
                        "SELECT cast(min("
                                + scope.shareDate()
                                + ") AS long) AS lo,cast(max("
                                + scope.shareDate()
                                + ") AS long) AS hi FROM "
                                + scope.shareTable());
        if (bounds.get("lo") == null) {
            Long rows =
                    jdbc.queryForObject("SELECT count() FROM " + scope.shareTable(), Long.class);
            if (rows == null || rows != 0) throw new IllegalStateException("ETF 日期为空但来源非空");
            return;
        }
        LocalDate start = date(bounds.get("lo")), end = date(bounds.get("hi")).plusDays(1);
        if (ChronoUnit.DAYS.between(start, end) > 36600)
            throw new IllegalStateException("ETF 历史超出核验预算");
        for (LocalDate lo = start; lo.isBefore(end); lo = lo.plusDays(31)) {
            LocalDate hi = lo.plusDays(31).isBefore(end) ? lo.plusDays(31) : end;
            requireSourceWindow(scope.shareTable(), scope.shareDate(), lo, hi);
            requireSourceWindow(scope.dailyTable(), scope.dailyDate(), lo, hi);
        }
    }

    private void requireSourceWindow(String table, String column, LocalDate lo, LocalDate hi) {
        String type = table.equals(scope.dailyTable()) ? "TIMESTAMP_NS" : "TIMESTAMP";
        String where =
                column + ">=cast(? AS " + type + ") AND " + column + "<cast(? AS " + type + ")";
        Long count =
                jdbc.queryForObject(
                        "SELECT count() FROM " + table + " WHERE " + where,
                        Long.class,
                        lo.toString(),
                        hi.toString());
        if (count == null || count < 0 || count > 2_000_000)
            throw new IllegalStateException("ETF 来源窗口超出完整核验预算");
        long dayUnits = table.equals(scope.dailyTable()) ? 86_400_000_000_000L : 86_400_000_000L;
        if (!jdbc.queryForList(
                        "SELECT ts_code FROM "
                                + table
                                + " WHERE "
                                + where
                                + " AND (ts_code IS NULL OR cast("
                                + column
                                + " AS long)%"
                                + dayUnits
                                + "<>0) LIMIT 1",
                        lo.toString(),
                        hi.toString())
                .isEmpty()) throw new IllegalStateException("ETF 来源日期不是 UTC 零点载体或证券键为空");
        if (!jdbc.queryForList(
                        "SELECT * FROM (SELECT "
                                + column
                                + ",ts_code,count() AS n FROM "
                                + table
                                + " WHERE "
                                + where
                                + " GROUP BY "
                                + column
                                + ",ts_code) WHERE n>1 LIMIT 1",
                        lo.toString(),
                        hi.toString())
                .isEmpty()) throw new IllegalStateException("ETF 来源业务键重复");
    }

    /** 调用方须先确认原发送者终止；仅按原意图核验，不安装、刷新或改写原输入。 */
    public Map<String, Object> reconcile(String runId, boolean originalSenderStopped)
            throws Exception {
        if (!originalSenderStopped) throw new IllegalArgumentException("须先确认原发送者已停止");
        var journal = new EtfOverviewRefreshJournal(ledger);
        var op = journal.get(runId);
        if (!op.state().equals("IN_DOUBT")) throw new IllegalStateException("原 ETF 刷新不处于未决状态");
        var original = new EtfOverviewMaintenance(jdbc, ledger, timeout, op.scope());
        if (!op.scope()
                        .definition()
                        .equals(
                                JobDefinitionJson.mapper()
                                        .readValue(op.definitionJson(), DatasetDefinition.class))
                || !EtfMarketOverviewMaterializedViewReadGuard.normalized(op.definitionSql())
                        .equals(
                                EtfMarketOverviewMaterializedViewReadGuard.normalized(
                                        original.scopedSql())))
            throw new IllegalStateException("原 ETF 冻结定义未受支持，保留原件");
        try (var channel =
                        FileChannel.open(
                                ledger.resolveSibling(ledger.getFileName() + ".etf-mv.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("原 ETF 刷新发送者仍持锁");
            if (!op.checkpoint().equals(checkpointName(jdbc, op.scope())))
                throw new IllegalStateException("原数据库或输出归属已变");
            if (!op.scope().legacy()) original.requireSourceLayouts();
            if (!op.left().equals(original.scopedSnapshot(op.scope().shareTable()))
                    || !op.right().equals(original.scopedSnapshot(op.scope().dailyTable())))
                throw new IllegalStateException("原冻结来源已变，不能重盖原证书");
            var view = original.scopedView();
            requireDefinition(view, op.scope());
            if (!"valid".equals(view.get("view_status"))
                    || nonempty(view.get("invalidation_reason"))
                    || !(view.get("base_table_txn") instanceof Number base)
                    || base.longValue() < 0
                    || !(view.get("refresh_base_table_txn") instanceof Number refresh)
                    || refresh.longValue() != base.longValue()
                    || Objects.toString(view.get("last_refresh_finish_timestamp"), "").isBlank())
                throw new IllegalStateException("原 ETF 刷新尚未追平，不能结束未决状态");
            String output = original.scopedSnapshot(op.scope().outputTable());
            if (!op.outputBefore().equals("ABSENT")
                    && !generation(op.outputBefore()).equals(generation(output)))
                throw new IllegalStateException("原 ETF 输出被替换");
            if (!op.scope().legacy()) original.verifySourceBudget();
            long rows = original.verifyHistory();
            if (!op.left().equals(original.scopedSnapshot(op.scope().shareTable()))
                    || !op.right().equals(original.scopedSnapshot(op.scope().dailyTable()))
                    || !output.equals(original.scopedSnapshot(op.scope().outputTable())))
                throw new IllegalStateException("ETF 只读核验期间版本变化");
            new MaterializedViewCheckpoints(ledger)
                    .save(
                            op.checkpoint(),
                            new Checkpoint(
                                    definitionHash(op.scope()),
                                    op.left(),
                                    op.right(),
                                    output,
                                    Objects.toString(
                                            view.get("last_refresh_finish_timestamp"), "")));
            new MaterializedViewCheckpoints(ledger)
                    .saveDateVerification(
                            op.checkpoint(),
                            new Checkpoint(
                                    definitionHash(op.scope()),
                                    op.left(),
                                    op.right(),
                                    output,
                                    Objects.toString(
                                            view.get("last_refresh_finish_timestamp"), "")),
                            rows);
            journal.complete(op.id(), output);
            versionIfActivated(jdbc, ledger, op.scope());
            return Map.of(
                    "status",
                    "VERIFIED",
                    "runId",
                    op.id(),
                    "output",
                    op.scope().outputTable(),
                    "verifiedRows",
                    rows,
                    "mode",
                    "READ_ONLY_RECONCILIATION");
        }
    }

    static void requireParity(
            List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        if (expected.size() != actual.size())
            throw new IllegalStateException(
                    "ETF MV row coverage differs from both-source aggregate");
        for (int i = 0; i < expected.size(); i++) {
            var a = expected.get(i);
            var b = actual.get(i);
            if (!date(a.get("trade_date")).equals(date(b.get("trade_date")))
                    || !Objects.equals(a.get("etf_count"), b.get("etf_count")))
                throw new IllegalStateException("ETF MV business keys/count differ");
            for (String field : List.of("total_share", "total_size_yi")) {
                Object x = a.get(field), y = b.get(field);
                if (x == null && y == null) continue;
                if (!(x instanceof Number xn)
                        || !(y instanceof Number yn)
                        || !Double.isFinite(xn.doubleValue())
                        || !Double.isFinite(yn.doubleValue())
                        || Math.abs(xn.doubleValue() - yn.doubleValue())
                                > 1e-8 + 1e-9 * Math.abs(xn.doubleValue()))
                    throw new IllegalStateException("ETF MV aggregate differs: " + field);
            }
        }
    }

    private static LocalDate date(Object value) {
        // JDBC 的无时区 Timestamp 是日期标签，不能按 JVM 时区再当事件 Instant 转换。
        if (value instanceof Number n)
            return CalendarTimestamp.fromStorageEpoch(n.longValue(), EpochUnit.MICROS).date();
        if (value instanceof Timestamp t) {
            if (!t.toLocalDateTime().toLocalTime().equals(LocalTime.MIDNIGHT))
                throw new IllegalArgumentException("ETF 交易日载体不是零点");
            return t.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate d) return d;
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    private static String generation(String snapshot) {
        var parts = snapshot.split(":");
        return String.join(":", Arrays.copyOf(parts, 3));
    }

    private static boolean nonempty(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
