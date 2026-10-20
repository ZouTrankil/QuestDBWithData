package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.PointInTimeStockExposures;
import com.zoutrankil.data.factor.engine.PointInTimeStockPools;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded, version-checked historical pool and industry inputs shared by the whole batch. */
public final class QuestDbStockMembership {
    public record Snapshot(
            PointInTimeStockPools.Index pools,
            PointInTimeStockExposures.Index exposures,
            List<Map<String, Object>> sourceState,
            long indexRows,
            long industryRows) {}

    private final JdbcTemplate jdbc;

    public QuestDbStockMembership(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(60);
    }

    public Snapshot read(LocalDate through, int maxRows) {
        if (maxRows < 1 || maxRows > 1_000_000)
            throw new IllegalArgumentException("Invalid membership row budget");
        var before = state();
        var indices = new ArrayList<PointInTimeStockPools.IndexMember>();
        jdbc.query(
                "SELECT index_code,trade_date,con_code,exchange FROM index_weight WHERE trade_date<'"
                        + through.plusDays(1)
                        + "' ORDER BY index_code,trade_date,con_code LIMIT "
                        + (maxRows + 1),
                (RowCallbackHandler)
                        rs -> {
                            if (indices.size() == maxRows)
                                throw new IllegalStateException(
                                        "Index membership exceeds row budget");
                            String code = rs.getString(1),
                                    symbol = stockCode(rs.getString(3), rs.getString(4));
                            var timestamp = rs.getTimestamp(2);
                            if (code == null || timestamp == null || symbol.isEmpty())
                                throw new IllegalStateException(
                                        "Missing index membership identity");
                            indices.add(
                                    new PointInTimeStockPools.IndexMember(
                                            code.strip().toUpperCase(Locale.ROOT).split("\\.")[0],
                                            timestamp.toLocalDateTime().toLocalDate(),
                                            symbol));
                        });
        var industries = new ArrayList<PointInTimeStockExposures.Membership>();
        jdbc.query(
                "SELECT ts_code,in_date,out_date,level,l1_name,l2_name FROM index_member LIMIT "
                        + (maxRows + 1),
                (RowCallbackHandler)
                        rs -> {
                            if (industries.size() == maxRows)
                                throw new IllegalStateException(
                                        "Industry membership exceeds row budget");
                            String symbol = rs.getString(1);
                            if (symbol == null || symbol.isBlank())
                                throw new IllegalStateException("Missing industry entity");
                            industries.add(
                                    new PointInTimeStockExposures.Membership(
                                            symbol,
                                            date(rs.getString(2)),
                                            date(rs.getString(3)),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getString(6)));
                        });
        if (!before.equals(state()))
            throw new IllegalStateException("Membership changed during read; retry");
        return new Snapshot(
                new PointInTimeStockPools.Index(indices),
                new PointInTimeStockExposures.Index(industries),
                List.copyOf(before),
                indices.size(),
                industries.size());
    }

    private List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended"
                                + " FROM tables() WHERE table_name IN ('index_weight','index_member') ORDER BY table_name");
        if (rows.size() != 2) throw new IllegalStateException("Missing stock membership tables");
        for (var row : rows)
            if (Boolean.TRUE.equals(row.get("table_suspended"))
                    || Boolean.TRUE.equals(row.get("walEnabled"))
                            && !QuestDbWriteChecks.walSettled(jdbc, (String) row.get("table_name")))
                throw new IllegalStateException("Membership WAL not settled");
        return rows;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.length() == 8
                    ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                    : LocalDate.parse(value.substring(0, 10));
        }
        // Python uses to_datetime(errors="coerce"): legacy null strings become open interval
        // bounds.
        catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String stockCode(String value, String exchange) {
        if (value == null) return "";
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.contains(".")) return code;
        String venue = exchange == null ? "" : exchange.toUpperCase(Locale.ROOT);
        String suffix =
                venue.contains("SH") || venue.contains("上海")
                        ? "SH"
                        : venue.contains("BJ")
                                        || venue.contains("北京")
                                        || code.startsWith("4")
                                        || code.startsWith("8")
                                        || code.startsWith("9")
                                ? "BJ"
                                : venue.contains("SZ") || venue.contains("深圳")
                                        ? "SZ"
                                        : code.startsWith("6") ? "SH" : "SZ";
        return code + "." + suffix;
    }
}
