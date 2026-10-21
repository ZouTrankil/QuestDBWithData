package com.zoutrankil.data.maintenance.application;

import static com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal.json;

import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal.Operation;
import com.zoutrankil.data.maintenance.storage.BacktestRefreshJournal.Request;
import com.zoutrankil.data.repository.BacktestDailyMaterializationReadGuard.NotReadyException;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort.State;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier.RowDigest;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 有限、显式的原生 FULL 维护；普通读取不调用，未决恢复只观察原意图。 */
public final class BacktestMvMaintenance {
    private final JdbcTemplate jdbc;
    private final Path ledger;
    private final Duration timeout;
    private final BacktestDailyScope configuredScope;

    public BacktestMvMaintenance(
            JdbcTemplate jdbc, Path ledger, Duration timeout, BacktestDailyScope scope) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.ledger = ledger.toAbsolutePath().normalize();
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("回测维护超时必须为正");
        timeout.toNanos();
        this.configuredScope = Objects.requireNonNull(scope);
    }

    private record Prepared(Request request, State before, RowDigest actual, boolean current) {}

    /** 计划只读，不创建 SQLite、锁文件或刷新意图。 */
    public Map<String, Object> plan() {
        var journal = new BacktestRefreshJournal(ledger);
        if (journal.pending(configuredScope.outputTable()).isPresent())
            throw new IllegalStateException("存在原回测 FULL 未决意图，先查看状态并按原意图核验");
        return describe(prepare(configuredScope));
    }

    public Map<String, Object> refresh(boolean sourcesStopped) throws Exception {
        if (!sourcesStopped)
            throw new IllegalArgumentException("须先完成原来源停写/未决归属检查，并显式声明 sources-stopped");
        Files.createDirectories(ledger.getParent());
        try (var channel =
                        FileChannel.open(
                                lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("回测维护的原执行器仍持锁");
            var journal = new BacktestRefreshJournal(ledger);
            if (journal.pending(configuredScope.outputTable()).isPresent())
                throw new IllegalStateException("原回测 FULL 未决，普通调用不能重发");
            var prepared = prepare(configuredScope);
            if (prepared.current()) return describe(prepared);
            var request = prepared.request();
            var port = new BacktestDailyMaterializedViewPort(jdbc, request.scope());
            if (!request.beforeVersion().equals(json(port.refreshAdmissionState())))
                throw new IllegalStateException("回测输入在意图前变化，未提交 FULL");
            // SQLite durable intent 必须先于唯一一条 FULL；之后任何异常均保留 IN_DOUBT。
            var operation = journal.begin(request);
            if (!request.beforeVersion().equals(json(port.refreshAdmissionState())))
                throw new IllegalStateException("回测输入在原意图后变化，保留未决，不发送 FULL");
            jdbc.execute(request.refreshSql());
            var result = awaitVerified(operation);
            journal.complete(operation, result);
            return result;
        }
    }

    public Map<String, Object> reconcile(String runId, boolean originalSenderStopped)
            throws Exception {
        if (!originalSenderStopped) throw new IllegalArgumentException("须确认原提交者已结束，不自动认领未决 FULL");
        var journal = new BacktestRefreshJournal(ledger);
        var original = journal.get(runId);
        try (var channel =
                        FileChannel.open(
                                lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("原回测 FULL 提交者仍持锁");
            // 只使用原请求的 scope、输入、窗口与 SQL；当前配置可以已切回另一布局。
            var result = awaitVerified(original);
            if (original.state().equals("VERIFIED")) {
                if (!json(result).equals(original.resultJson()))
                    throw new IllegalStateException("原已验证输出已变化，不重签原完成记录");
            } else journal.complete(original, result);
            return result;
        }
    }

    private Path lockPath() {
        return ledger.resolveSibling(ledger.getFileName() + ".backtest-mv.lock");
    }

    private Prepared prepare(BacktestDailyScope scope) {
        var port = new BacktestDailyMaterializedViewPort(jdbc, scope);
        var before = port.refreshAdmissionState();
        var bounds = bounds(scope);
        if (bounds == null) {
            if (!before.equals(port.sourceState()))
                throw new IllegalStateException("空回测窗口观察期间版本变化");
            // 原生 SQL 由空 stk_limit 驱动，不可能生成行；此处不为来源空窗签覆盖凭证。
            return new Prepared(null, before, null, true);
        }
        var checker = new BacktestDailyWindowVerifier(jdbc);
        var expected = checker.expectedDigest(scope, bounds[0], bounds[1]);
        var actual = checker.outputDigest(scope, bounds[0], bounds[1]);
        if (!before.equals(port.refreshAdmissionState()))
            throw new IllegalStateException("回测计划核对期间版本变化");
        boolean current = false;
        if (expected.equals(actual)) {
            try {
                current = before.equals(port.sourceState());
            } catch (NotReadyException waiting) {
                if (waiting.reason() != NotReadyException.Reason.SOURCES_CHANGED) throw waiting;
            }
        }
        var request =
                new Request(
                        1,
                        scope,
                        json(scope.definition()),
                        BacktestDailyMaterializedViewPort.createSql(scope),
                        "REFRESH MATERIALIZED VIEW " + scope.outputTable() + " FULL",
                        inputs(before, scope),
                        identity(before, scope),
                        json(before),
                        number(before.view(), "finished"),
                        number(before.tables().get(scope.outputTable()), "sequencerTxn"),
                        bounds[0],
                        bounds[1],
                        expected);
        return new Prepared(request, before, actual, current);
    }

    private Map<String, Object> describe(Prepared plan) {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", plan.current() ? "CURRENT" : "FULL_REQUIRED");
        result.put("scope", configuredScope);
        result.put("request", plan.request());
        result.put("actual", plan.actual());
        result.put("sources", plan.before());
        result.put("sourceCoverageVerified", false);
        result.put("refreshSubmitted", false);
        return result;
    }

    private Map<String, Object> awaitVerified(Operation operation) throws Exception {
        Request request = operation.request();
        var port = new BacktestDailyMaterializedViewPort(jdbc, request.scope());
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                var state = port.refreshAdmissionState();
                if (!request.inputVersions().equals(inputs(state, request.scope()))
                        || !request.outputIdentity().equals(identity(state, request.scope())))
                    throw new IllegalStateException("原回测 FULL 来源版本或物理代际已变，保留原意图");
                if (number(state.view(), "finished") <= request.priorFinished()
                        || number(state.tables().get(request.scope().outputTable()), "sequencerTxn")
                                <= request.priorOutputTxn()) {
                    if (System.nanoTime() >= deadline)
                        throw new IllegalStateException("未观察到原 FULL 完成，不补完成、不重发");
                    Thread.sleep(100);
                    continue;
                }
                // 真实 FULL 结束并不够：重算全部历史键值，再核对原预期摘要及窗外是否有残行。
                var checker = new BacktestDailyWindowVerifier(jdbc);
                var verified = checker.verifyRange(request.scope(), request.from(), request.to());
                var actual = checker.outputDigest(request.scope(), request.from(), request.to());
                if (!request.expected().equals(actual))
                    throw new IllegalStateException("原回测 FULL 预期完整行摘要不符");
                var outputBounds = outputBounds(request.scope());
                if (outputBounds != null
                        && (outputBounds[0].isBefore(request.from())
                                || outputBounds[1].isAfter(request.to())))
                    throw new IllegalStateException("回测 FULL 原窗口外仍有结果，不补完成");
                if (!verified.equals(port.sourceState())
                        || !request.inputVersions().equals(inputs(verified, request.scope()))
                        || !request.outputIdentity().equals(identity(verified, request.scope())))
                    throw new IllegalStateException("回测 FULL 完整核对期间版本变化");
                return Map.of(
                        "status",
                        "VERIFIED",
                        "runId",
                        operation.id(),
                        "scope",
                        request.scope(),
                        "protocol",
                        "backtest-full-v1",
                        "verifiedRows",
                        actual.rows(),
                        "rowSha256",
                        actual.sha256(),
                        "sources",
                        verified,
                        "sourceCoverageVerified",
                        false);
            } catch (NotReadyException waiting) {
                if (System.nanoTime() >= deadline) throw waiting;
                Thread.sleep(100);
            }
        }
    }

    /** MIN/MAX 仅确定待比较的完整窗口；完成仍依赖逐月全部键值和稳定版本。 */
    private LocalDate[] bounds(BacktestDailyScope scope) {
        var base = range("stk_limit");
        var output = outputBounds(scope);
        if (base == null) return output;
        if (output == null) return base;
        var from = base[0].isBefore(output[0]) ? base[0] : output[0];
        var to = base[1].isAfter(output[1]) ? base[1] : output[1];
        if (ChronoUnit.DAYS.between(from, to) > 36_600)
            throw new IllegalArgumentException("回测全历史超过核验窗口预算");
        return new LocalDate[] {from, to};
    }

    private LocalDate[] outputBounds(BacktestDailyScope scope) {
        return range(scope.outputTable());
    }

    private LocalDate[] range(String table) {
        var rows =
                jdbc.queryForList(
                        "SELECT cast(min(trade_date) AS long) AS lo,cast(max(trade_date) AS long) AS hi FROM "
                                + table);
        if (rows.size() != 1) throw new IllegalStateException("回测完整日期范围不可用");
        Object lo = rows.getFirst().get("lo"), hi = rows.getFirst().get("hi");
        if (lo == null && hi == null) return null;
        if (!(lo instanceof Number lower)
                || !(hi instanceof Number upper)
                || Math.floorMod(lower.longValue(), 86_400_000_000L) != 0
                || Math.floorMod(upper.longValue(), 86_400_000_000L) != 0)
            throw new IllegalStateException("回测源日期不是UTC午夜业务日期载体");
        var from = LocalDate.ofEpochDay(lower.longValue() / 86_400_000_000L);
        var to = LocalDate.ofEpochDay(upper.longValue() / 86_400_000_000L).plusDays(1);
        if (!from.isBefore(to) || ChronoUnit.DAYS.between(from, to) > 36_600)
            throw new IllegalArgumentException("回测完整窗口无效或超限");
        return new LocalDate[] {from, to};
    }

    private static long number(Map<String, Object> row, String name) {
        if (!(row.get(name) instanceof Number n) || n.longValue() < 0)
            throw new IllegalStateException("回测原生版本缺失：" + name);
        return n.longValue();
    }

    private static String inputs(State state, BacktestDailyScope scope) {
        var source = new TreeMap<String, Object>();
        for (String name : scope.sources()) {
            var version = new TreeMap<String, Object>();
            for (String field : BacktestRefreshJournal.VERSION_FIELDS)
                version.put(field, state.tables().get(name).get(field));
            source.put(name, version);
        }
        return json(source);
    }

    private static String identity(State state, BacktestDailyScope scope) {
        var row = state.tables().get(scope.outputTable());
        if (!(row.get("directoryName") instanceof String directory) || directory.isBlank())
            throw new IllegalStateException("回测输出物理目录缺失");
        return json(
                Map.of(
                        "table",
                        scope.outputTable(),
                        "id",
                        number(row, "id"),
                        "directory",
                        directory));
    }
}
