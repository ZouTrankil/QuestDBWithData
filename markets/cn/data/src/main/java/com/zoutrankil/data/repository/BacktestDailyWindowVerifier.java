package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** 水位追平不能证明 ASOF 历史沿用值已重算；按原始 SQL 逐月核对完整键值。 */
public final class BacktestDailyWindowVerifier {
    private static final int MAX_MONTH_ROWS = 200_000;
    private static final long DAY_MICROS = 86_400_000_000L;
    private static final String DRIVING_DATES =
            "SELECT DISTINCT trade_date FROM stk_limit ORDER BY trade_date";
    private final JdbcTemplate jdbc;
    // 缓存仅属于这个数据源和当前进程；版本变化或重启后重新核对，不重签旧证据。
    private final Map<Proof, Boolean> verified = new LinkedHashMap<>();

    private record Proof(
            BacktestDailyScope scope,
            LocalDate from,
            LocalDate to,
            BacktestDailyMaterializedViewPort.State state) {}

    public BacktestDailyWindowVerifier(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(120);
        this.jdbc.setMaxRows(MAX_MONTH_ROWS + 1);
    }

    /** 公共查询必须提供业务日期，不能以 LIMIT 代替核验范围。 */
    public String version(BacktestDailyScope scope, DatasetReadQuery query) {
        LocalDate from, to;
        if (query.equalities().get("trade_date") instanceof LocalDate day) {
            from = day;
            to = day.plusDays(1);
        } else if ("trade_date".equals(query.rangeColumn())
                && query.fromInclusive() instanceof LocalDate lower
                && query.toExclusive() instanceof LocalDate upper) {
            from = lower;
            to = upper;
        } else throw new IllegalArgumentException("回测 MV 读取须提供 LocalDate 交易日或日期范围");
        var state = verifyRange(scope, from, to);
        return new BacktestDailyMaterializedViewPort(jdbc, scope).fingerprint(state);
    }

    /** 分月限制内存和单次输出；保留 ASOF 所需的窗口前历史价格。 */
    public BacktestDailyMaterializedViewPort.State verifyRange(
            BacktestDailyScope scope, LocalDate from, LocalDate to) {
        Objects.requireNonNull(scope);
        if (from == null
                || to == null
                || !from.isBefore(to)
                || ChronoUnit.DAYS.between(from, to) > 36_600)
            throw new IllegalArgumentException("回测 MV 核验须使用非空、最多36600天的左闭右开日期范围");
        var port = new BacktestDailyMaterializedViewPort(jdbc, scope);
        var before = port.sourceState();
        var proof = new Proof(scope, from, to, before);
        synchronized (verified) {
            if (verified.containsKey(proof)) return before;
        }
        String raw = rawSql(scope), columns = projectedColumns(scope);
        for (LocalDate lower = from; lower.isBefore(to); ) {
            LocalDate upper = lower.withDayOfMonth(1).plusMonths(1);
            if (upper.isAfter(to)) upper = to;
            // 只限制驱动日历，不截断 ASOF 右侧历史，否则停牌沿用价会被错误清空。
            var expected =
                    jdbc.queryForList(
                            "SELECT "
                                    + columns
                                    + " FROM ("
                                    + raw
                                    + ") ORDER BY trade_date,ts_code LIMIT "
                                    + (MAX_MONTH_ROWS + 1),
                            micros(lower),
                            micros(upper));
            var actual =
                    jdbc.queryForList(
                            "SELECT "
                                    + columns
                                    + " FROM "
                                    + scope.outputTable()
                                    + " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "
                                    + (MAX_MONTH_ROWS + 1),
                            micros(lower),
                            micros(upper));
            requireRows(expected, lower, upper, scope);
            requireRows(actual, lower, upper, scope);
            if (!expected.equals(actual))
                throw new BacktestDailyMaterializationReadGuard.NotReadyException(
                        BacktestDailyMaterializationReadGuard.NotReadyException.Reason
                                .VALUES_CHANGED,
                        "回测 MV 与原始来源完整键值不一致："
                                + lower
                                + ".."
                                + upper
                                + "；历史 ASOF 修订不能只靠基表水位，须按原归属完成刷新后重新核验");
            if (!before.equals(port.sourceState()))
                throw new IllegalStateException("回测核验期间来源或 MV 版本变化");
            lower = upper;
        }
        synchronized (verified) {
            verified.put(proof, true);
            while (verified.size() > 16) verified.remove(verified.keySet().iterator().next());
        }
        return before;
    }

    public record RowDigest(long rows, String sha256) {}

