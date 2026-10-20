package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared ETF market returns and beta, after reference filtering; never fills missing trading rows. */
public final class EtfMarketPanel {
    private EtfMarketPanel() {}

    public record Input(String symbol, LocalDate[] dates, double[] close, double[] amount) {}

    public record Output(String symbol, LocalDate[] dates, Map<String, double[]> columns) {}

    public static List<Output> compute(List<Input> inputs, Map<String, String> categories) {
        var panel = compute(inputs);
        var index = new EtfDateIndex(panel);
        var residuals = new HashMap<String, double[]>();
        for (var security : panel) {
            var values = new double[security.dates().length];
            Arrays.fill(values, Double.NaN);
            residuals.put(security.symbol(), values);
        }
        for (var selected : index.dates().values()) {
            int n = selected.size();
            double[] returns = new double[n], beta = new double[n];
            String[] styles = new String[n];
            for (int i = 0; i < n; i++) {
                var position = selected.get(i);
                var security = panel.get(position.security());
                int row = position.row();
                returns[i] = security.columns().get("ret20")[row];
                beta[i] = security.columns().get("beta60")[row];
                styles[i] = categories.get(security.symbol());
            }
            var values = EtfResidualMomentum.compute(returns, beta, styles);
            for (int i = 0; i < n; i++) {
                var position = selected.get(i);
                var security = panel.get(position.security());
                residuals.get(security.symbol())[position.row()] = values[i];
            }
        }
        var result = new ArrayList<Output>();
        for (var security : panel) {
            var columns = new LinkedHashMap<>(security.columns());
            columns.put("residual_momentum_20d", residuals.get(security.symbol()));
            result.add(
                    new Output(
                            security.symbol(),
                            security.dates(),
                            Collections.unmodifiableMap(columns)));
        }
        return List.copyOf(result);
    }

    public static List<Output> compute(List<Input> inputs) {
        var symbols = new HashSet<String>();
        var prepared = new ArrayList<Output>();
        var market = new TreeMap<LocalDate, List<Double>>();
        for (var input : inputs) {
            if (input.symbol() == null
                    || input.symbol().isBlank()
                    || !symbols.add(input.symbol())
                    || input.dates().length != input.close().length
                    || input.dates().length != input.amount().length)
                throw new IllegalArgumentException("Unique aligned ETF inputs required");
            for (int i = 0; i < input.dates().length; i++)
                if (input.dates()[i] == null
                        || i > 0 && !input.dates()[i].isAfter(input.dates()[i - 1]))
                    throw new IllegalArgumentException("Increasing unique ETF dates required");
            var columns =
                    new LinkedHashMap<>(EtfMarketFeatures.compute(input.close(), input.amount()));
            for (int i = 0; i < input.dates().length; i++) {
                var values = market.computeIfAbsent(input.dates()[i], ignored -> new ArrayList<>());
                double value = columns.get("ret1")[i];
                if (!Double.isNaN(value)) values.add(value);
            }
            prepared.add(new Output(input.symbol(), input.dates().clone(), columns));
        }
        var medians = new HashMap<LocalDate, Double>();
        market.forEach(
                (date, values) -> {
                    values.sort(Double::compare);
                    int n = values.size();
                    medians.put(
                            date,
                            n == 0
                                    ? Double.NaN
                                    : n % 2 == 1
                                            ? values.get(n / 2)
                                            : (values.get(n / 2 - 1) + values.get(n / 2)) / 2);
                });
        var result = new ArrayList<Output>();
        for (var security : prepared) {
            int n = security.dates().length;
            double[] marketReturn = new double[n];
            for (int i = 0; i < n; i++) marketReturn[i] = medians.get(security.dates()[i]);
            var columns = security.columns();
            var ret = columns.get("ret1");
            var product = FactorSeries.combine(ret, marketReturn, (a, b) -> a * b);
            var square = FactorSeries.map(marketReturn, a -> a * a);
            var meanAsset = FactorSeries.rolling(ret, 60, 30, false);
            var meanMarket = FactorSeries.rolling(marketReturn, 60, 30, false);
            var meanProduct = FactorSeries.rolling(product, 60, 30, false);
            var meanSquare = FactorSeries.rolling(square, 60, 30, false);
            double[] beta = new double[n];
            for (int i = 0; i < n; i++)
                beta[i] =
                        FactorSeries.ratio(
                                meanProduct[i] - meanAsset[i] * meanMarket[i],
                                meanSquare[i] - meanMarket[i] * meanMarket[i]);
            columns.put("market_ret1", marketReturn);
            columns.put("ret_market_product", product);
            columns.put("market_ret_sq", square);
            columns.put("beta60", beta);
            result.add(
                    new Output(
                            security.symbol(),
                            security.dates(),
                            Collections.unmodifiableMap(columns)));
        }
        return List.copyOf(result);
    }
}
