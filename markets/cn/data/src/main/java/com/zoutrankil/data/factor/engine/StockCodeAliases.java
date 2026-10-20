package com.zoutrankil.data.factor.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned Python input-contract rules, applied independently to each source before joining. */
public final class StockCodeAliases {
    private record Rule(String legacy, String canonical, LocalDate switchDate) {}

    public record Row(String symbol, LocalDate date, double[] values) {
        public Row {
            Objects.requireNonNull(symbol);
            Objects.requireNonNull(date);
            Objects.requireNonNull(values);
        }
    }

    private record Key(String symbol, LocalDate date) {}

    private final Map<String, Rule> rules;

    private StockCodeAliases(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public static StockCodeAliases load() {
        var rules = new LinkedHashMap<String, Rule>();
        try (var stream =
                StockCodeAliases.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
            if (stream == null) throw new IllegalStateException("Missing stock alias rules");
            try (var reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 3)
                        throw new IllegalStateException("Invalid stock alias rule");
                    var rule = new Rule(fields[0], fields[1], LocalDate.parse(fields[2]));
                    if (rules.putIfAbsent(rule.legacy(), rule) != null
                            || rules.putIfAbsent(rule.canonical(), rule) != null)
                        throw new IllegalStateException("Duplicate stock alias rule");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StockCodeAliases(rules);
    }

    public String canonical(String symbol) {
        var rule = rules.get(symbol);
        return rule == null ? symbol : rule.canonical();
    }

    private boolean preferred(Row row) {
        var rule = rules.get(row.symbol());
        return rule == null
                || row.symbol()
                        .equals(
                                row.date().isBefore(rule.switchDate())
                                        ? rule.legacy()
                                        : rule.canonical());
    }

    /** Values are borrowed; callers freeze input buffers until the resulting rows have been consumed. */
    public List<Row> normalize(List<Row> rows, int[] stablePayloadColumns) {
        var selected = new LinkedHashMap<Key, Row>();
        for (var row : rows) {
            var key = new Key(canonical(row.symbol()), row.date());
            var prior = selected.putIfAbsent(key, row);
            if (prior == null) continue;
            if (prior.symbol().equals(row.symbol()))
                throw new IllegalStateException("Duplicate source security/date: " + key);
            if (prior.values().length != row.values().length)
                throw new IllegalArgumentException("Source column lengths differ");
            for (int column : stablePayloadColumns) {
                double a = prior.values()[column], b = row.values()[column];
                if (Double.isNaN(a) && Double.isNaN(b)) continue;
                if (!Double.isFinite(a)
                        || !Double.isFinite(b)
                        || Math.abs(a - b) > 1e-10 + 1e-10 * Math.abs(b))
                    throw new IllegalStateException("Stock alias payload conflict: " + key);
            }
            if (preferred(row) && !preferred(prior)) selected.put(key, row);
        }
        return selected.entrySet().stream()
                .map(e -> new Row(e.getKey().symbol(), e.getKey().date(), e.getValue().values()))
                .toList();
    }
}
