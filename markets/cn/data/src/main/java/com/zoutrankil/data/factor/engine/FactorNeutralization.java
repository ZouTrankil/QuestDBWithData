package com.zoutrankil.data.factor.engine;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

/** Industry/size OLS projection inside one frozen date, factor and universe. */
public final class FactorNeutralization {
    private FactorNeutralization() {}

    public record Result(
            double[] values, String[] status, int sampleCount, double exposureCoverage) {}

    /** One sequential pipeline batch owns this cache; never share it across workers.
     * Exact exposure keys include the valid sample, so pool/date names cannot hide
     * changed exposures or factor-specific missing values. Close after each batch.
     */
    public static final class ProjectionCache implements AutoCloseable {
        private final int capacity;
        private final LinkedHashMap<List<Exposure>, double[][]> entries =
                new LinkedHashMap<>(16, 0.75f, true);
        private long builds, hits;

        public ProjectionCache(int capacity) {
            if (capacity < 1 || capacity > 64)
                throw new IllegalArgumentException("Cache capacity must be 1..64");
            this.capacity = capacity;
        }

        public long builds() {
            return builds;
        }

        public long hits() {
            return hits;
        }

        @Override
        public void close() {
            entries.clear();
        }

        private double[][] get(List<Exposure> key) {
            double[][] result = entries.get(key);
            if (result != null) hits++;
            return result;
        }

        private void put(List<Exposure> key, double[][] basis) {
            if (entries.size() == capacity) entries.remove(entries.keySet().iterator().next());
            entries.put(List.copyOf(key), basis);
            builds++;
        }
    }

    private record Exposure(String symbol, double size, String industry) {}

    public static Result residualize(
            String[] symbols,
            double[] values,
            double[] logMarketCap,
            String[] industry,
            int minimumSample) {
        return residualize(symbols, values, logMarketCap, industry, minimumSample, null);
    }

    public static Result residualize(
            String[] symbols,
            double[] values,
            double[] logMarketCap,
            String[] industry,
            int minimumSample,
            ProjectionCache cache) {
        int n = values.length;
        if (symbols.length != n
                || logMarketCap.length != n
                || industry.length != n
                || minimumSample < 1)
            throw new IllegalArgumentException(
                    "Aligned cross-section and positive minimum sample required");
        double[] output = new double[n];
        Arrays.fill(output, Double.NaN);
        String[] status = new String[n];
        Arrays.fill(status, "insufficient_exposure");
        var sample = new ArrayList<Integer>();
        var industries = new TreeSet<String>();
        var distinctSymbols = new HashSet<String>();
        for (int i = 0; i < n; i++) {
            if (symbols[i] == null || !distinctSymbols.add(symbols[i]))
                throw new IllegalArgumentException(
                        "Unique security per frozen cross-section required");
            if (Double.isFinite(values[i])
                    && Double.isFinite(logMarketCap[i])
                    && industry[i] != null) {
                sample.add(i);
                industries.add(industry[i]);
            }
        }
        int count = sample.size();
        double coverage = n == 0 ? 0 : (double) count / n;
        if (count < minimumSample || industries.size() < 2)
            return new Result(output, status, count, coverage);
        sample.sort(Comparator.comparing(i -> symbols[i]));
        List<String> categories = List.copyOf(industries);
        int columns = 1 + categories.size();
        var key = new ArrayList<Exposure>(count);
        double[] y = new double[count];
        for (int row = 0; row < count; row++) {
            int i = sample.get(row);
            key.add(new Exposure(symbols[i], logMarketCap[i], industry[i]));
            y[row] = values[i];
        }
        double[][] basis = cache == null ? null : cache.get(key);
        if (basis == null) {
            double meanSize = 0;
            for (int i : sample) meanSize += logMarketCap[i];
            meanSize /= count;
            double[][] design = new double[count][columns];
            for (int row = 0; row < count; row++) {
                int i = sample.get(row);
                design[row][0] = 1;
                design[row][1] = logMarketCap[i] - meanSize;
                for (int c = 1; c < categories.size(); c++)
                    design[row][c + 1] = categories.get(c).equals(industry[i]) ? 1 : 0;
            }
            var svd = new SingularValueDecomposition(new Array2DRowRealMatrix(design, false));
            double[] singular = svd.getSingularValues();
            var u = svd.getU();
            double cutoff =
                    singular.length == 0
                            ? 0
                            : Math.ulp(1.0) * Math.max(count, columns) * singular[0];
            int rank = 0;
            for (double value : singular) if (value > cutoff) rank++;
            basis = new double[rank][count];
            int retained = 0;
            for (int c = 0; c < singular.length; c++)
                if (singular[c] > cutoff) {
                    for (int row = 0; row < count; row++) basis[retained][row] = u.getEntry(row, c);
                    retained++;
                }
            if (cache != null) cache.put(key, basis);
        }
        double[] fitted = new double[count];
        // Store only the retained column basis: O(sample * rank), never sample squared.
        for (double[] column : basis) {
            double coefficient = 0;
            for (int row = 0; row < count; row++) coefficient += column[row] * y[row];
            for (int row = 0; row < count; row++) fitted[row] += column[row] * coefficient;
        }
        double[] residual = new double[count];
        double mean = 0;
        for (int row = 0; row < count; row++) {
            residual[row] = y[row] - fitted[row];
            mean += residual[row];
        }
        mean /= count;
        double variance = 0;
        for (double value : residual) {
            double delta = value - mean;
            variance += delta * delta;
        }
        double std = Math.sqrt(variance / count);
        Arrays.fill(status, "missing_exposure");
        for (int row = 0; row < count; row++) {
            int i = sample.get(row);
            output[i] = Double.isFinite(std) && std > 0 ? residual[row] / std : residual[row];
            status[i] = "complete";
        }
        return new Result(output, status, count, coverage);
    }
}
