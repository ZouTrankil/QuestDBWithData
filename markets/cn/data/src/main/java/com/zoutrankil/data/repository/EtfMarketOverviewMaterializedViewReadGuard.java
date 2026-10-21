package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.fund.EtfOverviewScope;
import com.zoutrankil.data.maintenance.storage.EtfOverviewRefreshJournal;
import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** JOIN 两侧输入版本必须同时匹配本地运行账本中已经验证的证书。 */
public final class EtfMarketOverviewMaterializedViewReadGuard {
    public static final String OUTPUT = "mv_etf_market_overview_daily";
    public static final String SQL =
            """
            SELECT
                s.timestamp AS trade_date,
                count_distinct(s.ts_code) AS etf_count,
                sum(s.fd_share) AS total_share,
                sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
            FROM etf_share s
            JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp
            SAMPLE BY 1d ALIGN TO CALENDAR
            """;

    private EtfMarketOverviewMaterializedViewReadGuard() {}

    /** 原生输出必须有有效的当前证书；不再依赖普通视图别名。 */
    public static String versionIfActivated(JdbcTemplate jdbc) {
        return versionIfActivated(
                jdbc,
                Path.of(System.getProperty("app.sync.ledger-path", "var/sync-ledger.sqlite3")));
    }

    public static String versionIfActivated(JdbcTemplate jdbc, Path ledger) {
        return versionIfActivated(jdbc, ledger, EtfOverviewScope.LEGACY);
    }

    public static String versionIfActivated(
            JdbcTemplate jdbc, Path ledger, EtfOverviewScope scope) {
        if (!scope.legacy()) requireSourceLayouts(jdbc, scope);
        String share = snapshot(jdbc, scope.shareTable(), scope),
                daily = snapshot(jdbc, scope.dailyTable(), scope);
        String pin = sha(normalized(sql(scope)) + "|" + share + "|" + daily);
        var view = view(jdbc, scope);
        requireDefinition(view, scope);
        if (!"valid".equals(view.get("view_status"))
                || nonempty(view.get("invalidation_reason"))
                || counter(view, "refresh_base_table_txn") != counter(view, "base_table_txn"))
            throw new IllegalStateException(
                    "ETF native MV definition or checkpoint is not current");
        String output = snapshot(jdbc, scope.outputTable(), scope);
        String checkpoint = checkpointName(jdbc, scope);
        if (new EtfOverviewRefreshJournal(ledger).pending(checkpoint).isPresent())
            throw new IllegalStateException("ETF 刷新未决，拒绝读取旧凭证");
        var receipt =
                new MaterializedViewCheckpoints(ledger)
                        .find(checkpoint)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ETF local refresh checkpoint missing; run refresh-etf-overview"));
        if (!new MaterializedViewCheckpoints(ledger).hasDateVerification(checkpoint, receipt))
            throw new IllegalStateException("ETF 旧刷新证书缺独立的 UTC 日期窗口完整核验，须运行 refresh-etf-overview");
        if (!definitionHash(scope).equals(receipt.definitionHash())
                || !share.equals(receipt.leftVersion())
                || !daily.equals(receipt.rightVersion())
                || !output.equals(receipt.outputVersion())
                || !Objects.toString(view.get("last_refresh_finish_timestamp"), "")
                        .equals(receipt.refreshFinished()))
            throw new IllegalStateException(
                    "ETF JOIN inputs/output changed after verified refresh");
        if (!share.equals(snapshot(jdbc, scope.shareTable(), scope))
                || !daily.equals(snapshot(jdbc, scope.dailyTable(), scope))
                || !output.equals(snapshot(jdbc, scope.outputTable(), scope)))
            throw new IllegalStateException(
                    "ETF source/output revisions changed during verification");
        return "etf-native:" + checkpoint + "|" + pin + "|" + output;
    }

    public static Map<String, Object> view(JdbcTemplate jdbc) {
        return view(jdbc, EtfOverviewScope.LEGACY);
    }

    public static Map<String, Object> view(JdbcTemplate jdbc, EtfOverviewScope scope) {
        return one(
                jdbc.queryForList(
                        "SELECT * FROM materialized_views() WHERE view_name='"
                                + scope.outputTable()
                                + "'"));
    }

    public static void requireDefinition(Map<String, Object> view) {
        requireDefinition(view, EtfOverviewScope.LEGACY);
    }

    public static void requireDefinition(Map<String, Object> view, EtfOverviewScope scope) {
        if (!scope.shareTable().equals(view.get("base_table_name"))
                || !Set.of("manual", "immediate")
                        .contains(Objects.toString(view.get("refresh_type"), ""))
                || !normalized(sql(scope)).equals(normalized(view.get("view_sql"))))
            throw new IllegalStateException(
                    "Unexpected ETF native MV definition or refresh policy");
    }

    public static String definitionHash() {
        return definitionHash(EtfOverviewScope.LEGACY);
    }

    public static String definitionHash(EtfOverviewScope scope) {
        return sha(normalized(sql(scope)));
    }

