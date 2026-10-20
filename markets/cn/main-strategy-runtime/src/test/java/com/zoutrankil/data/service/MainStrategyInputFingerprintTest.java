package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.derived.application.MarketSentimentDailyJobService;
import com.zoutrankil.data.derived.application.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import com.zoutrankil.data.strategy.input.StrategyInputSchema;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

class MainStrategyInputFingerprintTest {
    private static void field(Object target, String name, Object value) throws Exception {
        var field = MainStrategyDailyPipeline.class.getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    @Test
    void legacyIndependentVectorStaysFixedAndNewIdentityBindsProtocol() throws Exception {
        var pipeline = mock(MainStrategyDailyPipeline.class, CALLS_REAL_METHODS);
        var registry = mock(SyncJobRegistry.class);
        when(registry.definitions()).thenReturn(List.of(MarketSentimentDailyJobService.jobDefinition(), RegimeFeaturesMonitorDailyJobService.jobDefinition()));
        var data = mock(MainStrategyDataService.class);
        when(data.definitionFingerprint()).thenReturn("approved-definition");
        when(data.configuredInputSchema()).thenReturn(StrategyInputSchema.V2);
        field(pipeline,"json",JobDefinitionJson.mapper()); field(pipeline,"owners",List.of()); field(pipeline,"registry",registry);
        field(pipeline,"ledger",Path.of("var/fixture-ledger.sqlite3")); field(pipeline,"targetEtfs",List.of("510300.SH","510500.SH")); field(pipeline,"strategyData",data);
        var day = LocalDate.parse("2026-09-28");
        // 由 a26c4cba 的原 v3 方法独立执行取得，未用新版方法自产预期值。
        var legacy = pipeline.inputFingerprint(day,"3");
        assertEquals("4d368dc0cf223619640503eaf2dee7ac03a570b17516abe7c23c1ee1625635e5", legacy);
        verify(data,never()).configuredInputSchema();
        var standard = pipeline.inputFingerprint(day,"4");
        assertNotEquals(legacy,standard);
        assertEquals(standard,pipeline.inputFingerprint(day));
        when(data.configuredInputSchema()).thenReturn(StrategyInputSchema.V1);
        assertNotEquals(standard,pipeline.inputFingerprint(day,"4"));
        assertEquals(legacy,pipeline.inputFingerprint(day,"3"));
        assertThrows(IllegalArgumentException.class,()->pipeline.inputFingerprint(day,"5"));
    }
}
