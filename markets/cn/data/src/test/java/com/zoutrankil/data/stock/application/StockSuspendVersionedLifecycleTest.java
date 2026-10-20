package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Prepared;
import com.zoutrankil.data.stock.domain.StockSuspendState.Range;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;
import com.zoutrankil.data.stock.domain.StockSuspendState.Verified;
import com.zoutrankil.data.stock.mapper.StockSuspendLayoutMapper;
import com.zoutrankil.data.stock.port.StockSuspendStagingPort;
import com.zoutrankil.data.stock.port.StockSuspendTables;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.data.stock.storage.StockSuspendStaging;
import com.zoutrankil.data.stock.storage.StockSuspendStorage;
import com.zoutrankil.data.stock.storage.StockSuspendWritePort;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实来源解析、执行器、原件及 SQLite；外部请求和物理存储由有界内存夹具替代。 */
class StockSuspendVersionedLifecycleTest {
    @TempDir Path root;
    private static final String TABLE = "java_d011_stk_suspend_runtime";
    private static final String PHYSICAL = "static-v2-" + String.format("%064x", 1);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void appendProofAdvancesSameVersionCheckpointForEmptyAndNonemptySource(
            int version, boolean nonempty) throws Exception {
        var h = new Harness(root.resolve("ledger.sqlite"));
        h.nonempty.set(nonempty);
        var service = h.service(version);
        var plan = service.planDetailed(Mode.INCREMENTAL, DAY, DAY, DAY);
        assertEquals(version, plan.request().definition().datasetVersion());
        assertFalse(Files.exists(h.path));
        var result = service.run(plan.request());
        assertEquals(
                nonempty ? SyncRunState.VERIFIED : SyncRunState.VERIFIED_EMPTY,
                result.state(),
                SyncRunLedger.openReadOnly(h.path).get(result.runId()).payloadJson());
        assertEquals(
                DAY,
                StockSuspendCoverage.checkpoint(h.path, h.logical(version), version)
                        .orElseThrow()
                        .through());
        assertTrue(
                StockSuspendCoverage.checkpoint(h.path, h.logical(version), 3 - version).isEmpty());
        var complete =
                root.resolve("sync-evidence")
                        .resolve(result.runId())
                        .resolve("complete-" + result.runId() + ".json");
        var proof = JobDefinitionJson.mapper().readTree(complete.toFile());
        assertTrue(proof.path("walSettled").asBoolean());
        assertFalse(proof.path("publication").path("replacementPublished").asBoolean(true));
        assertEquals(
                nonempty ? 1 : 0,
                proof.path("publication").path("verification").path("matchedRows").asInt(-1));
        assertEquals(version == 1 ? -1 : 2, proof.path("datasetSchemaVersion").asInt(-1));
        verify(h.target(version), never()).newStaging();
        assertNull(
                new DatasetIntervalLock(h.path)
                        .findOwned(
                                result.runId(),
                                new DatasetIntervalLock.Scope("stk_suspend", DAY, DAY)));
    }

