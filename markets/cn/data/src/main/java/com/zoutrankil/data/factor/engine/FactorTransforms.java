package com.zoutrankil.data.factor.engine;

import java.util.Objects;

/** Formula-layer transforms; direction and universe normalization are applied later. */
public final class FactorTransforms {
    private FactorTransforms() {}

    public static double[] apply(String transform, double[] source, double[] cleanAmount) {
        Objects.requireNonNull(source);
        double[] result =
                switch (transform) {
                    case "raw", "bool" -> source.clone();
                    case "delta3" ->
                            FactorSeries.combine(
                                    source, FactorSeries.shift(source, 3), (a, b) -> a - b);
                    case "to_clean_amount" ->
                            FactorSeries.combine(
                                    source,
                                    Objects.requireNonNull(cleanAmount, "clean_amount required"),
                                    FactorSeries::ratio);
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported factor transform: " + transform);
                };
        for (int i = 0; i < result.length; i++)
            if (!Double.isFinite(result[i])) result[i] = Double.NaN;
        return result;
    }
}
