package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.EtfHistoryWindow;
import com.zoutrankil.data.factor.engine.EtfMarketInputs;
import com.zoutrankil.data.factor.engine.EtfSharedPanel;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded ETF source snapshot. Current reference status is not historical PIT evidence. */
public final class QuestDbEtfInputs {
    public record Snapshot(
            EtfMarketInputs.Prepared market,
            Map<EtfSharedPanel.Key, Double> shares,
            long marketRows,
            long shareRows,
            List<Map<String, Object>> sourceState,
            boolean referencePointInTime,
            boolean sourceComplete) {}

    private final JdbcTemplate jdbc;

    public QuestDbEtfInputs(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(90);
    }

    public Snapshot read(LocalDate historyFrom, LocalDate through, int maxRows) {
        EtfHistoryWindow.requireBounded(historyFrom, through);
        if (historyFrom == null
                || through == null
                || historyFrom.isAfter(through)
                || maxRows < 1
                || maxRows > 1_000_000)
            throw new IllegalArgumentException("ETF window and row budget required");
        var before = state();
        var references = new ArrayList<EtfMarketInputs.Reference>();
        int[] referenceRows = {0};
        jdbc.query(
                "SELECT ts_code,name,status,list_date FROM etf_basic ORDER BY ts_code,timestamp,update_time LIMIT 20001",
                (RowCallbackHandler)
                        rs -> {
                            if (++referenceRows[0] > 20000)
                                throw new IllegalStateException("ETF reference exceeds row budget");
                            var listed = parseListingDate(rs.getString(4));
                            if (listed != null && !listed.isAfter(through))
                                references.add(
                                        new EtfMarketInputs.Reference(
                                                rs.getString(1), rs.getString(2), rs.getString(3)));
                        });
        // A missing/fully unusable reference must not silently admit the entire market in a live
        // reader.
        if (references.isEmpty())
            throw new IllegalStateException("No dated ETF reference available as of output date");
        var bars = new ArrayList<EtfMarketInputs.Bar>();
        long[] counts = {0, 0};
        String interval =
                "timestamp>='" + historyFrom + "' AND timestamp<'" + through.plusDays(1) + "'";
        jdbc.query(
                "SELECT ts_code,timestamp,close,amount FROM etf_daily WHERE "
                        + interval
                        + " ORDER BY ts_code,timestamp LIMIT "
                        + (maxRows + 1),
                (RowCallbackHandler)
                        rs -> {
                            if (++counts[0] > maxRows)
                                throw new IllegalStateException("ETF market exceeds row budget");
                            var stamp = rs.getTimestamp(2);
                            if (stamp == null) throw new IllegalStateException("Missing ETF date");
                            bars.add(
                                    new EtfMarketInputs.Bar(
                                            rs.getString(1),
                                            stamp.toLocalDateTime().toLocalDate(),
                                            number(rs, 3),
                                            number(rs, 4)));
                        });
        var shares = new LinkedHashMap<EtfSharedPanel.Key, Double>();
        jdbc.query(
                "SELECT ts_code,timestamp,fd_share FROM etf_share WHERE "
                        + interval
                        + " ORDER BY ts_code,timestamp LIMIT "
                        + (maxRows + 1),
                (RowCallbackHandler)
                        rs -> {
                            if (++counts[1] > maxRows)
                                throw new IllegalStateException("ETF shares exceed row budget");
                            var stamp = rs.getTimestamp(2);
                            if (stamp == null)
                                throw new IllegalStateException("Missing ETF share date");
                            var key =
                                    new EtfSharedPanel.Key(
                                            rs.getString(1), stamp.toLocalDateTime().toLocalDate());
                            // Python's merge expands duplicates. Reject instead of silently
                            // changing rolling windows.
                            if (shares.putIfAbsent(key, number(rs, 3)) != null)
                                throw new IllegalStateException("Duplicate ETF share date: " + key);
                        });
        if (!before.equals(state()))
            throw new IllegalStateException("ETF source changed during read");
        var market = EtfMarketInputs.prepare(bars, references);
        if (market.inputs().isEmpty())
            throw new IllegalStateException("No active ETF market rows in window");
        return new Snapshot(
                market,
                Collections.unmodifiableMap(shares),
                counts[0],
                counts[1],
                before,
                false,
                false);
    }

    static LocalDate parseListingDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(
                    value,
                    value.matches("[0-9]{8}")
                            ? DateTimeFormatter.BASIC_ISO_DATE
                            : DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("Invalid ETF listing date", invalid);
        }
    }

    private static double number(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    public void verifyUnchanged(Snapshot snapshot) {
        if (!snapshot.sourceState().equals(state()))
            throw new IllegalStateException("ETF source changed after snapshot");
    }

    private List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables()"
                                + " WHERE table_name IN ('etf_daily','etf_basic','etf_share') ORDER BY table_name");
        if (rows.size() != 3) throw new IllegalStateException("ETF source tables missing");
        for (var row : rows)
            if (!QuestDbWriteChecks.walSettled(jdbc, (String) row.get("table_name")))
                throw new IllegalStateException("ETF source WAL not settled");
        return rows;
    }
}
