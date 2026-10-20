package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Normalize each complete stock cross-section once, then reuse it for every ETF basket. */
public final class EtfCsiPipeline {
    private EtfCsiPipeline() {}

    /** Borrowed arrays: temporal windows and PIT fields must already be aligned to dates. */
    public record Security(String symbol, LocalDate[] dates, Map<String, double[]> columns) {}

    private record Position(int security, int row) {}

    public static List<EtfCsiAggregation.Row> compute(
            List<Security> panel, EtfCsiHoldings holdings, LocalDate from, LocalDate through) {
        return compute(panel, holdings, from, through, null);
    }

    public static List<EtfCsiAggregation.Row> compute(
            List<Security> panel,
            EtfCsiHoldings holdings,
            LocalDate from,
            LocalDate through,
            Set<LocalDate> outputDates) {
        Objects.requireNonNull(holdings);
        Objects.requireNonNull(from);
        Objects.requireNonNull(through);
        if (from.isAfter(through))
            throw new IllegalArgumentException("Ordered CSI output interval required");
        var dates = new TreeMap<LocalDate, List<Position>>();
        var symbols = new HashSet<String>();
        var fields = new TreeSet<String>();
        for (int security = 0; security < panel.size(); security++) {
            var input = panel.get(security);
            if (input.symbol() == null || input.symbol().isBlank() || !symbols.add(input.symbol()))
                throw new IllegalArgumentException("Unique CSI stock symbols required");
            fields.addAll(input.columns().keySet());
            for (var column : input.columns().values())
                if (column.length != input.dates().length)
                    throw new IllegalArgumentException("Aligned CSI columns required");
            for (int row = 0; row < input.dates().length; row++) {
                var date = Objects.requireNonNull(input.dates()[row]);
                if (row > 0 && !date.isAfter(input.dates()[row - 1]))
                    throw new IllegalArgumentException("Increasing unique CSI dates required");
                if (!date.isBefore(from)
                        && !date.isAfter(through)
                        && (outputDates == null || outputDates.contains(date)))
                    dates.computeIfAbsent(date, ignored -> new ArrayList<>())
                            .add(new Position(security, row));
            }
        }
        var result = new ArrayList<EtfCsiAggregation.Row>();
        for (var entry : dates.entrySet()) {
            var positions = entry.getValue();
            int size = positions.size();
            var section = new LinkedHashMap<String, double[]>();
            for (String field : fields) {
                double[] values = new double[size];
                Arrays.fill(values, Double.NaN);
                for (int i = 0; i < size; i++) {
                    var position = positions.get(i);
                    var column = panel.get(position.security()).columns().get(field);
                    if (column != null) values[i] = column[position.row()];
                }
                section.put(field, values);
            }
            // Filtering stocks to individual ETF memberships before normalization changes the
            // factor.
            var styles = EtfCsiStyles.compute(size, section);
            var stocks = new ArrayList<EtfCsiAggregation.Stock>(size);
            for (int i = 0; i < size; i++) {
                var values = new LinkedHashMap<String, Double>();
                for (var factor : styles.entrySet())
                    values.put(factor.getKey(), factor.getValue()[i]);
                stocks.add(
                        new EtfCsiAggregation.Stock(
                                panel.get(positions.get(i).security()).symbol(), values));
            }
            result.addAll(
                    EtfCsiAggregation.compute(entry.getKey(), stocks, holdings.at(entry.getKey())));
        }
        return List.copyOf(result);
    }
}