    @Test
    void sameLogicalIdentityCannotJoinAnotherVersionsCheckpoint() throws Exception {
        var h = new Harness(root.resolve("shared-id.sqlite"));
        h.sameLogical = true;
        var first = h.service(1).run(h.service(1).plan(Mode.INCREMENTAL, DAY, DAY, DAY));
        assertEquals(SyncRunState.VERIFIED, first.state());
        assertTrue(StockSuspendCoverage.checkpoint(h.path, h.logical(1), 2).isEmpty());
        var next = DAY.plusDays(1);
        var second = h.service(2).run(h.service(2).plan(Mode.INCREMENTAL, next, next, next));
        assertEquals(SyncRunState.VERIFIED, second.state());
        assertEquals(
                DAY,
                StockSuspendCoverage.checkpoint(h.path, h.logical(1), 1).orElseThrow().through());
        assertEquals(
                next,
                StockSuspendCoverage.checkpoint(h.path, h.logical(2), 2).orElseThrow().through());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void resumeAndReadOnlyRevalidationUseOriginalVersionAfterCurrentSelectionChanges(int original)
            throws Exception {
        var h = new Harness(root.resolve("resume.sqlite"));
        var plan = h.service(original).plan(Mode.BACKFILL, DAY, DAY, DAY);
        h.preflightFails.set(true);
        var failed = h.service(original).run(plan);
        assertEquals(SyncRunState.FAILED, failed.state());
        var ledger = SyncRunLedger.openReadOnly(h.path);
        String frozen = ledger.getRun(failed.runId()).frozenJson();
        h.preflightFails.set(false);
        clearInvocations(h.target(3 - original));
        var current = h.service(3 - original);
        var result = current.resume(failed.runId());
        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(original, ledger.getRun(result.runId()).jobVersion());
        assertEquals(frozen, ledger.getRun(result.runId()).frozenJson());
        assertEquals(frozen, ledger.getRun(failed.runId()).frozenJson());
        verify(h.target(3 - original), never()).newWriter(anyString());
        String before = FileEvidenceStore.sha256(Files.readAllBytes(h.path));
        String recheck = current.revalidate(result.runId());
        assertTrue(Files.isRegularFile(Path.of(recheck)));
        assertEquals(before, FileEvidenceStore.sha256(Files.readAllBytes(h.path)));
        verify(h.writers.getLast(), never()).send(anyList());
        verify(h.target(original), never()).newStaging();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void emptyAppendCannotCompleteWhileWalIsUnresolved(int version) throws Exception {
        var h = new Harness(root.resolve("wal.sqlite"));
        h.nonempty.set(false);
        h.walReady.set(false);
        var result =
                h.service(version).run(h.service(version).plan(Mode.INCREMENTAL, DAY, DAY, DAY));
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        var lease =
                new DatasetIntervalLock(h.path)
                        .findOwned(
                                result.runId(),
                                new DatasetIntervalLock.Scope("stk_suspend", DAY, DAY));
        assertNotNull(lease);
        assertTrue(lease.inDoubt());
        assertTrue(StockSuspendCoverage.checkpoint(h.path, h.logical(version), version).isEmpty());
        assertFalse(
                Files.exists(
                        root.resolve("sync-evidence")
                                .resolve(result.runId())
                                .resolve("complete-" + result.runId() + ".json")));
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void replacementUsesFrozenLayoutAndPreservesCheckpointAfterPublicationRecheck(
            int version, boolean nonempty) throws Exception {
        var h = new Harness(root.resolve("replacement.sqlite"));
        var outsideBefore =
                new StockSuspend(new StockSuspendKey("000001.SZ", DAY.minusDays(1)), 1L);
        var revoked = new StockSuspend(new StockSuspendKey("000002.SZ", DAY), 1L);
        var outsideAfter = new StockSuspend(new StockSuspendKey("000003.SZ", DAY.plusDays(1)), 1L);
        h.values.get(version).addAll(List.of(outsideBefore, revoked, outsideAfter));
        h.nonempty.set(nonempty);
        var service = h.service(version);
        var request = service.plan(Mode.INCREMENTAL, DAY, DAY.plusDays(1), DAY.plusDays(1));
        var result = service.run(request);
        assertEquals(
                nonempty ? SyncRunState.VERIFIED : SyncRunState.VERIFIED_EMPTY,
                result.state(),
                SyncRunLedger.openReadOnly(h.path).get(result.runId()).payloadJson());
        assertEquals(2, h.identities.get(version));
        assertFalse(h.values.get(version).contains(revoked));
        assertTrue(h.values.get(version).contains(outsideBefore));
        assertEquals(
                DAY.plusDays(1),
                StockSuspendCoverage.checkpoint(h.path, h.logical(version), version)
                        .orElseThrow()
                        .through());
        if (version == 2) {
            String frozen = SyncRunLedger.openReadOnly(h.path).getRun(result.runId()).frozenJson();
            var evidence = root.resolve("sync-evidence").resolve(result.runId());
            var preserved = new HashMap<Path, byte[]>();
            try (var paths = Files.list(evidence)) {
                for (var path :
                        paths.filter(
                                        p ->
                                                p.getFileName().toString().startsWith("page-")
                                                        || p.getFileName()
                                                                .toString()
                                                                .startsWith("complete-"))
                                .toList()) preserved.put(path, Files.readAllBytes(path));
            }
            h.service(1).finishPublication(result.runId(), true);
            for (var entry : preserved.entrySet())
                assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
            assertEquals(
                    frozen, SyncRunLedger.openReadOnly(h.path).getRun(result.runId()).frozenJson());
            assertEquals(
                    DAY.plusDays(1),
                    StockSuspendCoverage.checkpoint(h.path, h.logical(version), version)
                            .orElseThrow()
                            .through());
        }
    }

    @Test
    void revalidationRejectsAnUnexplainedPhysicalGenerationEvenWhenRowsAreIdentical()
            throws Exception {
        var h = new Harness(root.resolve("physical.sqlite"));
        var result = h.service(1).run(h.service(1).plan(Mode.INCREMENTAL, DAY, DAY, DAY));
        assertEquals(SyncRunState.VERIFIED, result.state());
        h.identities.put(1, 2L);
        clearInvocations(h.pages);
        String before = FileEvidenceStore.sha256(Files.readAllBytes(h.path));
        assertThrows(IllegalStateException.class, () -> h.service(2).revalidate(result.runId()));
        assertEquals(before, FileEvidenceStore.sha256(Files.readAllBytes(h.path)));
        verifyNoInteractions(h.pages);
    }

    @Test
    void revalidationDoesNotRebindWhenGenerationChangesAfterTheControllerCheck() throws Exception {
        var h = new Harness(root.resolve("race.sqlite"));
        var result = h.service(1).run(h.service(1).plan(Mode.INCREMENTAL, DAY, DAY, DAY));
        assertEquals(SyncRunState.VERIFIED, result.state());
        when(h.target(1).physicalTargetId()).thenReturn(PHYSICAL, Harness.physical(2));
        clearInvocations(h.pages);
        assertThrows(IllegalStateException.class, () -> h.service(2).revalidate(result.runId()));
        verifyNoInteractions(h.pages);
    }

    @Test
    void wrongVersionNewRunAndUnresolvedResumeStopBeforeAnyWriterOrSource() throws Exception {
        var h = new Harness(root.resolve("unsafe.sqlite"));
        var request = h.service(2).plan(Mode.BACKFILL, DAY, DAY, DAY);
        assertThrows(IllegalArgumentException.class, () -> h.service(1).run(request));
        assertFalse(Files.exists(h.path));
        var ledger = new SyncRunLedger(h.path);
        ledger.createRun("pending", null, h.logical(2), request);
        ledger.transition("pending", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("pending", 1, SyncRunState.IN_DOUBT, "{}");
        String frozen = ledger.getRun("pending").frozenJson();
        assertThrows(IllegalStateException.class, () -> h.service(1).restorePlan("pending"));
        assertThrows(IllegalStateException.class, () -> h.service(1).resume(request, "pending"));
        assertEquals(frozen, ledger.getRun("pending").frozenJson());
        verify(h.target(1), never()).newWriter(anyString());
        verify(h.target(2), never()).newWriter(anyString());
        verifyNoInteractions(h.pages);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void incompleteAppendProofCannotBeAdoptedOrRewrittenAsCheckpoint(int version) throws Exception {
        var h = new Harness(root.resolve("missing-proof.sqlite"));
        var result =
                h.service(version).run(h.service(version).plan(Mode.INCREMENTAL, DAY, DAY, DAY));
        assertEquals(SyncRunState.VERIFIED, result.state());
        Path complete =
                root.resolve("sync-evidence")
                        .resolve(result.runId())
                        .resolve("complete-" + result.runId() + ".json");
        var proof = (ObjectNode) JobDefinitionJson.mapper().readTree(complete.toFile());
        proof.remove("walSettled");
        JobDefinitionJson.mapper().writeValue(complete.toFile(), proof);
        byte[] original = Files.readAllBytes(complete);
        assertThrows(
                IllegalStateException.class,
                () -> StockSuspendCoverage.checkpoint(h.path, h.logical(version), version));
        assertArrayEquals(original, Files.readAllBytes(complete));
    }

    private static final class Harness {
        final Path path;
        final SyncJobRegistry jobs = mock(SyncJobRegistry.class);
        final TusharePageService pages = mock(TusharePageService.class);
        final Map<Integer, StockSuspendTarget.Versioned> targets = new HashMap<>();
        final Map<Integer, List<StockSuspend>> values = new HashMap<>();
        final Map<Integer, Long> identities = new HashMap<>();
        final Map<Integer, Boolean> present = new HashMap<>();
        final Map<Integer, Map<String, Snapshot>> extras = new HashMap<>();
        final List<StockSuspendWriteSession.Versioned> writers = new ArrayList<>();
        final AtomicBoolean nonempty = new AtomicBoolean(true);
        final AtomicBoolean walReady = new AtomicBoolean(true);
        final AtomicBoolean preflightFails = new AtomicBoolean(false);
        boolean sameLogical;

        Harness(Path path) throws Exception {
            this.path = path;
            for (int v : List.of(1, 2)) {
                targets.put(v, mock(StockSuspendTarget.Versioned.class));
                values.put(v, new ArrayList<>());
                identities.put(v, 1L);
                present.put(v, true);
                extras.put(v, new HashMap<>());
            }
            for (int v : List.of(1, 2)) {
                var target = target(v);
                var tables = mock(StockSuspendTables.Versioned.class);
                when(tables.datasetVersion()).thenReturn(v);
                when(tables.logicalTargetId(TABLE)).thenAnswer(i -> logical(v));
                when(tables.openTable(anyString())).thenAnswer(i -> table(v, i.getArgument(0)));
                when(tables.snapshotIfPresent(anyString()))
                        .thenAnswer(i -> optional(v, i.getArgument(0)));
                when(tables.prepare(any(), any(), anyList(), any(), any()))
                        .thenAnswer(
                                i ->
                                        StockSuspendStaging.prepare(
                                                i.getArgument(0),
                                                i.getArgument(1),
                                                i.getArgument(2),
                                                i.getArgument(3),
                                                i.getArgument(4)));
                when(tables.physicalTargetId(anyString(), any()))
                        .thenAnswer(i -> physical(((Identity) i.getArgument(1)).id()));
                doAnswer(
                                i -> {
                                    rename(v, i.getArgument(0), i.getArgument(1));
                                    return null;
                                })
                        .when(tables)
                        .rename(anyString(), anyString());
                when(target.datasetVersion()).thenReturn(v);
                when(target.forVersion(1)).thenReturn(target(1));
                when(target.forVersion(2)).thenReturn(target(2));
                when(target.tableName()).thenReturn(TABLE);
                when(target.targetId()).thenAnswer(i -> logical(v));
                when(target.physicalTargetId())
                        .thenAnswer(i -> physical(snapshot(v).identity().id()));
                when(target.physicalTargetId(anyString(), any()))
                        .thenAnswer(i -> physical(((Identity) i.getArgument(1)).id()));
                when(target.newPublicationTables()).thenReturn(tables);
                when(target.openTable(anyString())).thenAnswer(i -> table(v, i.getArgument(0)));
                when(target.range())
                        .thenAnswer(
                                i -> {
                                    var rows = values.get(v);
                                    return rows.isEmpty()
                                            ? new Range(null, null)
                                            : new Range(
                                                    rows.getFirst().tradeDate(),
                                                    rows.getLast().tradeDate());
                                });
                when(target.prepare(any(), any(), anyList(), any(), any()))
                        .thenAnswer(
                                i ->
                                        StockSuspendStaging.prepare(
                                                i.getArgument(0),
                                                i.getArgument(1),
                                                i.getArgument(2),
                                                i.getArgument(3),
                                                i.getArgument(4)));
                when(target.newWriter(anyString()))
                        .thenAnswer(i -> writer(v, TABLE, i.getArgument(0)));
                var staging = mock(StockSuspendStagingPort.class);
                when(target.newStaging()).thenReturn(staging);
                when(staging.write(any(), any(), any()))
                        .thenAnswer(
                                i -> {
                                    Prepared prepared = i.getArgument(0);
                                    Path evidence = i.getArgument(1);
                                    String stage =
                                            "java_stk_suspend_stage_"
                                                    + UUID.randomUUID().toString().replace("-", "");
                                    var snapshot =
                                            build(identities.get(v) + 1, prepared.expected());
                                    extras.get(v).put(stage, snapshot);
                                    Files.createDirectories(evidence);
                                    Path receipt = evidence.resolve(stage + "-verified.json");
                                    FileEvidenceStore.writeNewDurable(
                                            receipt,
                                            JobDefinitionJson.mapper()
                                                    .writeValueAsBytes(
                                                            StockSuspendLayoutMapper.bind(
                                                                    Map.of(
                                                                            "target",
                                                                            TABLE,
                                                                            "stage",
                                                                            stage,
                                                                            "actual",
                                                                            snapshot,
                                                                            "sourceRows",
                                                                            prepared.source()
                                                                                    .size(),
                                                                            "windowFrom",
                                                                            prepared.fromInclusive()
                                                                                    .toString(),
                                                                            "windowTo",
                                                                            prepared.toInclusive()
                                                                                    .toString(),
                                                                            "preservedOutsideRows",
                                                                            StockSuspendState
                                                                                    .outside(
                                                                                            prepared.before()
                                                                                                    .rows(),
                                                                                            prepared
                                                                                                    .fromInclusive(),
                                                                                            prepared.toInclusive()
                                                                                                    .plusDays(
                                                                                                            1))
                                                                                    .size()),
                                                                    CnTradeDateLayout.fromVersion(
                                                                            v))));
                                    return new Verified(
                                            stage,
                                            snapshot,
                                            prepared.source().isEmpty() ? 0 : 1,
                                            receipt.toString());
                                });
            }
            when(jobs.prepare(any(), anyInt(), any(), anyMap(), any(), any(), any()))
                    .thenAnswer(
                            i ->
                                    StockSuspendSyncJobOwner.definition(i.getArgument(1))
                                            .freeze(
                                                    i.getArgument(2),
                                                    i.getArgument(3),
                                                    i.getArgument(4),
                                                    i.getArgument(5),
                                                    i.getArgument(6)));
            when(pages.fetcher(eq(StockSuspendSource.CONTRACT), any()))
                    .thenReturn(
                            parameters -> {
                                var json = JobDefinitionJson.mapper();
                                List<Map<String, JsonNode>> rows =
                                        nonempty.get()
                                                ? List.of(
                                                        Map.of(
                                                                "ts_code",
                                                                json.valueToTree("000004.SZ"),
                                                                "trade_date",
                                                                json.valueToTree(
                                                                        parameters.get(
                                                                                "trade_date")),
                                                                "suspend_timing",
                                                                json.getNodeFactory().nullNode(),
                                                                "suspend_type",
                                                                json.valueToTree("S")))
                                                : List.of();
                                return new PageExecutor.Page(rows, null, true, "offline");
                            });
        }

        StockSuspendTarget.Versioned target(int version) {
            return targets.get(version);
        }

        String logical(int version) {
            return "static-v2-" + (sameLogical ? "c" : version == 1 ? "d" : "e").repeat(64);
        }

        StockSuspendJobService service(int version) {
            return new StockSuspendJobService(jobs, pages, target(version), path);
        }

        static String physical(long id) {
            return "static-v2-" + String.format("%064x", id);
        }

        Snapshot snapshot(int version) throws Exception {
            return optional(version, TABLE);
        }

        Snapshot optional(int version, String name) throws Exception {
            if (!name.equals(TABLE)) return extras.get(version).get(name);
            return present.get(version)
                    ? build(identities.get(version), values.get(version))
                    : null;
        }

        static Snapshot build(long id, List<StockSuspend> rows) throws Exception {
            var ordered = StockSuspendState.sortedUnique(rows);
            byte[] bytes = StockSuspendStorage.canonical(ordered);
            return new Snapshot(
                    new Identity(id, "generation-" + id),
                    ordered,
                    FileEvidenceStore.sha256(bytes),
                    bytes.length);
        }

        StockSuspendTables.Table table(int version, String name) throws Exception {
            var table = mock(StockSuspendTables.Table.class);
            when(table.preflight()).thenAnswer(i -> optional(version, name).identity());
            when(table.snapshot()).thenAnswer(i -> optional(version, name));
            return table;
        }

        void rename(int version, String from, String to) throws Exception {
            var value = optional(version, from);
            if (value == null || optional(version, to) != null)
                throw new IllegalStateException("夹具代际冲突");
            if (from.equals(TABLE)) present.put(version, false);
            else extras.get(version).remove(from);
            if (to.equals(TABLE)) {
                values.put(version, new ArrayList<>(value.rows()));
                identities.put(version, value.identity().id());
                present.put(version, true);
            } else extras.get(version).put(to, value);
        }

        StockSuspendWriteSession.Versioned writer(int version, String name, String expectedIdentity)
                throws Exception {
            var writer = mock(StockSuspendWriteSession.Versioned.class);
            when(writer.datasetVersion()).thenReturn(version);
            when(writer.codec()).thenReturn(StockSuspendWritePort.CODEC);
            when(writer.recoveryReadbackBatchRows()).thenReturn(100);
            when(writer.walSettled()).thenAnswer(i -> walReady.get());
            doAnswer(
                            i -> {
                                if (preflightFails.get())
                                    throw new IllegalStateException("离线预检停止点");
                                assertEquals(
                                        expectedIdentity,
                                        physical(optional(version, name).identity().id()));
                                return null;
                            })
                    .when(writer)
                    .preflight();
            doAnswer(
                            i -> {
                                List<StockSuspend> rows = i.getArgument(0);
                                var keys = rows.stream().map(StockSuspend::key).toList();
                                var actual = optional(version, name);
                                var updated = new ArrayList<>(actual.rows());
                                updated.removeIf(r -> keys.contains(r.key()));
                                updated.addAll(rows);
                                if (name.equals(TABLE)) values.put(version, updated);
                                else
                                    extras.get(version)
                                            .put(name, build(actual.identity().id(), updated));
                                return null;
                            })
                    .when(writer)
                    .send(anyList());
            when(writer.readback(anyList()))
                    .thenAnswer(
                            i -> {
                                List<StockSuspendKey> keys = i.getArgument(0);
                                return optional(version, name).rows().stream()
                                        .filter(r -> keys.contains(r.key()))
                                        .toList();
                            });
            when(writer.forTarget(anyString(), anyString()))
                    .thenAnswer(i -> writer(version, i.getArgument(0), i.getArgument(1)));
            writers.add(writer);
            return writer;
        }
    }
}
