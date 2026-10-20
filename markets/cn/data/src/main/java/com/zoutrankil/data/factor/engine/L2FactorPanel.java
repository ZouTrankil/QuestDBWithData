package com.zoutrankil.data.factor.engine;

import com.zoutrankil.data.factor.domain.FactorDefinition;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared L2 input window. History is counted in observed rows, not calendar days. */
public final class L2FactorPanel {
    private L2FactorPanel() {}

    public record Row(LocalDate date, double[] values) {}

    public record Section(String symbol, LocalDate[] dates, Map<String, double[]> factors) {}

    /** Input must arrive in increasing date order for each symbol; retain only three history rows. */
    public static final class Builder {
        private final LocalDate from, through;
        private final List<String> columns;
        private final Map<String, ArrayDeque<Row>> rows = new TreeMap<>();

        public Builder(LocalDate from, LocalDate through, List<String> columns) {
            if (through.isBefore(from)
                    || columns.isEmpty()
                    || new HashSet<>(columns).size() != columns.size())
                throw new IllegalArgumentException("Valid L2 window and unique columns required");
            this.from = from;
            this.through = through;
            this.columns = List.copyOf(columns);
        }

        public void add(String symbol, LocalDate date, double[] values) {
            if (symbol == null
                    || symbol.isBlank()
                    || date == null
                    || date.isAfter(through)
                    || values.length != columns.size())
                throw new IllegalArgumentException("Invalid L2 row");
            var series = rows.computeIfAbsent(symbol, ignored -> new ArrayDeque<>());
            if (!series.isEmpty() && !date.isAfter(series.getLast().date()))
                throw new IllegalArgumentException(
                        "Duplicate or unordered L2 date: " + symbol + "/" + date);
            series.addLast(new Row(date, values.clone()));
            if (date.isBefore(from)) while (series.size() > 3) series.removeFirst();
        }

        public long retainedRows() {
            return rows.values().stream().mapToLong(Collection::size).sum();
        }

        public List<Section> compute(List<FactorDefinition> definitions) {
            if (definitions.isEmpty()
                    || definitions.stream().map(FactorDefinition::factorId).distinct().count()
                            != definitions.size())
                throw new IllegalArgumentException("Unique L2 factors required");
            for (var definition : definitions)
                if (!definition.enabled() || !definition.sourceGroup().equals("l2_microstructure"))
                    throw new IllegalArgumentException("Enabled L2 factor required");
            var result = new ArrayList<Section>();
            var engine = new FactorFormulaEngine();
            for (var entry : rows.entrySet()) {
                var records = List.copyOf(entry.getValue());
                int first = 0;
                while (first < records.size() && records.get(first).date().isBefore(from)) first++;
                if (first == records.size()) continue;
                var shared = new LinkedHashMap<String, double[]>();
                for (int c = 0; c < columns.size(); c++) {
                    double[] values = new double[records.size()];
                    for (int i = 0; i < values.length; i++) values[i] = records.get(i).values()[c];
                    shared.put(columns.get(c), values);
                }
                var outputs = new LinkedHashMap<String, double[]>();
                for (var definition : definitions) {
                    var values = engine.compute(definition, shared);
                    outputs.put(
                            definition.factorId(),
                            Arrays.copyOfRange(values, first, values.length));
                }
                LocalDate[] dates = new LocalDate[records.size() - first];
                for (int i = 0; i < dates.length; i++) dates[i] = records.get(i + first).date();
                result.add(
                        new Section(entry.getKey(), dates, Collections.unmodifiableMap(outputs)));
            }
            return List.copyOf(result);
        }
    }
}