    /** 发送维护意图前，调用方须前后固定原输入版本；此方法不把摘要当来源覆盖证明。 */
    public RowDigest expectedDigest(BacktestDailyScope scope, LocalDate from, LocalDate to) {
        return digest(scope, from, to, true);
    }

    public RowDigest outputDigest(BacktestDailyScope scope, LocalDate from, LocalDate to) {
        return digest(scope, from, to, false);
    }

    private RowDigest digest(BacktestDailyScope scope, LocalDate from, LocalDate to, boolean raw) {
        if (from == null
                || to == null
                || !from.isBefore(to)
                || ChronoUnit.DAYS.between(from, to) > 36_600)
            throw new IllegalArgumentException("回测摘要须使用非空、最多36600天的日期范围");
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("backtest-canonical-13-v1\n".getBytes(StandardCharsets.UTF_8));
            long count = 0;
            String columns = projectedColumns(scope);
            for (LocalDate lower = from; lower.isBefore(to); ) {
                if (Thread.currentThread().isInterrupted())
                    throw new CancellationException("回测摘要已中断");
                LocalDate upper = lower.withDayOfMonth(1).plusMonths(1);
                if (upper.isAfter(to)) upper = to;
                String sql =
                        raw
                                ? "SELECT "
                                        + columns
                                        + " FROM ("
                                        + rawSql(scope)
                                        + ") ORDER BY trade_date,ts_code LIMIT "
                                        + (MAX_MONTH_ROWS + 1)
                                : "SELECT "
                                        + columns
                                        + " FROM "
                                        + scope.outputTable()
                                        + " WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date,ts_code LIMIT "
                                        + (MAX_MONTH_ROWS + 1);
                var rows = jdbc.queryForList(sql, micros(lower), micros(upper));
                requireRows(rows, lower, upper, scope);
                for (var row : rows) {
                    byte[] bytes = JobDefinitionJson.canonicalMapper().writeValueAsBytes(row);
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    hash.update(bytes);
                    count++;
                }
                lower = upper;
            }
            return new RowDigest(count, HexFormat.of().formatHex(hash.digest()));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("回测完整行摘要生成失败", failure);
        }
    }

    private static String rawSql(BacktestDailyScope scope) {
        String query =
                BacktestDailyMaterializedViewPort.querySql(
                        BacktestDailyMaterializedViewPort.createSql(scope));
        if (!query.contains(DRIVING_DATES)) throw new IllegalStateException("原始回测 SQL 缺少明确日期驱动边界");
        return query.replace(
                DRIVING_DATES,
                "SELECT DISTINCT trade_date FROM stk_limit WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) ORDER BY trade_date");
    }

    private static String projectedColumns(BacktestDailyScope scope) {
        return String.join(
                ",",
                scope.definition().columns().stream()
                        .map(
                                c ->
                                        c.storageName().equals("trade_date")
                                                ? "cast(trade_date AS long) AS trade_date"
                                                : c.storageName())
                        .toList());
    }

    private static void requireRows(
            List<Map<String, Object>> rows,
            LocalDate from,
            LocalDate to,
            BacktestDailyScope scope) {
        if (rows.size() > MAX_MONTH_ROWS) throw new IllegalStateException("回测月窗超过 200000 行核验预算");
        var keys = new HashSet<List<Object>>();
        for (var row : rows) {
            if (!(row.get("trade_date") instanceof Long day)
                    || Math.floorMod(day, DAY_MICROS) != 0
                    || day < micros(from)
                    || day >= micros(to)
                    || !(row.get("ts_code") instanceof String code)
                    || code.isBlank()
                    || !keys.add(List.of(day, code)))
                throw new IllegalStateException("回测核验缺少唯一完整交易日/证券键");
            if (!row.keySet().equals(new HashSet<>(scope.definition().storageColumns())))
                throw new IllegalStateException("回测核验必须包含完整13列");
            for (var column : scope.definition().columns()) {
                Object value = row.get(column.storageName());
                if (value == null) {
                    if (!column.nullable())
                        throw new IllegalStateException("回测必填字段为空：" + column.storageName());
                } else if (!(switch (column.storageType()) {
                    case TIMESTAMP, LONG -> value instanceof Long;
                    case SYMBOL -> value instanceof String;
                    case INT -> value instanceof Integer;
                    case DOUBLE -> value instanceof Double number && Double.isFinite(number);
                    default -> false;
                })) throw new IllegalStateException("回测值类型与完整契约不符：" + column.storageName());
            }
        }
    }

    private static long micros(LocalDate day) {
        return Math.multiplyExact(day.toEpochDay(), DAY_MICROS);
    }
}
