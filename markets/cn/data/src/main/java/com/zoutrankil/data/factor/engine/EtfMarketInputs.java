package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reference filtering and date normalization boundary before shared numerical calculation. */
public final class EtfMarketInputs {
    private EtfMarketInputs() {}

    public record Bar(String symbol, LocalDate date, double close, double amount) {}

    public record Reference(String symbol, String name, String status) {}

    public record Prepared(
            List<EtfMarketPanel.Input> inputs,
            Map<String, String> categories,
            Map<String, String> names) {}

    public static Prepared prepare(List<Bar> bars, List<Reference> reference) {
        var active = new LinkedHashMap<String, Reference>();
        for (var row : reference)
            if (row.status() == null || row.status().equals("L"))
                active.putIfAbsent(row.symbol(), row);
        var grouped = new TreeMap<String, TreeMap<LocalDate, Bar>>();
        for (var bar : bars) {
            if (bar.date() == null) continue;
            if (bar.symbol() == null || bar.symbol().isBlank())
                throw new IllegalArgumentException("ETF symbol required");
            if (!reference.isEmpty() && !active.containsKey(bar.symbol())) continue;
            // The source adapter supplies stable encounter order for duplicate observations.
            grouped.computeIfAbsent(bar.symbol(), ignored -> new TreeMap<>()).put(bar.date(), bar);
        }
        var inputs = new ArrayList<EtfMarketPanel.Input>();
        var categories = new LinkedHashMap<String, String>();
        var names = new LinkedHashMap<String, String>();
        grouped.forEach(
                (symbol, rows) -> {
                    var dates = new LocalDate[rows.size()];
                    double[] close = new double[rows.size()], amount = new double[rows.size()];
                    int index = 0;
                    for (var row : rows.values()) {
                        dates[index] = row.date();
                        close[index] = row.close();
                        amount[index++] = row.amount();
                    }
                    inputs.add(new EtfMarketPanel.Input(symbol, dates, close, amount));
                    var entry = active.get(symbol);
                    String name = entry == null ? null : entry.name();
                    names.put(symbol, name);
                    categories.put(symbol, EtfResearchStyle.classify(name));
                });
        return new Prepared(
                List.copyOf(inputs),
                Collections.unmodifiableMap(categories),
                Collections.unmodifiableMap(names));
    }
}
