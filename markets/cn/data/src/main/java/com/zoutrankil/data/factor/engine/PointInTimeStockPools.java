package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** V3 base-IC membership over one frozen, normalized stock-date cross section.
 * Ranking is shared across children; listing counts must come from distinct historical trading rows.
 */
public final class PointInTimeStockPools {
    private PointInTimeStockPools() {}

    public static final String VERSION = "investable_a_v3_global_total_mv90";

    public record Stock(
            String symbol,
            double totalMv,
            int listingTradeDays,
            double isSt,
            double isSuspended,
            boolean tradable) {}

    public record IndexMember(String indexCode, LocalDate date, String symbol) {}

    public record Definition(String id, String indexCode, List<String> prefixes) {
        public Definition {
            prefixes = List.copyOf(prefixes);
        }
    }

    public static final List<Definition> DEFINITIONS =
            List.of(
                    new Definition("all_market", "", List.of()),
                    new Definition("csi300", "000300", List.of()),
                    new Definition("csi_a500", "000510", List.of()),
                    new Definition("csi500", "000905", List.of()),
                    new Definition("csi800", "000906", List.of()),
                    new Definition("csi1000", "000852", List.of()),
                    new Definition("csi_all", "000985", List.of()),
                    new Definition("csi2000", "932000", List.of()),
                    new Definition("chinext", "", List.of("300", "301")),
                    new Definition("chinext50", "399673", List.of()),
                    new Definition("star_market", "", List.of("688", "689")),
                    new Definition("star50", "000688", List.of()));

    public record Member(
            int sourceIndex, String symbol, double parentPercentile, List<String> reasons) {
        public Member {
            reasons = List.copyOf(reasons);
        }

        public boolean included() {
            return reasons.isEmpty();
        }
    }

    public record Resolution(
            String poolId,
            LocalDate membershipAsOf,
            String unavailableReason,
            List<Member> members) {
        public Resolution {
            members = List.copyOf(members);
        }

        public boolean available() {
            return unavailableReason.isEmpty();
        }

        public MultiPoolFactorPipeline.Pool asPool() {
            if (!available()) throw new IllegalStateException(poolId + ": " + unavailableReason);
            return new MultiPoolFactorPipeline.Pool(
                    poolId,
                    members.stream()
                            .filter(Member::included)
                            .mapToInt(Member::sourceIndex)
                            .toArray());
        }
    }

    public static final class Index {
        private final Map<String, NavigableMap<LocalDate, Set<String>>> history;

        public Index(List<IndexMember> rows) {
            var grouped = new HashMap<String, NavigableMap<LocalDate, Set<String>>>();
            for (var row : rows) {
                Objects.requireNonNull(row.date());
                Objects.requireNonNull(row.symbol());
                grouped.computeIfAbsent(row.indexCode(), ignored -> new TreeMap<>())
                        .computeIfAbsent(row.date(), ignored -> new HashSet<>())
                        .add(row.symbol());
            }
            var frozen = new HashMap<String, NavigableMap<LocalDate, Set<String>>>();
            grouped.forEach(
                    (code, dates) -> {
                        var snapshots = new TreeMap<LocalDate, Set<String>>();
                        dates.forEach((date, symbols) -> snapshots.put(date, Set.copyOf(symbols)));
                        frozen.put(code, Collections.unmodifiableNavigableMap(snapshots));
                    });
            history = Map.copyOf(frozen);
        }

