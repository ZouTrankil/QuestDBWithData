package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Python financial-indicator product: full prior history, bounded by report and announcement dates. */
public final class QuestDbFinancialIndicators {
    public static final List<String> COLUMNS =
            List.of(
                    "grossprofit_margin",
                    "roic",
                    "ocf_to_or",
                    "tr_yoy",
                    "q_profit_yoy",
                    "q_op_yoy",
                    "roe",
                    "roa",
                    "debt_to_assets");

    public record Snapshot(
            Map<String, List<PointInTimeFactorJoin.VectorObservation>> securities,
            List<Map<String, Object>> sourceState,
            long rows) {
        public Snapshot {
            var copy = new LinkedHashMap<String, List<PointInTimeFactorJoin.VectorObservation>>();
            securities.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            securities = Collections.unmodifiableMap(copy);
            sourceState = List.copyOf(sourceState);
        }

        public Map<String, double[]> align(String symbol, LocalDate[] signalDates) {
            var values =
                    PointInTimeFactorJoin.joinColumns(
                            signalDates,
                            securities.getOrDefault(symbol, List.of()),
                            COLUMNS.size());
            var columns = new LinkedHashMap<String, double[]>();
            for (int c = 0; c < COLUMNS.size(); c++) columns.put(COLUMNS.get(c), values[c]);
            return Collections.unmodifiableMap(columns);
        }
    }

    private final JdbcTemplate jdbc;

    public QuestDbFinancialIndicators(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(60);
    }

    public Snapshot read(LocalDate through, int maxRows) {
        Objects.requireNonNull(through);
        if (maxRows < 1 || maxRows > 1_000_000)
            throw new IllegalArgumentException("Expected 1..1000000 source row budget");
        var before = state();
        var securities = new LinkedHashMap<String, List<PointInTimeFactorJoin.VectorObservation>>();
        long[] count = {0};
        String sql =
                "SELECT ts_code,ann_date,end_date,"
                        + String.join(",", COLUMNS)
                        + " FROM fina_indicator WHERE ann_date<'"
                        + through.plusDays(1)
                        + "' AND end_date<='"
                        + through.format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "' ORDER BY end_date,ts_code,ann_date LIMIT "
                        + (maxRows + 1);
        jdbc.query(
                sql,
                (RowCallbackHandler)
                        rs -> {
                            if (++count[0] > maxRows)
                                throw new IllegalStateException(
                                        "Financial source exceeds row budget");
                            String symbol = rs.getString(1);
                            var announcement = rs.getTimestamp(2);
                            if (symbol == null || symbol.isBlank() || announcement == null)
                                throw new IllegalStateException(
                                        "Missing financial entity/announcement");
                            double[] values = new double[COLUMNS.size()];
                            for (int c = 0; c < values.length; c++) {
                                values[c] = rs.getDouble(c + 4);
                                if (rs.wasNull() || !Double.isFinite(values[c]))
                                    values[c] = Double.NaN;
                            }
                            // Preserve the Python product's identities and stable report-date order
                            // before the as-of sort.
                            securities
                                    .computeIfAbsent(symbol, ignored -> new ArrayList<>())
                                    .add(
                                            new PointInTimeFactorJoin.VectorObservation(
                                                    announcement.toLocalDateTime().toLocalDate(),
                                                    values));
                        });
        if (!before.equals(state()))
            throw new IllegalStateException("Financial source changed during read; retry");
        return new Snapshot(securities, before, count[0]);
    }

    private List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName,table_txn,wal_txn,walEnabled,table_suspended"
                                + " FROM tables() WHERE table_name='fina_indicator'");
        if (rows.size() != 1) throw new IllegalStateException("Missing fina_indicator");
        var row = rows.getFirst();
        if (Boolean.TRUE.equals(row.get("table_suspended"))
                || Boolean.TRUE.equals(row.get("walEnabled"))
                        && !QuestDbWriteChecks.walSettled(jdbc, "fina_indicator"))
            throw new IllegalStateException("Financial source WAL not settled");
        return rows;
    }
}
