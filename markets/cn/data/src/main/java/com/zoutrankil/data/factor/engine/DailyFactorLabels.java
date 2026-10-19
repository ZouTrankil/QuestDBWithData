package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** Python factor_daily_labels parity. The caller supplies adjacent official sessions. */
public final class DailyFactorLabels {
    public static final String LEGACY = "close_hfq-fwd1-v1";
    public static final String SUSPENSION_LAST = "close_hfq-fwd1-suspend-last-v2";
    private DailyFactorLabels() {}
    public record Price(LocalDate date, String symbol, double closeHfq) {}
    public record Suspension(LocalDate date, String symbol, double suspended) {}
    public record Label(String symbol, LocalDate signalDate, double signalClose,
            double maturityClose, LocalDate priceSourceDate, String status, double forwardReturn) {}

    public static List<Label> build(LocalDate signal, LocalDate maturity, List<Price> prices,
            List<Suspension> suspensions, String definition) {
        if (!LEGACY.equals(definition) && !SUSPENSION_LAST.equals(definition))
            throw new IllegalArgumentException("Unsupported daily label definition: " + definition);
        if (signal == null || maturity == null || !maturity.isAfter(signal))
            throw new IllegalArgumentException("Label maturity must follow signal date");
        var today = new LinkedHashMap<String,Double>();
        var future = new HashMap<String,Double>();
        for (var price : prices) {
            Map<String,Double> target = signal.equals(price.date()) ? today
                    : maturity.equals(price.date()) ? future : null;
            if (target == null) continue;
            if (price.symbol() == null || target.putIfAbsent(price.symbol(), price.closeHfq()) != null)
                throw new IllegalArgumentException("Missing or duplicate daily price business key");
        }
        if (today.isEmpty() || future.isEmpty())
            throw new IllegalArgumentException("Signal or maturity prices unavailable");
        var suspended = new HashSet<String>();
        if (SUSPENSION_LAST.equals(definition) && suspensions != null) {
            var seen = new HashSet<String>();
            for (var row : suspensions) if (maturity.equals(row.date())) {
                if (row.symbol() == null || !seen.add(row.symbol()))
                    throw new IllegalArgumentException("Missing or duplicate target-date suspension key");
                if (row.suspended() == 1) suspended.add(row.symbol());
            }
        }
        var result = new ArrayList<Label>(today.size());
        for (var entry : today.entrySet()) {
            String symbol = entry.getKey(); double start = entry.getValue();
            boolean present = future.containsKey(symbol);
            double end = future.getOrDefault(symbol, Double.NaN);
            String status = present ? "observed" : "missing_maturity_price";
            LocalDate sourceDate = present ? maturity : null;
            if (!present && suspended.contains(symbol) && valid(start)) {
                end = start; sourceDate = signal; status = "suspended_last_price";
            }
            boolean valid = valid(start) && valid(end);
            if (!valid && !status.equals("missing_maturity_price")) status = "invalid_price";
            result.add(new Label(symbol, signal, start, end, sourceDate, status,
                    valid ? end / start - 1 : Double.NaN));
        }
        return List.copyOf(result);
    }
    private static boolean valid(double price) { return Double.isFinite(price) && price > 0; }
}
