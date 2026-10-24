package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.application.EtfBasicJobService;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 日批前置资料只复用现有owner；意图后失败不自动新建运行重发。 */
@Service
@Lazy
public class MainStrategyReferencePreparation {
    private final ExchangeCalendarJobService calendar;
    private final StockDetailInfoJobService stock;
    private final EtfBasicJobService etf;
    private final JdbcTemplate jdbc;
    private final Path ledgerPath, root;

    public MainStrategyReferencePreparation(
            ExchangeCalendarJobService calendar,
            StockDetailInfoJobService stock,
            EtfBasicJobService etf,
            JdbcTemplate jdbc,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.sync.batch.artifact-root:var/main-strategy-batch}") String artifacts) {
        this.calendar = calendar;
        this.stock = stock;
        this.etf = etf;
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(30);
        ledgerPath = Path.of(ledger).toAbsolutePath().normalize();
        root = Path.of(artifacts).toAbsolutePath().normalize().resolve("reference-preparation-v1");
    }

    @FunctionalInterface
    interface Planner {
        Planned plan() throws Exception;
    }

    @FunctionalInterface
    interface Execution {
        void run(String runId) throws Exception;
    }

    record Planned(SyncJobDefinition.FrozenRequest request, String target, Execution execution) {}

    public void prepare(Clock clock) throws Exception {
        if (!"exchange_calendar".equals(calendar.tableName())
                || !"stock_detail_info".equals(stock.tableName())
                || !"etf_basic".equals(etf.tableName()))
            throw new IllegalStateException("主策略前置资料必须绑定同名正式目标");
        LocalDate day = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IllegalStateException("资料准备目录不能是链接");
        try (var channel =
                        FileChannel.open(
                                root.resolve("operation.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有主策略资料准备任务");
            // 日历覆盖近期补数及未来开市日；不推导窗口以外的历史覆盖。
            one(
                    day,
                    "exchange_calendar",
                    () -> {
                        var p =
                                calendar.plan(
                                        List.of("SSE", "SZSE"),
                                        day.minusDays(31),
                                        day.plusDays(31),
                                        day,
                                        SyncJobDefinition.Mode.BACKFILL);
                        return new Planned(
                                p.request(), p.targetId(), id -> calendar.executeRecorded(id, p));
                    });
            one(
                    day,
                    "stock_detail_info",
                    () -> {
                        var p = stock.plan(List.of(), true, day);
                        return new Planned(p, stock.targetId(), id -> stock.execute(id, null, p));
                    });
            one(
                    day,
                    "etf_basic",
                    () -> {
                        var p = etf.plan(day);
                        return new Planned(
                                p.request(), p.targetId(), id -> etf.executeRecorded(id, p));
                    });
        }
    }

    void one(LocalDate day, String table, Planner planner) throws Exception {
        Path folder = root.resolve(day.toString());
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(folder)) throw new IllegalStateException("原资料目录不能是链接");
        Path intent = folder.resolve(table + "-intent.json");
        Path complete = folder.resolve(table + "-verified.json");
        if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
            var saved = read(intent);
            validateRun(saved, table, day);
            // 意图之后没有最终凭证，即使原运行成功也不自动认证；先核对原运行和物理结果。
            if (!Files.isRegularFile(complete, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("前置资料需要显式核对原运行：" + saved.path("runId").asText());
            var proof = read(complete);
            if (!proof.path("intentSha256").asText().equals(FileEvidenceStore.sha256(bytes(intent)))
                    || !proof.path("tableClock")
                            .equals(
                                    JobDefinitionJson.mapper()
                                            .readTree(
                                                    JobDefinitionJson.canonicalMapper()
                                                            .writeValueAsBytes(clock(table)))))
                throw new IllegalStateException("前置资料原凭证或目标版本变化，需要显式修订：" + table);
            return;
        }
        if (Files.exists(complete, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("前置资料凭证缺少原意图");
        var planned = planner.plan();
        String runId = "strategy-reference-" + UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>();
        body.put("schema", "main-strategy.reference-preparation.v1");
        body.put("table", table);
        body.put("logicalDate", day.toString());
        body.put("runId", runId);
        body.put("targetId", planned.target());
        body.put("frozenJson", SyncRequestIdentity.snapshotJson(planned.request()));
        body.put(
                "requestFingerprint",
                SyncRequestIdentity.fingerprint(planned.request(), planned.target()));
        FileEvidenceStore.writeNewDurable(
                intent, JobDefinitionJson.canonicalMapper().writeValueAsBytes(body));
        // 原编号在发送前已固定；重启看到意图就阻断，不从超时推断可以重发。
        planned.execution().run(runId);
        var saved = read(intent);
        validateRun(saved, table, day);
        var verifiedClock = verifyCurrent(saved, table);
        var proof = new LinkedHashMap<String, Object>();
        proof.put("intentSha256", FileEvidenceStore.sha256(bytes(intent)));
        proof.put("tableClock", verifiedClock);
        FileEvidenceStore.writeNewDurable(
                complete, JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof));
    }

    /** 原owner已经完成显式恢复后，再只读核对来源与当前结果，补齐本编排的完成凭证。 */
    public Map<String, Object> reconcile(LocalDate day, String table, boolean writerStopped)
            throws Exception {
        if (!writerStopped
                || !Set.of("exchange_calendar", "stock_detail_info", "etf_basic").contains(table))
            throw new IllegalArgumentException("需要明确停止原writer及正确的前置资料表");
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("operation.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有前置资料任务");
            Path intent = root.resolve(day.toString()).resolve(table + "-intent.json");
            var saved = read(intent);
            validateRun(saved, table, day);
            String id = saved.path("runId").asText();
            var before = verifyCurrent(saved, table);
            var proof =
                    Map.of(
                            "intentSha256",
                            FileEvidenceStore.sha256(bytes(intent)),
                            "tableClock",
                            before);
            byte[] body = JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof);
            Path complete = intent.resolveSibling(table + "-verified.json");
            if (Files.exists(complete, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(body, bytes(complete)))
                    throw new IllegalStateException("原资料完成凭证不符，不能覆盖");
            } else FileEvidenceStore.writeNewDurable(complete, body);
            return Map.of(
                    "table", table, "runId", id, "state", "VERIFIED", "databaseRowsWritten", 0);
        }
    }

    private Map<String, Object> verifyCurrent(JsonNode saved, String table) throws Exception {
        var before = clock(table);
        String id = saved.path("runId").asText();
        switch (table) {
            case "exchange_calendar" -> calendar.revalidate(id);
            case "stock_detail_info" -> {
                var prior =
                        FrozenRunRequest.restoreCompleted(
                                ledgerPath, id, StockDetailInfoJobService.definition());
                stock.revalidateGroupChild(id, prior.targetId(), prior.request());
            }
            case "etf_basic" -> etf.revalidate(id);
            default -> throw new IllegalArgumentException("未知前置资料表");
        }
        if (!before.equals(clock(table))) throw new IllegalStateException("核对期间前置资料物理版本变化");
        return before;
    }

    private void validateRun(JsonNode saved, String table, LocalDate day) throws Exception {
        if (!"main-strategy.reference-preparation.v1".equals(saved.path("schema").asText())
                || !table.equals(saved.path("table").asText())
                || !day.toString().equals(saved.path("logicalDate").asText()))
            throw new IllegalStateException("前置资料意图归属不符");
        var ledger = SyncRunLedger.openReadOnly(ledgerPath);
        String id = saved.path("runId").asText();
        var run = ledger.getRun(id);
        if (!run.jobId().equals("data." + table)
                || !run.logicalDate().equals(day.toString())
                || !run.targetId().equals(saved.path("targetId").asText())
                || !run.frozenJson().equals(saved.path("frozenJson").asText())
                || !SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())
                        .equals(saved.path("requestFingerprint").asText())
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(ledger.get(id).state()))
            throw new IllegalStateException("前置资料原运行未通过完整核验：" + id);
    }

    private Map<String, Object> clock(String table) {
        var rows =
                jdbc.queryForList(
                        "SELECT t.id,t.directoryName,t.table_txn,t.walEnabled,t.table_suspended,"
                                + "w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t "
                                + "JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",
                        table);
        if (rows.size() != 1) throw new IllegalStateException("前置资料物理目标不唯一：" + table);
        var c = rows.getFirst();
        if (!Boolean.TRUE.equals(c.get("walEnabled"))
                || Boolean.TRUE.equals(c.get("table_suspended"))
                || Boolean.TRUE.equals(c.get("suspended"))
                || !(c.get("writerTxn") instanceof Number writer)
                || !(c.get("sequencerTxn") instanceof Number sequence)
                || writer.longValue() != sequence.longValue()
                || !(c.get("bufferedTxnSize") instanceof Number buffered)
                || buffered.longValue() != 0)
            throw new IllegalStateException("前置资料WAL未稳定：" + table);
        return c;
    }

    private byte[] bytes(Path path) throws Exception {
        FileEvidenceStore.owned(
                path,
                root,
                FileEvidenceStore.Ownership.DESCENDANT,
                FileEvidenceStore.Symlinks.REJECT_LEAF,
                () -> new IllegalStateException("资料凭证越界"));
        return FileEvidenceStore.readBounded(
                path, 1024 * 1024, () -> new IllegalStateException("资料凭证超预算"));
    }

    private JsonNode read(Path path) throws Exception {
        return JobDefinitionJson.mapper().readTree(bytes(path));
    }
}
