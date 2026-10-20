package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Per-security statement pipeline. Each source is sorted once and advanced by announcement date. */
public final class StructuralFinancialFeatures {
    public static final String ELIGIBLE_COLUMN = "__structural_financial_eligible";

    private StructuralFinancialFeatures() {}

    public record Statement(LocalDate period, LocalDate announced, Map<String, Double> values) {
        public Statement {
            Objects.requireNonNull(period);
            Objects.requireNonNull(announced);
            values = Map.copyOf(values);
        }
    }

    public static final List<String> COLUMNS =
            List.of(
                    "value_cfp",
                    "quality_gross_profit_assets",
                    "quality_operating_profitability",
                    "quality_accruals",
                    "quality_cashflow_quality",
                    "investment_asset_growth",
                    "investment_capex_assets",
                    "investment_noa_growth");

    private static final class Cursor {
        final List<Statement> rows;
        final NavigableMap<LocalDate, Statement> known = new TreeMap<>();
        int index;

        Cursor(List<Statement> source) {
            rows = source.stream().sorted(Comparator.comparing(Statement::announced)).toList();
        }

        boolean advance(LocalDate date) {
            int before = index;
            while (index < rows.size() && !rows.get(index).announced().isAfter(date)) {
                var row = rows.get(index++);
                known.put(row.period(), row);
            }
            return index != before;
        }

        double ttm(LocalDate period, String field) {
            double current = value(known.get(period), field);
            if (period.getMonthValue() == 12) return current;
            return current
                    + value(known.get(LocalDate.of(period.getYear() - 1, 12, 31)), field)
                    - value(known.get(period.minusYears(1)), field);
        }
    }

    public static Map<String, double[]> compute(
            LocalDate[] dates,
            double[] totalMarketValue,
            List<Statement> income,
            List<Statement> balance,
            List<Statement> cashflow) {
        if (dates.length != totalMarketValue.length)
            throw new IllegalArgumentException("Market dates and values differ");
        for (int i = 0; i < dates.length; i++)
            if (dates[i] == null || i > 0 && !dates[i].isAfter(dates[i - 1]))
                throw new IllegalArgumentException("Strictly ordered unique signal dates required");
        var inc = new Cursor(income);
        var bal = new Cursor(balance);
        var cash = new Cursor(cashflow);
        var output = new LinkedHashMap<String, double[]>();
        double[][] columns = new double[COLUMNS.size()][];
        for (int c = 0; c < COLUMNS.size(); c++) {
            columns[c] = new double[dates.length];
            Arrays.fill(columns[c], Double.NaN);
            output.put(COLUMNS.get(c), columns[c]);
        }
        var eligible = new double[dates.length];
        output.put(ELIGIBLE_COLUMN, eligible);
        double ocf = Double.NaN;
        double[] cached = new double[COLUMNS.size()];
        Arrays.fill(cached, Double.NaN);
        for (int i = 0; i < dates.length; i++) {
            // Bitwise OR deliberately advances every source, even when the first one changed.
            boolean changed =
                    inc.advance(dates[i]) | bal.advance(dates[i]) | cash.advance(dates[i]);
            if (bal.known.isEmpty()) continue;
            eligible[i] = 1;
            if (changed) {
                var current = bal.known.lastEntry().getValue();
                var period = current.period();
                var prior = bal.known.get(period.minusYears(1));
                double assets = value(current, "total_assets"),
                        priorAssets = value(prior, "total_assets");
                double profit = inc.ttm(period, "n_income_attr_p");
                ocf = cash.ttm(period, "n_cashflow_act");
                cached[1] =
                        divide(inc.ttm(period, "revenue") - inc.ttm(period, "oper_cost"), assets);
                cached[2] = divide(inc.ttm(period, "operate_profit"), assets);
                cached[3] = divide(profit - ocf, assets);
                cached[4] = divide(ocf, Math.abs(profit));
                cached[5] = divide(assets, priorAssets) - 1;
                cached[6] = divide(cash.ttm(period, "c_pay_acq_const_fiolta"), assets);
                cached[7] =
                        divide(
                                netOperatingAssets(current) - netOperatingAssets(prior),
                                Math.abs(priorAssets));
            }
            cached[0] = divide(ocf, totalMarketValue[i] * 10000);
            for (int c = 0; c < COLUMNS.size(); c++)
                columns[c][i] = Double.isFinite(cached[c]) ? cached[c] : Double.NaN;
        }
        return Collections.unmodifiableMap(output);
    }

    private static double value(Statement row, String field) {
        return row == null ? Double.NaN : row.values().getOrDefault(field, Double.NaN);
    }

    private static double zeroIfMissing(double value) {
        return Double.isNaN(value) ? 0 : value;
    }

    private static double netOperatingAssets(Statement row) {
        double debt = 0;
        for (String field : List.of("st_borr", "non_cur_liab_due_1y", "lt_borr", "bond_payable"))
            debt += zeroIfMissing(value(row, field));
        return value(row, "total_assets")
                - value(row, "money_cap")
                - zeroIfMissing(value(row, "trad_asset"))
                - value(row, "total_liab")
                + debt;
    }

    private static double divide(double a, double b) {
        return Math.abs(b) > 1e-12 ? a / b : Double.NaN;
    }
}
