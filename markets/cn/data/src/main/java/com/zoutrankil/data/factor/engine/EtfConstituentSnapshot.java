package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/** Python extract_latest_etf_constituents: latest report period among announced rows. */
public final class EtfConstituentSnapshot {
    private EtfConstituentSnapshot() {}

    public static List<EtfConstituentWeights.Input> select(
            List<EtfConstituentWeights.Input> source, LocalDate asOf) {
        Objects.requireNonNull(asOf);
        var latest = new HashMap<String, LocalDate>();
        for (var row : source)
            if (eligible(row, asOf) && row.periodEnd() != null)
                latest.merge(row.etf(), row.periodEnd(), (a, b) -> a.isAfter(b) ? a : b);
        return source.stream()
                .filter(
                        row ->
                                eligible(row, asOf)
                                        && row.periodEnd() != null
                                        && row.periodEnd().equals(latest.get(row.etf())))
                .sorted(
                        Comparator.comparing(EtfConstituentWeights.Input::etf)
                                .thenComparing(
                                        EtfConstituentWeights.Input::stock,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    private static boolean eligible(EtfConstituentWeights.Input row, LocalDate asOf) {
        return row.etf() != null
                && (row.etf().endsWith(".SH") || row.etf().endsWith(".SZ"))
                && row.announced() != null
                && !row.announced().isAfter(asOf);
    }
}
