package com.zoutrankil.batch.l2;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Native P7: minute aggregation, StandardScaler and sklearn-compatible three-component full GMM. */
public final class L2GmmFeatures {
    private static final int COMPONENTS = 3, DIMENSIONS = 5;
    private static final double REGULARIZATION = 1e-6;
    private L2GmmFeatures() {}

    private static final class Window {
        double volume, buyVolume, cancelVolume, orderVolume, spread;
        int deals, buys, cancels, orders, quotes;
    }

    public static Map<String, Object> compute(L2FeatureData data) {
        Map<String, Object> out = empty();
        if (data.deals().isEmpty()) return out;
        TreeMap<Long, Window> windows = new TreeMap<>();
        for (var deal : data.deals()) {
            Window window = windows.computeIfAbsent(deal.time() / 60_000 * 60_000, key -> new Window());
            window.volume += deal.volume(); window.deals++;
            if (deal.side() == 0) { window.buyVolume += deal.volume(); window.buys++; }
            if (deal.side() == -1 || deal.side() == -11) { window.cancelVolume += deal.volume(); window.cancels++; }
        }
        for (var order : data.orders()) {
            Window window = windows.computeIfAbsent(order.time() / 60_000 * 60_000, key -> new Window());
            window.orderVolume += order.volume(); window.orders++;
        }
        for (var quote : data.quotes()) {
            Window window = windows.get(quote.time() / 60_000 * 60_000);
            if (window == null) continue;
            double spread = (quote.askPrice()[0] - quote.bidPrice()[0]) / (quote.bidPrice()[0] + 1e-6);
            if (!Double.isNaN(spread)) { window.spread += spread; window.quotes++; }
        }
        if (windows.size() < 5) return out;
        double[][] values = new double[windows.size()][DIMENSIONS];
        long[] keys = new long[windows.size()]; int row = 0;
        for (var entry : windows.entrySet()) {
            Window w = entry.getValue(); keys[row] = entry.getKey();
            values[row++] = new double[] { w.deals == 0 ? 0 : w.volume / w.deals, w.orders,
                    w.cancels == 0 ? 0 : Math.max(0, Math.min(1, w.cancelVolume / (w.orderVolume + 1e-6))),
                    w.quotes == 0 ? 0 : w.spread / w.quotes,
                    w.buys == 0 ? 0 : w.buyVolume / (w.volume + 1e-6) };
        }
        // Python first applies X.fillna(0); infinities then fail sklearn validation
        // and the canonical builder returns its four empty-result metrics.
        for (double[] value : values) for (int d = 0; d < DIMENSIONS; d++) {
            if (Double.isNaN(value[d])) value[d] = 0;
            else if (!Double.isFinite(value[d])) return out;
        }
        double[] mean = new double[DIMENSIONS], scale = new double[DIMENSIONS];
        for (double[] value : values) for (int d = 0; d < DIMENSIONS; d++) mean[d] += value[d];
        for (int d = 0; d < DIMENSIONS; d++) mean[d] /= values.length;
        for (double[] value : values) for (int d = 0; d < DIMENSIONS; d++) { double diff = value[d] - mean[d]; scale[d] += diff * diff / values.length; }
        for (int d = 0; d < DIMENSIONS; d++) {
            double error = values.length * Math.ulp(1.0) * scale[d] + Math.pow(values.length * mean[d] * Math.ulp(1.0), 2);
            scale[d] = scale[d] <= error ? 1 : Math.sqrt(scale[d]);
        }
        double[][] x = new double[values.length][DIMENSIONS];
        for (int i = 0; i < x.length; i++) for (int d = 0; d < DIMENSIONS; d++) {
            x[i][d] = (values[i][d] - mean[d]) / scale[d];
            if (!Double.isFinite(x[i][d])) return out;
        }
        GaussianModel model;
        try { model = fit(x); }
        catch (IllegalArgumentException numericalFailure) { return out; }
        int[] labels = predict(x, model);
        // The reference labels inverse-transformed centroids. This matters for
        // constant features: restoring their scale makes numerical ties exact.
        double[][] centroids = new double[COMPONENTS][DIMENSIONS];
        for (int c = 0; c < COMPONENTS; c++) for (int d = 0; d < DIMENSIONS; d++)
            centroids[c][d] = model.mean[c][d] * scale[d] + mean[d];
        int hft = 0, main = 0;
        for (int c = 1; c < COMPONENTS; c++) {
            if (centroids[c][1] > centroids[hft][1]) hft = c;
            if (centroids[c][0] > centroids[main][0]) main = c;
        }
        if (main == hft) {
            Integer[] ranked = {0, 1, 2};
            Arrays.sort(ranked, (a, b) -> Double.compare(centroids[b][0], centroids[a][0]));
            main = ranked[1];
        }
        int retail = 0;
        while (retail == hft || retail == main) retail++;
        Map<Long, Integer> windowLabels = new LinkedHashMap<>();
        for (int i = 0; i < labels.length; i++) windowLabels.put(keys[i], labels[i]);
        double totalVolume = 0, mainVolume = 0, hftVolume = 0, retailVolume = 0, mainNet = 0;
        for (var deal : data.deals()) {
            totalVolume += deal.volume();
            Integer label = windowLabels.get(deal.time() / 60_000 * 60_000);
            if (label == null) continue;
            if (label == main) { mainVolume += deal.volume(); mainNet += deal.amount() * (deal.side() == 0 ? 1 : -1); }
            else if (label == hft) hftVolume += deal.volume();
            else if (label == retail) retailVolume += deal.volume();
        }
        if (totalVolume == 0) return out;
        out.put("gmm_main_force_ratio", mainVolume / totalVolume);
        out.put("gmm_hft_ratio", hftVolume / totalVolume);
        out.put("gmm_retail_ratio", retailVolume / totalVolume);
        out.put("gmm_main_force_net_inflow", mainNet);
        return out;
    }

