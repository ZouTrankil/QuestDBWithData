package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.factor.engine.DailyFactorFeatures;
import com.zoutrankil.data.factor.engine.DailyFactorPanel;
import com.zoutrankil.data.factor.engine.DailyMarketRiskFeatures;
import com.zoutrankil.data.factor.engine.EtfComponentFeatures;
import com.zoutrankil.data.factor.engine.EtfComponentPanel;
import com.zoutrankil.data.factor.engine.EtfConstituentPipeline;
import com.zoutrankil.data.factor.engine.EtfConstituentSnapshot;
import com.zoutrankil.data.factor.engine.EtfConstituentWeights;
import com.zoutrankil.data.factor.engine.EtfCsiAggregation;
import com.zoutrankil.data.factor.engine.EtfCsiCrossSection;
import com.zoutrankil.data.factor.engine.EtfCsiHoldings;
import com.zoutrankil.data.factor.engine.EtfCsiPipeline;
import com.zoutrankil.data.factor.engine.EtfCsiStyles;
import com.zoutrankil.data.factor.engine.EtfCsiTemporal;
import com.zoutrankil.data.factor.engine.EtfDateIndex;
import com.zoutrankil.data.factor.engine.EtfEarningsOutlook;
import com.zoutrankil.data.factor.engine.EtfMarketFeatures;
import com.zoutrankil.data.factor.engine.EtfMarketInputs;
import com.zoutrankil.data.factor.engine.EtfMarketPanel;
import com.zoutrankil.data.factor.engine.EtfRecoveryQuality;
import com.zoutrankil.data.factor.engine.EtfResearchStyle;
import com.zoutrankil.data.factor.engine.EtfResidualMomentum;
import com.zoutrankil.data.factor.engine.EtfRiskContext;
import com.zoutrankil.data.factor.engine.EtfSelfL2;
import com.zoutrankil.data.factor.engine.EtfShareFeatures;
import com.zoutrankil.data.factor.engine.EtfSharedPanel;
import com.zoutrankil.data.factor.engine.FactorCrossSection;
import com.zoutrankil.data.factor.engine.FactorFormulaEngine;
import com.zoutrankil.data.factor.engine.FactorNeutralization;
import com.zoutrankil.data.factor.engine.FactorPreprocessing;
import com.zoutrankil.data.factor.engine.FactorSeries;
import com.zoutrankil.data.factor.engine.FactorTransforms;
import com.zoutrankil.data.factor.engine.L2FactorPanel;
import com.zoutrankil.data.factor.engine.MarketStateFeatures;
import com.zoutrankil.data.factor.engine.MultiPoolFactorPipeline;
import com.zoutrankil.data.factor.engine.PointInTimeFactorJoin;
import com.zoutrankil.data.factor.engine.PointInTimeStockExposures;
import com.zoutrankil.data.factor.engine.PointInTimeStockPools;
import com.zoutrankil.data.factor.engine.StockCodeAliases;
import com.zoutrankil.data.factor.engine.StructuralFinancialFeatures;
import com.zoutrankil.data.factor.storage.BacktestFactorSource;
import com.zoutrankil.data.factor.storage.QuestDbCsiFinancials;
import com.zoutrankil.data.factor.storage.QuestDbDailyFactorPanel;
import com.zoutrankil.data.factor.storage.QuestDbEtfConstituents;
import com.zoutrankil.data.factor.storage.QuestDbEtfEarnings;
import com.zoutrankil.data.factor.storage.QuestDbEtfInputs;
import com.zoutrankil.data.factor.storage.QuestDbEtfL2;
import com.zoutrankil.data.factor.storage.QuestDbEtfTechnical;
import com.zoutrankil.data.factor.storage.QuestDbEventFactors;
import com.zoutrankil.data.factor.storage.QuestDbFinancialIndicators;
import com.zoutrankil.data.factor.storage.QuestDbFinancialStatements;
import com.zoutrankil.data.factor.storage.QuestDbL2FactorPanel;
import com.zoutrankil.data.factor.storage.QuestDbMarginFactors;
import com.zoutrankil.data.factor.storage.QuestDbStockMembership;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Fingerprints executed bytecode, including nested helpers, so code changes invalidate cached inputs. */
public final class FactorComputationIdentity {
    private FactorComputationIdentity() {}

