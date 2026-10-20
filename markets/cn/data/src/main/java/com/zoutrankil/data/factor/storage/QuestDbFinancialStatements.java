package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.StructuralFinancialFeatures;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One bounded snapshot of the three statement sources reused across structural factors. */
public final class QuestDbFinancialStatements {
    private static final Map<String, List<String>> FIELDS =
            Map.of(
                    "income", List.of("revenue", "oper_cost", "operate_profit", "n_income_attr_p"),
                    "cashflow", List.of("n_cashflow_act", "c_pay_acq_const_fiolta"),
                    "balance",
                            List.of(
                                    "total_assets",
                                    "money_cap",
                                    "trad_asset",
                                    "st_borr",
                                    "non_cur_liab_due_1y",
                                    "lt_borr",
                                    "bond_payable",
                                    "total_liab"));

    public record Snapshot(
            Map<String, Map<String, List<StructuralFinancialFeatures.Statement>>> tables,
            List<Map<String, Object>> sourceState,
            long rows) {
        public Map<String, double[]> compute(
                String symbol, LocalDate[] dates, double[] marketValue) {
            return StructuralFinancialFeatures.compute(
                    dates,
                    marketValue,
                    tables.get("income").getOrDefault(symbol, List.of()),
                    tables.get("balance").getOrDefault(symbol, List.of()),
                    tables.get("cashflow").getOrDefault(symbol, List.of()));
        }
    }

    private final JdbcTemplate jdbc;

    public QuestDbFinancialStatements(JdbcTemplate jdbc) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(60);
    }

    public Snapshot read(LocalDate through, int maxRowsPerTable) {
        Objects.requireNonNull(through);
        if (maxRowsPerTable < 1 || maxRowsPerTable > 1_000_000)
            throw new IllegalArgumentException("Expected bounded statement row budget");
        var before = state();
        var tables =
                new LinkedHashMap<
                        String, Map<String, List<StructuralFinancialFeatures.Statement>>>();
        long total = 0;
        for (String table : List.of("income", "balance", "cashflow")) {
            var columns = FIELDS.get(table);
            var securities =
                    new LinkedHashMap<String, List<StructuralFinancialFeatures.Statement>>();
            long[] count = {0};
            String sql =
                    "SELECT ts_code,end_date,ann_date,"
                            + String.join(",", columns)
                            + " FROM "
                            + table
                            + " WHERE ann_date<'"
                            + through.plusDays(1)
                            + "' ORDER BY ann_date,ts_code,end_date LIMIT "
                            + (maxRowsPerTable + 1);
            jdbc.query(
                    sql,
                    (RowCallbackHandler)
                            rs -> {
                                if (++count[0] > maxRowsPerTable)
                                    throw new IllegalStateException(
                                            "Statement row budget exceeded: " + table);
                                String symbol = rs.getString(1), periodText = rs.getString(2);
                                var announced = rs.getTimestamp(3);
                                if (symbol == null || periodText == null || announced == null)
                                    throw new IllegalStateException("Missing statement identity");
                                LocalDate period;
                                try {
                                    period =
                                            LocalDate.parse(
                                                    periodText, DateTimeFormatter.BASIC_ISO_DATE);
                                } catch (DateTimeParseException error) {
                                    throw new IllegalStateException(
                                            "Invalid statement period", error);
                                }
                                var values = new LinkedHashMap<String, Double>();
                                for (int c = 0; c < columns.size(); c++) {
                                    double value = rs.getDouble(c + 4);
                                    if (rs.wasNull()) value = Double.NaN;
                                    values.put(columns.get(c), value);
                                }
                                securities
                                        .computeIfAbsent(symbol, ignored -> new ArrayList<>())
                                        .add(
                                                new StructuralFinancialFeatures.Statement(
                                                        period,
                                                        announced.toLocalDateTime().toLocalDate(),
                                                        values));
                            });
            var frozen = new LinkedHashMap<String, List<StructuralFinancialFeatures.Statement>>();
            securities.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            tables.put(table, Collections.unmodifiableMap(frozen));
            total += count[0];
        }
        if (!before.equals(state()))
            throw new IllegalStateException("Statements changed during read; retry");
        return new Snapshot(Collections.unmodifiableMap(tables), List.copyOf(before), total);
    }

    private List<Map<String, Object>> state() {
        var rows =
                jdbc.queryForList(
                        "SELECT id,table_name,directoryName,table_txn,wal_txn,walEnabled,table_suspended"
                                + " FROM tables() WHERE table_name IN ('income','balance','cashflow') ORDER BY table_name");
        if (rows.size() != 3) throw new IllegalStateException("Missing statement source");
        for (var row : rows)
            if (Boolean.TRUE.equals(row.get("table_suspended"))
                    || Boolean.TRUE.equals(row.get("walEnabled"))
                            && !QuestDbWriteChecks.walSettled(jdbc, (String) row.get("table_name")))
                throw new IllegalStateException("Statement source WAL not settled");
        return rows;
    }
}
