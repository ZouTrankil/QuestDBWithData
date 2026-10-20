package com.zoutrankil.data.factor.engine;

import com.zoutrankil.data.factor.domain.FactorDefinition;

import java.util.Arrays;

/** Named intermediate values preserve raw formulas and apply direction once, at the end. */
public final class FactorPreprocessing {
    private FactorPreprocessing() {}

    public record Result(
            double[] raw,
            double[] rank,
            double[] zscore,
            double[] neutralized,
            double[] effective,
            String[] status,
            int sampleCount,
            double outlierFraction,
            int exposureSampleCount,
            double exposureCoverage,
            String recipe) {}

    public static Result compute(
            FactorDefinition definition,
            String[] symbols,
            double[] raw,
            double[] logMarketCap,
            String[] industry) {
        return compute(definition, symbols, raw, logMarketCap, industry, null);
    }

    public static Result compute(
            FactorDefinition definition,
            String[] symbols,
            double[] raw,
            double[] logMarketCap,
            String[] industry,
            FactorNeutralization.ProjectionCache cache) {
        if (symbols.length != raw.length)
            throw new IllegalArgumentException("Misaligned factor cross-section");
        String normalize =
                switch (definition.normalizePolicy()) {
                    case "none" -> "none";
                    case "rank", "cross_section_rank" -> "rank";
                    case "robust_zscore", "cross_section_robust_zscore" -> "robust_zscore";
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported normalization: " + definition.normalizePolicy());
                };
        String neutralize = definition.neutralizePolicy();
        if (!neutralize.equals("none") && !neutralize.equals("industry_size"))
            throw new IllegalArgumentException("Unsupported neutralization: " + neutralize);
        if (neutralize.equals("industry_size") && !normalize.equals("robust_zscore"))
            throw new IllegalArgumentException(
                    "Industry/size neutralization requires robust z-score");
        double[] cleaned = raw.clone();
        for (int i = 0; i < cleaned.length; i++)
            if (!Double.isFinite(cleaned[i])) cleaned[i] = Double.NaN;
        var normalized = FactorCrossSection.normalize(cleaned, normalize);
        double[] rank = missing(raw.length),
                zscore = missing(raw.length),
                neutralized = missing(raw.length);
        if (normalize.equals("rank")) rank = normalized.values();
        if (normalize.equals("robust_zscore")) zscore = normalized.values();
        double[] effective = normalized.values().clone();
        String[] status = new String[raw.length];
        for (int i = 0; i < status.length; i++)
            status[i] =
                    Double.isFinite(effective[i]) ? "ready" : "blocked_insufficient_cross_section";
        int exposureSample = 0;
        double coverage = Double.NaN;
        String kind =
                normalize.equals("none") ? "raw" : normalize.equals("rank") ? "rank" : "zscore";
        if (neutralize.equals("industry_size")) {
            if (logMarketCap == null || industry == null)
                throw new IllegalArgumentException("Industry and size exposures required");
            var residual =
                    FactorNeutralization.residualize(
                            symbols, effective, logMarketCap, industry, 20, cache);
            neutralized = residual.values();
            effective = neutralized.clone();
            kind = "neutralized";
            exposureSample = residual.sampleCount();
            coverage = residual.exposureCoverage();
            for (int i = 0; i < status.length; i++) {
                if (residual.status()[i].equals("missing_exposure"))
                    status[i] = "blocked_missing_exposure";
                else if (residual.status()[i].equals("insufficient_exposure"))
                    status[i] = "blocked_insufficient_exposure";
            }
        }
        for (int i = 0; i < effective.length; i++) effective[i] *= definition.directionSign();
        String recipe =
                "factor_preprocess_v1|outlier="
                        + (normalize.equals("robust_zscore") ? "quantile_clip_1_99" : "none")
                        + "|normalize="
                        + normalize
                        + "|neutralize="
                        + neutralize
                        + "|effective="
                        + kind;
        return new Result(
                cleaned,
                rank,
                zscore,
                neutralized,
                effective,
                status,
                normalized.sampleCount(),
                normalized.clippedFraction(),
                exposureSample,
                coverage,
                recipe);
    }

    private static double[] missing(int count) {
        double[] result = new double[count];
        Arrays.fill(result, Double.NaN);
        return result;
    }
}
