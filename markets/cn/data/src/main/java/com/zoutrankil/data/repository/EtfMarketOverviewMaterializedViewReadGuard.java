package com.zoutrankil.data.repository;

import com.zoutrankil.data.maintenance.storage.MaterializedViewCheckpoints;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        String share = snapshot(jdbc, "etf_share"), daily = snapshot(jdbc, "etf_daily");
        String pin = sha(normalized(SQL) + "|" + share + "|" + daily);
        var view = view(jdbc);
        requireDefinition(view);
        if (!"valid".equals(view.get("view_status"))
                || nonempty(view.get("invalidation_reason"))
                || counter(view, "refresh_base_table_txn") != counter(view, "base_table_txn"))
            throw new IllegalStateException(
                    "ETF native MV definition or checkpoint is not current");
        String output = snapshot(jdbc, OUTPUT);
        String checkpoint = checkpointName(jdbc);
        var receipt =
                new MaterializedViewCheckpoints(ledger)
                        .find(checkpoint)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ETF local refresh checkpoint missing; run refresh-etf-overview"));
        if (!definitionHash().equals(receipt.definitionHash())
                || !share.equals(receipt.leftVersion())
                || !daily.equals(receipt.rightVersion())
                || !output.equals(receipt.outputVersion())
                || !Objects.toString(view.get("last_refresh_finish_timestamp"), "")
                        .equals(receipt.refreshFinished()))
            throw new IllegalStateException(
                    "ETF JOIN inputs/output changed after verified refresh");
        if (!share.equals(snapshot(jdbc, "etf_share"))
                || !daily.equals(snapshot(jdbc, "etf_daily"))
                || !output.equals(snapshot(jdbc, OUTPUT)))
            throw new IllegalStateException(
                    "ETF source/output revisions changed during verification");
        return "etf-native:" + checkpoint + "|" + pin + "|" + output;
    }

    public static Map<String, Object> view(JdbcTemplate jdbc) {
        return one(
                jdbc.queryForList(
                        "SELECT * FROM materialized_views() WHERE view_name='" + OUTPUT + "'"));
    }

    public static void requireDefinition(Map<String, Object> view) {
        if (!"etf_share".equals(view.get("base_table_name"))
                || !Set.of("manual", "immediate")
                        .contains(Objects.toString(view.get("refresh_type"), ""))
                || !normalized(SQL).equals(normalized(view.get("view_sql"))))
            throw new IllegalStateException(
                    "Unexpected ETF native MV definition or refresh policy");
    }

    public static String definitionHash() {
        return sha(normalized(SQL));
    }

    public static String checkpointName(JdbcTemplate jdbc) {
        String identity = jdbc.queryForObject("SELECT current_data_id()", String.class);
        if (identity == null || identity.isBlank())
            throw new IllegalStateException("QuestDB database identity unavailable");
        return OUTPUT + "@" + identity;
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
        if (!Set.of("etf_share", "etf_daily", OUTPUT).contains(table))
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