        public List<Resolution> resolve(LocalDate date, List<Stock> stocks, List<String> poolIds) {
            Objects.requireNonNull(date);
            var requested = new HashSet<String>();
            var definitions = new ArrayList<Definition>();
            for (String id : poolIds) {
                if (!requested.add(id)) throw new IllegalArgumentException("Duplicate pool: " + id);
                definitions.add(
                        DEFINITIONS.stream()
                                .filter(d -> d.id().equals(id))
                                .findFirst()
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Unknown V3 pool: " + id)));
            }
            var symbols = new HashSet<String>();
            var ordinary = new boolean[stocks.size()];
            var ranked = new ArrayList<Integer>();
            int ordinaryCount = 0;
            for (int i = 0; i < stocks.size(); i++) {
                var stock = stocks.get(i);
                if (stock.symbol() == null || !symbols.add(stock.symbol()))
                    throw new IllegalArgumentException("Unique normalized stock symbols required");
                ordinary[i] = ordinary(stock.symbol());
                if (ordinary[i]) {
                    ordinaryCount++;
                    if (stock.totalMv() > 0) ranked.add(i);
                }
            }
            ranked.sort(
                    Comparator.<Integer>comparingDouble(i -> stocks.get(i).totalMv())
                            .reversed()
                            .thenComparing(i -> stocks.get(i).symbol()));
            int keep = (int) Math.ceil(ordinaryCount * 0.90);
            double[] percentiles = new double[stocks.size()];
            Arrays.fill(percentiles, Double.NaN);
            boolean[] retained = new boolean[stocks.size()];
            for (int rank = 0; rank < ranked.size(); rank++) {
                int i = ranked.get(rank);
                percentiles[i] = (rank + 1.0) / ordinaryCount;
                retained[i] = rank < keep;
            }
            var output = new ArrayList<Resolution>();
            for (var definition : definitions) {
                String error =
                        stocks.isEmpty()
                                ? "signal-date market snapshot unavailable"
                                : ranked.size() < keep
                                        ? "insufficient complete all-A parent ranking for global cutoff"
                                        : "";
                LocalDate asOf = date;
                Set<String> membership = null;
                if (error.isEmpty() && !definition.indexCode().isEmpty()) {
                    var dates = history.get(definition.indexCode());
                    var snapshot = dates == null ? null : dates.floorEntry(date);
                    if (snapshot == null) error = "PIT index membership unavailable";
                    else {
                        asOf = snapshot.getKey();
                        membership = snapshot.getValue();
                    }
                }
                if (!error.isEmpty()) {
                    output.add(new Resolution(definition.id(), null, error, List.of()));
                    continue;
                }
                var members = new ArrayList<Member>();
                for (int i = 0; i < stocks.size(); i++) {
                    var stock = stocks.get(i);
                    var reasons = new ArrayList<String>();
                    if (!ordinary[i]) reasons.add("not_ordinary_a_share");
                    else {
                        if (!(stock.totalMv() > 0)) reasons.add("market_cap_unavailable");
                        else if (!retained[i]) reasons.add("market_cap_below_global_top_90pct");
                        boolean child = membership == null || membership.contains(stock.symbol());
                        if (!definition.prefixes().isEmpty())
                            child =
                                    definition.prefixes().stream()
                                            .anyMatch(stock.symbol()::startsWith);
                        if (!child) reasons.add("not_pit_member");
                        if (stock.isSt() != 0) reasons.add("st_or_status_unavailable");
                        if (stock.isSuspended() != 0) reasons.add("suspended");
                        if (!stock.tradable()) reasons.add("not_tradable");
                        boolean growth =
                                stock.symbol().startsWith("300")
                                        || stock.symbol().startsWith("301")
                                        || stock.symbol().startsWith("688")
                                        || stock.symbol().startsWith("689");
                        if (stock.listingTradeDays() < (growth ? 252 : 60))
                            reasons.add("listing_history_below_parent_rule");
                    }
                    members.add(new Member(i, stock.symbol(), percentiles[i], reasons));
                }
                members.sort(Comparator.comparing(Member::symbol));
                output.add(new Resolution(definition.id(), asOf, "", members));
            }
            return List.copyOf(output);
        }
    }

    private static boolean ordinary(String symbol) {
        String upper = symbol.toUpperCase(Locale.ROOT);
        return upper.length() == 9
                && upper.charAt(6) == '.'
                && ((upper.endsWith(".SH") && upper.startsWith("6"))
                        || (upper.endsWith(".SZ")
                                && (upper.startsWith("0") || upper.startsWith("3"))));
    }
}