    public static String sha256() throws Exception {
        return sha256(new Class<?>[0]);
    }

    public static String sha256(Class<?>... entryPoints) throws Exception {
        var classes = new TreeMap<String, Class<?>>();
        for (var type :
                List.of(
                        DailyFactorFeatures.class,
                        DailyFactorPanel.class,
                        DailyMarketRiskFeatures.class,
                        FactorSeries.class,
                        FactorFormulaEngine.class,
                        FactorTransforms.class,
                        StockCodeAliases.class,
                        PointInTimeFactorJoin.class,
                        StructuralFinancialFeatures.class,
                        MarketStateFeatures.class,
                        QuestDbDailyFactorPanel.class,
                        QuestDbFinancialIndicators.class,
                        QuestDbFinancialStatements.class,
                        NativeDailyObservations.class,
                        BacktestFactorSource.class,
                        BacktestDailyWindowVerifier.class,
                        BacktestDailyMaterializedViewPort.class,
                        BacktestDailyScope.class,
                        GlobalFactorObservations.class,
                        NativePoolFactors.class,
                        StockPublicationScope.class,
                        QuestDbStockMembership.class,
                        PointInTimeStockPools.class,
                        PointInTimeStockExposures.class,
                        MultiPoolFactorPipeline.class,
                        QuestDbEventFactors.class,
                        QuestDbMarginFactors.class,
                        FactorPreprocessing.class,
                        FactorCrossSection.class,
                        FactorNeutralization.class,
                        L2FactorPanel.class,
                        QuestDbL2FactorPanel.class,
                        L2SourceScope.class,
                        L2GlobalObservations.class,
                        L2ObservationBundle.class,
                        EtfMarketFeatures.class,
                        EtfMarketPanel.class,
                        EtfDateIndex.class,
                        EtfSharedPanel.class,
                        EtfShareFeatures.class,
                        EtfResidualMomentum.class,
                        EtfRecoveryQuality.class,
                        EtfResearchStyle.class,
                        EtfMarketInputs.class,
                        QuestDbEtfInputs.class,
                        EtfComponentFeatures.class,
                        EtfComponentPanel.class,
                        EtfConstituentWeights.class,
                        EtfEarningsOutlook.class,
                        EtfSelfL2.class,
                        EtfConstituentSnapshot.class,
                        QuestDbEtfConstituents.class,
                        EtfConstituentPipeline.class,
                        QuestDbEtfL2.class,
                        QuestDbEtfEarnings.class,
                        EtfRiskContext.class,
                        EtfCsiAggregation.class,
                        EtfCsiCrossSection.class,
                        EtfCsiHoldings.class,
                        EtfCsiPipeline.class,
                        EtfCsiStyles.class,
                        EtfCsiTemporal.class,
                        QuestDbCsiFinancials.class,
                        QuestDbEtfTechnical.class,
                        EtfPanelObservations.class)) collect(type, classes);
        // Callers supply orchestration bytecode without making the application layer depend on the
        // CLI.
        for (var entryPoint : entryPoints) collect(Objects.requireNonNull(entryPoint), classes);
        var digest = MessageDigest.getInstance("SHA-256");
        for (var entry : classes.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (var stream =
                    entry.getValue()
                            .getResourceAsStream(
                                    "/" + entry.getKey().replace('.', '/') + ".class")) {
                if (stream == null)
                    throw new IllegalStateException(
                            "Missing running factor bytecode: " + entry.getKey());
                digest.update(stream.readAllBytes());
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void collect(Class<?> type, Map<String, Class<?>> classes) {
        if (classes.putIfAbsent(type.getName(), type) == null)
            for (var nested : type.getDeclaredClasses()) collect(nested, classes);
    }
}