    private static Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gmm_main_force_ratio", 0.0); out.put("gmm_hft_ratio", 0.0);
        out.put("gmm_retail_ratio", 0.0); out.put("gmm_main_force_net_inflow", 0.0);
        return out;
    }

    private static final class GaussianModel {
        final double[] weight = new double[COMPONENTS];
        final double[][] mean = new double[COMPONENTS][DIMENSIONS];
        final double[][][] covariance = new double[COMPONENTS][DIMENSIONS][DIMENSIONS];
        final double[][][] cholesky = new double[COMPONENTS][DIMENSIONS][DIMENSIONS];
        final double[] logDet = new double[COMPONENTS];
    }

    private static GaussianModel fit(double[][] x) {
        int[] initial = kmeans(x);
        double[][] responsibility = new double[x.length][COMPONENTS];
        for (int i = 0; i < x.length; i++) responsibility[i][initial[i]] = 1;
        GaussianModel model = maximize(x, responsibility);
        double previous = Double.NEGATIVE_INFINITY;
        for (int iteration = 0; iteration < 100; iteration++) {
            double bound = expectation(x, model, responsibility);
            model = maximize(x, responsibility);
            if (Math.abs(bound - previous) < 1e-3) break;
            previous = bound;
        }
        return model;
    }

    private static GaussianModel maximize(double[][] x, double[][] responsibility) {
        GaussianModel model = new GaussianModel();
        double[] count = new double[COMPONENTS];
        Arrays.fill(count, 10 * Math.ulp(1.0));
        for (int i = 0; i < x.length; i++) for (int c = 0; c < COMPONENTS; c++) {
            double r = responsibility[i][c]; count[c] += r;
            for (int d = 0; d < DIMENSIONS; d++) model.mean[c][d] += r * x[i][d];
        }
        double total = Arrays.stream(count).sum();
        for (int c = 0; c < COMPONENTS; c++) {
            model.weight[c] = count[c] / total;
            for (int d = 0; d < DIMENSIONS; d++) model.mean[c][d] /= count[c];
            for (int i = 0; i < x.length; i++) for (int d = 0; d < DIMENSIONS; d++) for (int e = 0; e <= d; e++)
                model.covariance[c][d][e] += responsibility[i][c] * (x[i][d] - model.mean[c][d]) * (x[i][e] - model.mean[c][e]);
            for (int d = 0; d < DIMENSIONS; d++) for (int e = 0; e <= d; e++) {
                double value = model.covariance[c][d][e] / count[c] + (d == e ? REGULARIZATION : 0);
                model.covariance[c][d][e] = model.covariance[c][e][d] = value;
            }
            for (int d = 0; d < DIMENSIONS; d++) for (int e = 0; e <= d; e++) {
                double value = model.covariance[c][d][e];
                for (int j = 0; j < e; j++) value -= model.cholesky[c][d][j] * model.cholesky[c][e][j];
                if (!Double.isFinite(value) || d == e && value <= 0)
                    throw new IllegalArgumentException("Nonfinite or singular GMM covariance");
                model.cholesky[c][d][e] = d == e ? Math.sqrt(value) : value / model.cholesky[c][e][e];
            }
            for (int d = 0; d < DIMENSIONS; d++) model.logDet[c] += Math.log(model.cholesky[c][d][d]);
        }
        return model;
    }

    private static double logProbability(double[] x, GaussianModel model, int c) {
        double[] transformed = new double[DIMENSIONS]; double squared = 0;
        for (int d = 0; d < DIMENSIONS; d++) {
            double value = x[d] - model.mean[c][d];
            for (int e = 0; e < d; e++) value -= model.cholesky[c][d][e] * transformed[e];
            transformed[d] = value / model.cholesky[c][d][d]; squared += transformed[d] * transformed[d];
        }
        return Math.log(model.weight[c]) - 0.5 * (DIMENSIONS * Math.log(2 * Math.PI) + squared) - model.logDet[c];
    }

    private static double expectation(double[][] x, GaussianModel model, double[][] responsibility) {
        double bound = 0;
        for (int i = 0; i < x.length; i++) {
            double max = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < COMPONENTS; c++) { responsibility[i][c] = logProbability(x[i], model, c); max = Math.max(max, responsibility[i][c]); }
            double sum = 0;
            for (int c = 0; c < COMPONENTS; c++) sum += Math.exp(responsibility[i][c] - max);
            double norm = max + Math.log(sum); bound += norm;
            if (!Double.isFinite(norm)) throw new IllegalArgumentException("Nonfinite GMM likelihood");
            for (int c = 0; c < COMPONENTS; c++) responsibility[i][c] = Math.exp(responsibility[i][c] - norm);
        }
        return bound / x.length;
    }

    private static int[] predict(double[][] x, GaussianModel model) {
        int[] labels = new int[x.length];
        for (int i = 0; i < x.length; i++) {
            double best = logProbability(x[i], model, 0);
            for (int c = 1; c < COMPONENTS; c++) { double value = logProbability(x[i], model, c); if (value > best) { best = value; labels[i] = c; } }
        }
        return labels;
    }

    private static int[] kmeans(double[][] input) {
        // sklearn centers the data before seeding/iteration for numerical accuracy.
        double[][] x = new double[input.length][DIMENSIONS]; double[] center = new double[DIMENSIONS];
        for (double[] row : input) for (int d = 0; d < DIMENSIONS; d++) center[d] += row[d] / input.length;
        double variance = 0;
        for (int i = 0; i < x.length; i++) for (int d = 0; d < DIMENSIONS; d++) { x[i][d] = input[i][d] - center[d]; variance += x[i][d] * x[i][d] / (x.length * DIMENSIONS); }
        double tolerance = variance * 1e-4;
        RandomState random = new RandomState(42);
        double[][] means = new double[COMPONENTS][DIMENSIONS];
        means[0] = x[Math.min((int)(random.nextDouble() * x.length), x.length - 1)].clone();
        double[] closest = new double[x.length];
        for (int i = 0; i < x.length; i++) closest[i] = squaredDistance(x[i], means[0]);
        for (int c = 1; c < COMPONENTS; c++) {
            double potential = Arrays.stream(closest).sum(), bestPotential = Double.POSITIVE_INFINITY;
            int bestCandidate = 0; double[] bestDistances = null;
            for (int trial = 0; trial < 3; trial++) {
                double randomValue = random.nextDouble() * potential, cumulative = 0;
                int candidate = 0;
                while (candidate < closest.length - 1 && (cumulative += closest[candidate]) < randomValue) candidate++;
                double[] distances = new double[x.length]; double newPotential = 0;
                for (int i = 0; i < x.length; i++) { distances[i] = Math.min(closest[i], squaredDistance(x[i], x[candidate])); newPotential += distances[i]; }
                if (newPotential < bestPotential) { bestPotential = newPotential; bestCandidate = candidate; bestDistances = distances; }
            }
            means[c] = x[bestCandidate].clone(); closest = bestDistances;
        }
        int[] labels = new int[x.length], previous = new int[x.length]; Arrays.fill(previous, -1);
        boolean strict = false;
        for (int iteration = 0; iteration < 300; iteration++) {
            double[][] next = new double[COMPONENTS][DIMENSIONS]; int[] counts = new int[COMPONENTS];
            for (int i = 0; i < x.length; i++) {
                labels[i] = nearest(x[i], means); counts[labels[i]]++;
                for (int d = 0; d < DIMENSIONS; d++) next[labels[i]][d] += x[i][d];
            }
            // Match sklearn's relocation of empty clusters to the farthest assigned point.
            for (int c = 0; c < COMPONENTS; c++) if (counts[c] == 0) {
                int farthest = -1; double distance = -1;
                for (int i = 0; i < x.length; i++) if (counts[labels[i]] > 1) {
                    double candidate = squaredDistance(x[i], means[labels[i]]);
                    if (candidate > distance) { distance = candidate; farthest = i; }
                }
                if (farthest >= 0 && distance > 0) {
                    counts[c]++; counts[labels[farthest]]--;
                    for (int d = 0; d < DIMENSIONS; d++) { next[c][d] = x[farthest][d]; next[labels[farthest]][d] -= x[farthest][d]; }
                }
            }
            int largest = 0; for (int c = 1; c < COMPONENTS; c++) if (counts[c] > counts[largest]) largest = c;
            for (int c = 0; c < COMPONENTS; c++) if (counts[c] > 0) for (int d = 0; d < DIMENSIONS; d++) next[c][d] /= counts[c];
            for (int c = 0; c < COMPONENTS; c++) if (counts[c] == 0) next[c] = next[largest].clone();
            double shift = 0;
            for (int c = 0; c < COMPONENTS; c++) shift += squaredDistance(means[c], next[c]);
            means = next;
            if (Arrays.equals(labels, previous)) { strict = true; break; }
            if (shift <= tolerance) break;
            System.arraycopy(labels, 0, previous, 0, labels.length);
        }
        if (!strict) for (int i = 0; i < x.length; i++) labels[i] = nearest(x[i], means);
        return labels;
    }

    private static int nearest(double[] point, double[][] means) {
        int best = 0; double distance = squaredDistance(point, means[0]);
        for (int c = 1; c < COMPONENTS; c++) { double next = squaredDistance(point, means[c]); if (next < distance) { distance = next; best = c; } }
        return best;
    }
    private static double squaredDistance(double[] a, double[] b) { double sum = 0; for (int d = 0; d < DIMENSIONS; d++) { double diff = a[d] - b[d]; sum += diff * diff; } return sum; }

    /** NumPy legacy RandomState MT19937 and its 53-bit uniform-double conversion. */
    private static final class RandomState {
        private final int[] state = new int[624]; private int index = 624;
        RandomState(int seed) { state[0] = seed; for (int i = 1; i < state.length; i++) state[i] = 1812433253 * (state[i-1] ^ (state[i-1] >>> 30)) + i; }
        private int nextInt() {
            if (index == 624) {
                for (int i = 0; i < 624; i++) { int y = (state[i] & 0x80000000) | (state[(i+1)%624] & 0x7fffffff); state[i] = state[(i+397)%624] ^ (y >>> 1) ^ ((y & 1) == 0 ? 0 : 0x9908b0df); }
                index = 0;
            }
            int y = state[index++]; y ^= y >>> 11; y ^= (y << 7) & 0x9d2c5680; y ^= (y << 15) & 0xefc60000; y ^= y >>> 18; return y;
        }
        double nextDouble() { long a = nextInt() >>> 5, b = nextInt() >>> 6; return (a * 67108864.0 + b) / 9007199254740992.0; }
    }
}
