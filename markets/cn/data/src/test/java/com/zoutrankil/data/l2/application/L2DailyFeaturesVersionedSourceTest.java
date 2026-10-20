package com.zoutrankil.data.l2.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.l2.mapper.L2DailyFeaturesMapper;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class L2DailyFeaturesVersionedSourceTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void originalDefinitionDrivesRealRunnerAndSqliteWithoutChangingRawSource(int version)
            throws Exception {
        var def = L2DailyFeaturesJobService.definition(version);
        var inspection =
                (L2DailyFeaturesParquetSource.Inspection)
                        L2ManifestDailySourceContractTest.inspection(true);
        var source = mock(L2DailyFeaturesParquetSource.class);
        when(source.sourceRootIdentity()).thenReturn(L2ManifestDailySourceContractTest.ROOT);
        when(source.inspect(any(), any(), anyList(), anyInt(), anyInt())).thenReturn(inspection);
        var raw = L2ManifestDailySourceContractTest.stream(true).get(1).path("rows").get(0);
        // 这是写入成功夹具，布尔状态明确已知；原 NULL 来源解析用例不改。
        raw = raw.deepCopy();
        for (String flag : List.of("has_deal", "has_order", "has_snapshot"))
            ((ObjectNode) raw).put(flag, false);
        var row = new L2DailyFeaturesMapper().fromParquet(raw, inspection.from());
        when(source.stream(any(), anyList(), anyInt(), anyInt(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            SyncJobRunner.PageConsumer<L2DailyFeatures> consumer =
                                    call.getArgument(4);
                            consumer.accept(
                                    new SyncJobRunner.Page<>(
                                            List.of(row),
                                            "f".repeat(64),
                                            "{\"receipt\":true}",
                                            "page-1"));
                            return new SyncJobRunner.SourceCompletion(
                                    1, 1, true, "{\"complete\":true}");
                        });
        var writer = mock(L2DailyFeaturesWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(version);
        when(writer.codec())
                .thenReturn(
                        L2DailyFeaturesWriteSession.codec(CnTradeDateLayout.fromVersion(version)));
        when(writer.walSettled()).thenReturn(true);
        when(writer.stableVersion()).thenReturn("wal:1");
        when(writer.countRows(any(), anyList())).thenReturn(1);
        when(writer.uncertainSenderStopped()).thenReturn(true);
        when(writer.readback(anyList())).thenReturn(List.of(row));
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("source_root_id", L2ManifestDailySourceContractTest.ROOT);
        if (version == 2)
            parameters.put(
                    "source_inspection", JobDefinitionJson.mapper().writeValueAsString(inspection));
        var request =
                def.freeze(
                        SyncJobDefinition.Mode.BACKFILL,
                        parameters,
                        inspection.from(),
                        inspection.to(),
                        inspection.to());
        var adapter = new L2DailyFeaturesSyncAdapter(source, inspection, List.of(), def, writer);
        var path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        var result =
                new SyncJobRunner<L2DailyFeatures, L2DailyFeaturesKey>(
                                ledger, new DatasetIntervalLock(path))
                        .run(
                                "run-" + version,
                                null,
                                "questdb-" + "a".repeat(64),
                                request,
                                adapter,
                                () -> false);
        assertEquals(SyncRunState.VERIFIED, result.state());
        verify(writer).send(List.of(row));
        verify(source).inspect(inspection.from(), inspection.to(), List.of(), 300000, 25000);
        assertEquals(
                SyncRequestIdentity.snapshotJson(request),
                ledger.getRun(result.runId()).frozenJson());
        assertEquals(version, ledger.getRun(result.runId()).jobVersion());
        var otherParameters = new LinkedHashMap<String, Object>();
        otherParameters.put("source_root_id", L2ManifestDailySourceContractTest.ROOT);
        if (version == 1)
            otherParameters.put(
                    "source_inspection", JobDefinitionJson.mapper().writeValueAsString(inspection));
        assertNotEquals(
                SyncRequestIdentity.fingerprint(request, "target"),
                SyncRequestIdentity.fingerprint(
                        L2DailyFeaturesJobService.definition(3 - version)
                                .freeze(
                                        request.mode(),
                                        otherParameters,
                                        request.from(),
                                        request.to(),
                                        request.logicalDate()),
                        "target"));
    }

    @Test
    void newFrozenInspectionCannotBeReplacedOrOmittedBeforeIo() throws Exception {
        var definition = L2DailyFeaturesJobService.definition(2);
        var inspection =
                (L2DailyFeaturesParquetSource.Inspection)
                        L2ManifestDailySourceContractTest.inspection(true);
        var source = mock(L2DailyFeaturesParquetSource.class);
        var writer = mock(L2DailyFeaturesWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(2);
        var adapter =
                new L2DailyFeaturesSyncAdapter(source, inspection, List.of(), definition, writer);
        var day = inspection.from();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        definition.freeze(
                                SyncJobDefinition.Mode.BACKFILL,
                                Map.of("source_root_id", L2ManifestDailySourceContractTest.ROOT),
                                day,
                                day,
                                day));
        var request =
                definition.freeze(
                        SyncJobDefinition.Mode.BACKFILL,
                        Map.of(
                                "source_root_id",
                                L2ManifestDailySourceContractTest.ROOT,
                                "source_inspection",
                                "{}"),
                        day,
                        day,
                        day);
        assertThrows(IllegalStateException.class, () -> adapter.preflight(request));
        verifyNoInteractions(source);
        verify(writer, never()).preflight();
    }

    @Test
    void v2NeverAdoptsUnversionedSessionOrOtherFrozenDefinition() throws Exception {
        var source = mock(L2DailyFeaturesParquetSource.class);
        var inspection = mock(L2DailyFeaturesParquetSource.Inspection.class);
        var unversioned = mock(L2DailyFeaturesWriteSession.class);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new L2DailyFeaturesSyncAdapter(
                                source,
                                inspection,
                                List.of(),
                                L2DailyFeaturesJobService.definition(2),
                                unversioned));
        var writer = mock(L2DailyFeaturesWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(2);
        var adapter =
                new L2DailyFeaturesSyncAdapter(
                        source,
                        inspection,
                        List.of(),
                        L2DailyFeaturesJobService.definition(2),
                        writer);
        var day = L2ManifestDailySourceContractTest.DAY;
        var old =
                L2DailyFeaturesJobService.definition()
                        .freeze(
                                SyncJobDefinition.Mode.BACKFILL,
                                Map.of("source_root_id", L2ManifestDailySourceContractTest.ROOT),
                                day,
                                day,
                                day);
        assertThrows(IllegalArgumentException.class, () -> adapter.preflight(old));
        verifyNoInteractions(source, inspection, unversioned);
        verify(writer, never()).preflight();
    }
}
