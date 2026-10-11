package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.factor.storage.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Fingerprints executed bytecode, including nested helpers, so code changes invalidate cached inputs. */
public final class FactorComputationIdentity {
    private FactorComputationIdentity() {}
    public static String sha256() throws Exception {
        return sha256(new Class<?>[0]);
    }
    public static String sha256(Class<?>... entryPoints) throws Exception {
        var classes=new TreeMap<String,Class<?>>();
        for(var type:List.of(DailyFactorFeatures.class,DailyFactorPanel.class,DailyMarketRiskFeatures.class,
                FactorSeries.class,FactorFormulaEngine.class,FactorTransforms.class,StockCodeAliases.class,
                PointInTimeFactorJoin.class,StructuralFinancialFeatures.class,MarketStateFeatures.class,QuestDbDailyFactorPanel.class,
                QuestDbFinancialIndicators.class,QuestDbFinancialStatements.class,NativeDailyObservations.class,BacktestFactorSource.class,
                GlobalFactorObservations.class,NativePoolFactors.class,StockPublicationScope.class,QuestDbStockMembership.class,
                PointInTimeStockPools.class,PointInTimeStockExposures.class,MultiPoolFactorPipeline.class,QuestDbEventFactors.class,QuestDbMarginFactors.class,
                FactorPreprocessing.class,FactorCrossSection.class,FactorNeutralization.class,
                L2FactorPanel.class,QuestDbL2FactorPanel.class,L2SourceScope.class,L2GlobalObservations.class,L2ObservationBundle.class,
                EtfMarketFeatures.class,EtfMarketPanel.class,EtfDateIndex.class,EtfSharedPanel.class,
                EtfShareFeatures.class,EtfResidualMomentum.class,EtfRecoveryQuality.class,EtfResearchStyle.class,
                EtfMarketInputs.class,QuestDbEtfInputs.class,EtfComponentFeatures.class,EtfComponentPanel.class,EtfConstituentWeights.class,
                EtfEarningsOutlook.class,EtfSelfL2.class,EtfConstituentSnapshot.class,QuestDbEtfConstituents.class,
                EtfConstituentPipeline.class,QuestDbEtfL2.class,QuestDbEtfEarnings.class,EtfRiskContext.class,
                EtfCsiAggregation.class,EtfCsiCrossSection.class,EtfCsiHoldings.class,EtfCsiPipeline.class,
                EtfCsiStyles.class,EtfCsiTemporal.class,QuestDbCsiFinancials.class,QuestDbEtfTechnical.class,EtfPanelObservations.class))collect(type,classes);
        // Callers supply orchestration bytecode without making the application layer depend on the CLI.
        for(var entryPoint:entryPoints)collect(Objects.requireNonNull(entryPoint),classes);
        var digest=MessageDigest.getInstance("SHA-256");
        for(var entry:classes.entrySet()) {
            digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            try(var stream=entry.getValue().getResourceAsStream("/"+entry.getKey().replace('.','/')+".class")) {
                if(stream==null)throw new IllegalStateException("Missing running factor bytecode: "+entry.getKey());
                digest.update(stream.readAllBytes());digest.update((byte)0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void collect(Class<?> type,Map<String,Class<?>> classes) {
        if(classes.putIfAbsent(type.getName(),type)==null)
            for(var nested:type.getDeclaredClasses())collect(nested,classes);
    }
}
