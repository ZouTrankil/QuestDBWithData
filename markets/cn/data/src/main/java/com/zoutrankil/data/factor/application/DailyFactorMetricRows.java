package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.domain.FactorDefinition;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/** Canonical one-day base_ic rows, retaining Python raw and effective metrics separately. */
public final class DailyFactorMetricRows {
    private DailyFactorMetricRows() {}

    public static FactorDailyMetricRow map(
            DailyFactorEvaluation.Evaluated evaluated,
            FactorDefinition definition,
            LocalDate maturity,
            String controlVersion,
            String l2SourceLineageDigest) {
        var raw = Objects.requireNonNull(evaluated).raw();
        var identity = raw.identity();
        if (!identity.equals(evaluated.effective().identity())
                || !identity.factorId().equals(definition.factorId())
                || !identity.definitionVersion().equals(definition.version())
                || !"base_ic".equals(identity.evaluationProfile())
                || !(DailyFactorLabels.LEGACY.equals(identity.labelDefinition())
                        || DailyFactorLabels.SUSPENSION_LAST.equals(identity.labelDefinition()))
                || !Objects.requireNonNull(maturity).isAfter(identity.signalDate())
                || Objects.requireNonNull(controlVersion).isBlank())
            throw new IllegalArgumentException("Canonical daily metric identity mismatch");
        if (l2SourceLineageDigest != null && !l2SourceLineageDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid L2 source lineage digest");
        var metric = raw.metrics();
        var pure = evaluated.effective().metrics();
        var deciles = metric.decileMeanReturns();
        var matureAt = maturity.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
        double controls =
                "industry_size".equals(definition.neutralizePolicy())
                        ? evaluated.preprocessing().exposureCoverage()
                        : 1.0;
        return new FactorDailyMetricRow(
                identity.signalDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                identity.universeId(),
                identity.universeVersion(),
                identity.factorId(),
                identity.definitionVersion(),
                identity.labelDefinition(),
                1,
                "base_ic",
                "close_hfq",
                matureAt,
                finite(metric.rankIc()),
                finite(metric.topMinusBottom()),
                metric.win(),
                finite(metric.decileMonotonicity()),
                null,
                finite(deciles.get(0)),
                finite(deciles.get(1)),
                finite(deciles.get(2)),
                finite(deciles.get(3)),
                finite(deciles.get(4)),
                finite(deciles.get(5)),
                finite(deciles.get(6)),
                finite(deciles.get(7)),
                finite(deciles.get(8)),
                finite(deciles.get(9)),
                finite(pure.rankIc()),
                finite(pure.topMinusBottom()),
                pure.win(),
                finite(pure.decileMonotonicity()),
                null,
                finite(controls),
                controlVersion,
                metric.coverage(),
                metric.sampleCount(),
                raw.sampleHash(),
                identity.membershipAsOf().atStartOfDay().toInstant(ZoneOffset.UTC),
                matureAt,
                metric.quality(),
                l2SourceLineageDigest);
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }
}
