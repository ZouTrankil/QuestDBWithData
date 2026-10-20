package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared stock exposures matching Python build_stock_exposures interval semantics. */
public final class PointInTimeStockExposures {
    private PointInTimeStockExposures() {}

    public record Membership(
            String symbol,
            LocalDate inDate,
            LocalDate outDate,
            String level,
            String l1Name,
            String l2Name) {}

    public record Exposures(double[] logMarketCap, String[] industry) {}

    /** Build once per source snapshot, then resolve every requested date against the same index.
     * Input order breaks equal in-date ties, as in the Python source frame.
     */
    public static final class Index {
        private final Map<String, List<Membership>> bySymbol;

        public Index(List<Membership> memberships) {
            boolean preferL1 = memberships.stream().anyMatch(m -> "L1".equalsIgnoreCase(m.level()));
            var grouped = new HashMap<String, List<Membership>>();
            for (var member : memberships) {
                Objects.requireNonNull(member.symbol(), "membership symbol");
                if (preferL1 && !"L1".equalsIgnoreCase(member.level())) continue;
                if (industryName(member) == null) continue;
                grouped.computeIfAbsent(member.symbol(), ignored -> new ArrayList<>()).add(member);
            }
            var order =
                    Comparator.comparing(
                            Membership::inDate, Comparator.nullsFirst(Comparator.naturalOrder()));
            var frozen = new HashMap<String, List<Membership>>();
            grouped.forEach(
                    (symbol, rows) -> {
                        rows.sort(order);
                        frozen.put(symbol, List.copyOf(rows));
                    });
            bySymbol = Map.copyOf(frozen);
        }

        public Exposures resolve(
                LocalDate date, String[] symbols, double[] circMv, double[] totalMv) {
            Objects.requireNonNull(date);
            if (symbols.length != circMv.length || symbols.length != totalMv.length)
                throw new IllegalArgumentException("Misaligned market capitalization");
            var distinct = new HashSet<String>();
            double[] size = new double[symbols.length];
            String[] industry = new String[symbols.length];
            for (int i = 0; i < symbols.length; i++) {
                if (symbols[i] == null || !distinct.add(symbols[i]))
                    throw new IllegalArgumentException("Unique shared symbols required");
                // Python fills missing circ_mv only; zero/negative values do not fall back.
                double cap = Double.isNaN(circMv[i]) ? totalMv[i] : circMv[i];
                size[i] = cap > 0 ? Math.log(cap) : Double.NaN;
                for (var member : bySymbol.getOrDefault(symbols[i], List.of())) {
                    if (member.inDate() != null && member.inDate().isAfter(date)) break;
                    if (member.outDate() == null || member.outDate().isAfter(date))
                        industry[i] = industryName(member);
                }
            }
            return new Exposures(size, industry);
        }
    }

    private static String industryName(Membership member) {
        String value = member.l1Name() == null ? member.l2Name() : member.l1Name();
        if (value == null || value.strip().isEmpty()) return null;
        return value.strip();
    }
}
