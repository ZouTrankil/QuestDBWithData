package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Narrow CSI financial projections; latest announcements retain null fields, matching merge_asof. */
public final class QuestDbCsiFinancials {
    private static final Map<String, List<String>> FIELDS =
            Map.of(
                    "fina_indicator",
                            List.of(
                                    "roe",
                                    "grossprofit_margin",
                                    "ocf_to_or",
                                    "debt_to_assets",
                                    "q_gr_yoy",
                                    "q_profit_yoy"),
                    "cashflow", List.of("n_cashflow_act"));

    public record Snapshot(
            Map<String, Map<String, List<PointInTimeFactorJoin.VectorObservation>>> tables,
            long rows,
            List<Map<String, Object>> sourceState) {
        public Map<String, double[]> align(String symbol, LocalDate[] dates) {
            var result = new LinkedHashMap<String, double[]>();
            for (String table : List.of("fina_indicator", "cashflow")) {
                var fields = FIELDS.get(table);
                var columns =
                        PointInTimeFactorJoin.joinRawColumns(
                                dates,
                                tables.get(table).getOrDefault(symbol, List.of()),
                                fields.size());
                for (int c = 0; c < fields.size(); c++) result.put(fields.get(c), columns[c]);
            }
            return Collections.unmodifiableMap(result);
        }
    }

    private final JdbcTemplate jdbc;

    public QuestDbCsiFinancials(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(90);
    }

    public Snapshot read(LocalDate through, int maxRowsPerTable) {
        return read(LocalDate.MIN, through, maxRowsPerTable);
    }

    /** Retain the last pre-window announcement vector, including nulls, plus all window events. */
    public Snapshot read(LocalDate from, LocalDate through, int maxRowsPerTable) {
        Objects.requireNonNull(through);
        if (from == null || from.isAfter(through))
            throw new IllegalArgumentException("Ordered CSI financial window required");
        if (maxRowsPerTable < 1 || maxRowsPerTable > 1_000_000)
            throw new IllegalArgumentException("Bounded CSI financial row budget required");
        var before = state();
        var tables =
                new LinkedHashMap<
                        String, Map<String, List<PointInTimeFactorJoin.VectorObservation>>>();
        long total = 0;
        for (String table : List.of("fina_indicator", "cashflow")) {
            var fields = FIELDS.get(table);
            var rows = new LinkedHashMap<String, List<PointInTimeFactorJoin.VectorObservation>>();
            long[] count = {0};
            var prior = new HashMap<String, PointInTimeFactorJoin.VectorObservation>();
            // Match Python dataset contracts: indicators are bounded by report end and
            // announcement,
            // cashflow by announcement only. Date cutoffs are inclusive at midnight.
            boolean indicators = table.equals("fina_indicator");
            String end = through.format(DateTimeFormatter.BASIC_ISO_DATE);
            String reportFilter = indicators ? " AND end_date<='" + end + "'" : "";
            String order = indicators ? "end_date,ts_code,ann_date" : "ann_date,ts_code,end_date";
            String sql =
                    "SELECT ts_code,ann_date,"
                            + String.join(",", fields)
                            + " FROM "
                            + table
                            + " WHERE ann_date<='"
                            + through
                            + "'"
                            + reportFilter
                            + " ORDER BY "
                            + order
                            + " LIMIT "
                            + (maxRowsPerTable + 1);
            if (!from.equals(LocalDate.MIN)) {
                sql =
                        "WITH prior AS (SELECT ts_code,max(date_trunc('day',ann_date)) AS prior_day FROM "
                                + table
                                + " WHERE ann_date<'"
                                + from
                                + "'"
                                + reportFilter
                                + " GROUP BY ts_code)"
                                + " SELECT p.ts_code,p.ann_date,p."
                                + String.join(",p.", fields)
                                + " FROM "
                                + table
                                + " p"
                                + " LEFT JOIN prior b ON p.ts_code=b.ts_code WHERE p.ann_date<='"
                                + through
                                + "'"
                                + (indicators ? " AND p.end_date<='" + end + "'" : "")
                                + " AND (p.ann_date>='"
                                + from
                                + "' OR date_trunc('day',p.ann_date)=b.prior_day)"
                                + " ORDER BY p."
                                + order.replace(",", ",p.")
                                + " LIMIT "
                                + (maxRowsPerTable + 1);
            }
            jdbc.query(
                    sql,
                    (RowCallbackHandler)
                            rs -> {
                                if (++count[0] > maxRowsPerTable)
                                    throw new IllegalStateException(
                                            "CSI financial source exceeds row budget");
                                var stamp = rs.getTimestamp(2);
                                String symbol = rs.getString(1);
                                if (stamp == null || symbol == null || symbol.isBlank())
                                    throw new IllegalStateException(
                                            "Missing CSI financial identity");
                                double[] values = new double[fields.size()];
                                for (int i = 0; i < values.length; i++) {
                                    values[i] = rs.getDouble(i + 3);
                                    if (rs.wasNull()) values[i] = Double.NaN;
                                }
                                var event =
                                        new PointInTimeFactorJoin.VectorObservation(
                                                stamp.toLocalDateTime().toLocalDate(), values);
                                if (event.availableDate().isBefore(from)) {
                                    var previous = prior.get(symbol);
                                    if (previous == null
                                            || !event.availableDate()
                                                    .isBefore(previous.availableDate()))
                                        prior.put(symbol, event);
                                } else
                                    rows.computeIfAbsent(symbol, ignored -> new ArrayList<>())
                                            .add(event);
                            });
            prior.forEach(
                    (symbol, event) ->
                            rows.computeIfAbsent(symbol, ignored -> new ArrayList<>())
                                    .addFirst(event));
            rows.replaceAll((symbol, events) -> List.copyOf(events));
            tables.put(table, Collections.unmodifiableMap(rows));
            total += count[0];
        }
        if (!before.equals(state()))
            throw new IllegalStateException("CSI financial source changed during read");
        return new Snapshot(Collections.unmodifiableMap(tables), total, before);
    }

    public void verifyUnchanged(Snapshot snapshot) {
        if (!snapshot.sourceState().equals(state()))
            throw new IllegalStateException("CSI financial source changed after read");
    }

    private List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended FROM tables() WHERE table_name IN ('fina_indicator','cashflow') ORDER BY table_name");
        if (rows.size() != 2) throw new IllegalStateException("CSI financial source missing");
        for (var row : rows)
            if (Boolean.TRUE.equals(row.get("table_suspended"))
                    || Boolean.TRUE.equals(row.get("walEnabled"))
                            && !QuestDbWriteChecks.walSettled(jdbc, (String) row.get("table_name")))
                throw new IllegalStateException("CSI financial source unsettled");
        return rows;
    }
}