    /** 只替换显式来源和日期名，原 INNER JOIN、聚合、单位及 NULL 规则不变。 */
    public static String sql(EtfOverviewScope scope) {
        if (scope.legacy()) return SQL;
        return "SELECT s."
                + scope.shareDate()
                + " AS trade_date, count_distinct(s.ts_code) AS etf_count, sum(s.fd_share) AS total_share, sum(s.fd_share * d.close) / 10000.0 AS total_size_yi FROM "
                + scope.shareTable()
                + " s JOIN "
                + scope.dailyTable()
                + " d ON s.ts_code = d.ts_code AND s."
                + scope.shareDate()
                + " = d."
                + scope.dailyDate()
                + " SAMPLE BY 1d ALIGN TO CALENDAR";
    }

    public static String checkpointName(JdbcTemplate jdbc) {
        return checkpointName(jdbc, EtfOverviewScope.LEGACY);
    }

    public static String checkpointName(JdbcTemplate jdbc, EtfOverviewScope scope) {
        String identity = jdbc.queryForObject("SELECT current_data_id()", String.class);
        if (identity == null || identity.isBlank())
            throw new IllegalStateException("QuestDB database identity unavailable");
        return scope.outputTable() + "@" + identity;
    }

    public static String normalized(Object value) {
        return value == null
                ? ""
                : value.toString().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String snapshot(JdbcTemplate jdbc, String table) {
        return snapshot(jdbc, table, EtfOverviewScope.LEGACY);
    }

    public static String snapshot(JdbcTemplate jdbc, String table, EtfOverviewScope scope) {
        if (!Set.of(scope.shareTable(), scope.dailyTable(), scope.outputTable()).contains(table))
            throw new IllegalArgumentException("Unknown ETF source/output");
        var row =
                one(
                        jdbc.queryForList(
                                "SELECT t.id,t.directoryName,t.table_txn,t.walEnabled,t.table_suspended,"
                                        + "t.wal_pending_row_count,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                                        + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",
                                table));
        if (!Boolean.TRUE.equals(row.get("walEnabled"))
                || !Boolean.FALSE.equals(row.get("suspended"))
                || !Boolean.FALSE.equals(row.get("table_suspended"))
                || counter(row, "wal_pending_row_count") != 0
                || counter(row, "bufferedTxnSize") != 0
                || counter(row, "writerTxn") != counter(row, "sequencerTxn"))
            throw new IllegalStateException("ETF source/output WAL is not settled: " + table);
        return table
                + ":"
                + counter(row, "id")
                + ":"
                + text(row, "directoryName")
                + ":"
                + counter(row, "table_txn")
                + ":"
                + counter(row, "sequencerTxn");
    }

    /** 日期载体精度和完整键须先对上，不能只凭相同列名继续 JOIN。 */
    public static void requireSourceLayouts(JdbcTemplate jdbc, EtfOverviewScope scope) {
        requireSourceLayout(jdbc, scope.shareTable(), scope.shareDate(), "TIMESTAMP", "fd_share");
        requireSourceLayout(jdbc, scope.dailyTable(), scope.dailyDate(), "TIMESTAMP_NS", "close");
    }

    private static void requireSourceLayout(
            JdbcTemplate jdbc, String table, String date, String precision, String value) {
        var columns = jdbc.queryForList("SELECT * FROM table_columns('" + table + "')");
        var byName = new HashMap<String, Map<String, Object>>();
        for (var column : columns)
            if (byName.put(Objects.toString(column.get("column")), column) != null)
                throw new IllegalStateException("ETF 来源元数据重复");
        var d = byName.get(date);
        var code = byName.get("ts_code");
        var v = byName.get(value);
        if (d == null
                || code == null
                || v == null
                || !precision.equals(d.get("type"))
                || !Boolean.TRUE.equals(d.get("designated"))
                || !Boolean.TRUE.equals(d.get("upsertKey"))
                || !"SYMBOL".equals(code.get("type"))
                || !Boolean.TRUE.equals(code.get("upsertKey"))
                || !"DOUBLE".equals(v.get("type"))
                || columns.stream().filter(c -> Boolean.TRUE.equals(c.get("upsertKey"))).count()
                        != 2)
            throw new IllegalStateException("ETF 来源的日期精度、指定列、业务键或数值类型不匹配：" + table);
    }

    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        if (rows.size() != 1)
            throw new IllegalStateException("Unique ETF native metadata/receipt required");
        return rows.getFirst();
    }

    private static long counter(Map<String, Object> row, String name) {
        if (!(row.get(name) instanceof Number value) || value.longValue() < 0)
            throw new IllegalStateException("Required ETF revision counter unavailable: " + name);
        return value.longValue();
    }

    private static String text(Map<String, Object> row, String name) {
        String value = Objects.toString(row.get(name), "");
        if (value.isBlank())
            throw new IllegalStateException("Required ETF identity missing: " + name);
        return value;
    }

    private static boolean nonempty(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
