package com.zoutrankil.data.stock.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.domain.StockDetailState.*;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import com.zoutrankil.data.stock.port.StockDetailTarget;
import com.zoutrankil.data.stock.storage.StockDetailInfoStaging;
import com.zoutrankil.data.stock.storage.StockDetailPublicationJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.zoutrankil.data.stock.storage.StockDetailPublicationJournal.State;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real publication/recovery and SQLite journal/leases; only physical table operations are replaced. */
class StockDetailPublicationContractTest {
    @TempDir Path temp;
    private static final String TABLE = "java_d002_offline";
    private static final Instant OBSERVED = Instant.parse("2026-09-29T01:00:00Z");
    private static final class AbruptStop extends Error {}
    private enum RenameFault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"PREPARED", "OLD_RENAMED", "NEW_RENAMED"})
    void hardStopAfterEachDurablePhaseRecoversWithoutRepeatingCompletedRename(State phase) throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        var publisher = new StockDetailInfoPublication(f.tables, f.path, state -> {
            if (state == phase) throw new AbruptStop();
        });
        assertThrows(AbruptStop.class, () -> publisher.publish(f.lease, TABLE, f.prepared, f.stage, () -> false));
        assertEquals(phase, f.entry().state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertFalse(f.ownedLease().inDoubt());
        assertFalse(Files.exists(f.evidence.resolve("completion.json")));
        int alreadyRenamed = f.tables.renames.size();
        assertThrows(IllegalStateException.class,
                () -> StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, false));
        assertEquals(alreadyRenamed, f.tables.renames.size());

        var result = StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true);

        assertEquals(SyncRunState.VERIFIED, result.state());
        assertEquals(1, result.sourceRows());
        assertEquals(2, f.tables.renames.size(), "Each physical rename occurs exactly once across recovery");
        assertEquals(f.prepared.rows(), f.tables.tables.get(TABLE).rows());
        assertEquals(f.before, f.tables.tables.get(f.entry().intent().backup()));
        assertFalse(f.tables.tables.containsKey(f.stage.table()));
        f.assertVerifiedAndReleased();
        assertEquals(0, f.tables.stagingWrites, "Existing complete stage is reused without source or staging replay");
        var completion = JobDefinitionJson.mapper().readTree(f.evidence.resolve("completion.json").toFile());
        assertEquals(f.run + "-snapshot", completion.path("snapshotSlice").asText());
        assertEquals(f.entry().intent().id(), completion.path("publicationId").asText());
    }

    @ParameterizedTest @EnumSource(RenameFault.class)
    void lostRenameAcknowledgementRetainsLeaseUntilFullRunReconciliation(RenameFault fault) throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        f.tables.fault = fault;
        var publisher = new StockDetailInfoPublication(f.tables, f.path);
        var failure = assertThrows(StockDetailInfoPublication.Uncertain.class,
                () -> publisher.publish(f.lease, TABLE, f.prepared, f.stage, () -> false));
        assertEquals(f.entry().intent().id(), failure.publicationId());
        assertEquals(State.IN_DOUBT, f.entry().state());
        assertTrue(f.ownedLease().inDoubt());
        assertEquals(switch (fault) {
            case BEFORE_FIRST -> StockDetailInfoPublication.Layout.ORIGINAL;
            case AFTER_FIRST -> StockDetailInfoPublication.Layout.OLD_MOVED;
            case AFTER_SECOND -> StockDetailInfoPublication.Layout.PUBLISHED;
        }, publisher.inspect(failure.publicationId()).layout());
        f.tables.fault = null;

        StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true);

        assertEquals(2, f.tables.renames.size());
        assertEquals(f.prepared.rows(), f.tables.tables.get(TABLE).rows());
        f.assertVerifiedAndReleased();
        assertEquals(switch (fault) {
            case BEFORE_FIRST -> List.of(State.RESUMING, State.RESUMING);
            case AFTER_FIRST -> List.of(State.PREPARED, State.RESUMING);
            case AFTER_SECOND -> List.of(State.PREPARED, State.OLD_RENAMED);
        }, f.tables.renameStates);
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"PREPARED", "OLD_RENAMED"})
    void explicitRollbackRestoresOriginalAndKeepsUnreconciledRunLease(State phase) throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        var publisher = new StockDetailInfoPublication(f.tables, f.path, state -> {
            if (state == phase) throw new AbruptStop();
        });
        assertThrows(AbruptStop.class, () -> publisher.publish(f.lease, TABLE, f.prepared, f.stage, () -> false));
        var result = publisher.restoreOriginal(f.lease, f.entry().intent().id(), true);
        assertEquals(State.ROLLED_BACK, result.entry().state());
        assertEquals(StockDetailInfoPublication.Layout.ORIGINAL, result.inspection().layout());
        assertEquals(f.before, f.tables.tables.get(TABLE));
        assertEquals(f.stage.snapshot(), f.tables.tables.get(f.stage.table()));
        assertTrue(f.ownedLease().inDoubt(), "Physical rollback alone does not reconcile the run");
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
    }

    @Test void interruptedStagingRebuildsOnlyFromFrozenCompleteInput() throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        f.tables.tables.remove(f.stage.table());
        f.tables.tables.put("java_partial_stage", f.before);
        f.locks.retainInDoubt(f.lease);

        StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true);

        assertEquals(1, f.tables.stagingWrites);
        assertEquals(f.before, f.tables.tables.get("java_partial_stage"));
        assertEquals(f.prepared.rows(), f.tables.tables.get(TABLE).rows());
        f.assertVerifiedAndReleased();
    }

    @Test void corruptPreparedSourceAfterJournalCommitRejectsBeforeAnyRecoveryRename() throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        var publisher = new StockDetailInfoPublication(f.tables, f.path, state -> {
            if (state == State.OLD_RENAMED) throw new AbruptStop();
        });
        assertThrows(AbruptStop.class, () -> publisher.publish(f.lease, TABLE, f.prepared, f.stage, () -> false));
        var file = f.evidence.resolve("prepared-publication.json");
        var json = JobDefinitionJson.mapper();
        var proof = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) proof.path("sourceRows").get(0)).put("name", "tampered");
        Files.writeString(file, json.writeValueAsString(proof));
        var beforeJournal = f.entry();

        var error = assertThrows(IllegalStateException.class,
                () -> StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true));

        assertEquals("Prepared publication merge is inconsistent", error.getMessage());
        assertEquals(beforeJournal, f.entry());
        assertEquals(1, f.tables.renames.size());
        assertNotNull(f.ownedLease());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertFalse(Files.exists(f.evidence.resolve("completion.json")));
    }

    @Test void emptyPreparedSourceCannotManufactureARecoverablePublication() throws Exception {
        var f = new Fixture(temp, List.of());
        assertFalse(f.prepared.merge().requiresPublication());
        var error = assertThrows(IllegalStateException.class,
                () -> StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true));
        assertEquals("Prepared publication merge is inconsistent", error.getMessage());
        assertNull(new StockDetailPublicationJournal(f.path).findSingleForRun(f.run));
        assertEquals(0, f.tables.stagingWrites);
        assertTrue(f.tables.renames.isEmpty());
        assertNotNull(f.ownedLease());
    }

    @Test void conflictingPhysicalLayoutNeverRenamesDuringRecovery() throws Exception {
        var f = new Fixture(temp, List.of(row("000001.SZ", "new")));
        var publisher = new StockDetailInfoPublication(f.tables, f.path, state -> {
            if (state == State.OLD_RENAMED) throw new AbruptStop();
        });
        assertThrows(AbruptStop.class, () -> publisher.publish(f.lease, TABLE, f.prepared, f.stage, () -> false));
        f.tables.tables.put(TABLE, snapshot(99, f.before.rows()));
        var error = assertThrows(IllegalStateException.class,
                () -> StockDetailInfoRunRecovery.finishInterrupted(f.tables, f.path, TABLE, f.run, true));
        assertEquals("Conflicting publication cannot be resumed", error.getMessage());
        assertEquals(1, f.tables.renames.size());
        assertNotNull(f.ownedLease());
        assertEquals(State.OLD_RENAMED, f.entry().state());
    }

    private static final class Fixture {
        final Path path, evidence;
        final String run = "detail-offline";
        final SyncRunLedger ledger;
        final DatasetIntervalLock locks;
        final DatasetIntervalLock.Lease lease;
        final FakeTarget tables;
        final Snapshot before;
        final Prepared prepared;
        final Verified stage;
        Fixture(Path temp, List<StockDetailInfo> source) throws Exception {
            path = temp.resolve("ledger.sqlite"); evidence = temp.resolve("sync-evidence").resolve(run);
            ledger = new SyncRunLedger(path); locks = new DatasetIntervalLock(path);
            before = snapshot(11, List.of(new StockDetailInfoMapper().toStorage(row("600000.SH", "retained"))));
            tables = new FakeTarget(path, run); tables.tables.put(TABLE, before);
            var owner = new StockDetailInfoJobService(mock(TusharePageService.class), tables, path);
            var request = owner.plan(List.of("000001.SZ"), false, LocalDate.of(2026, 9, 29));
            var target = owner.targetId();
            ledger.createRun(run, null, target, request); running(run);
            ledger.createChild(run + "-attempt", SyncRunLedger.Kind.ATTEMPT, run, run); running(run + "-attempt");
            ledger.createChild(run + "-snapshot", SyncRunLedger.Kind.SLICE, run, run + "-attempt"); running(run + "-snapshot");
            lease = locks.acquire(run, DatasetIntervalLock.Scope.allDates("stock_detail_info"));
            prepared = tables.prepare(before, source);
            StockDetailRecoveryEvidence.prepare(evidence, run, target, request, OBSERVED, source, List.of(), prepared);
            var staged = snapshot(22, prepared.rows());
            stage = new Verified("java_stock_detail_stage_offline", staged, evidence.resolve("stage.json").toString());
            tables.tables.put(stage.table(), staged);
        }
        void running(String id) throws Exception { ledger.transition(id, ledger.get(id).revision(), SyncRunState.RUNNING, "{}"); }
        StockDetailPublicationJournal.Entry entry() throws Exception {
            return new StockDetailPublicationJournal(path).requireSingleForRun(run);
        }
        DatasetIntervalLock.Lease ownedLease() throws Exception {
            return locks.findOwned(run, DatasetIntervalLock.Scope.allDates("stock_detail_info"));
        }
        void assertVerifiedAndReleased() throws Exception {
            assertEquals(State.VERIFIED, entry().state());
            for (String id : List.of(run + "-snapshot", run + "-attempt", run))
                assertEquals(SyncRunState.VERIFIED, ledger.get(id).state());
            assertNull(ownedLease());
        }
    }

    private static final class FakeTarget implements StockDetailTarget {
        final Map<String, Snapshot> tables = new HashMap<>();
        final List<String> renames = new ArrayList<>();
        final List<State> renameStates = new ArrayList<>();
        final Path ledger;
        final String run;
        RenameFault fault;
        int stagingWrites;
        FakeTarget(Path ledger, String run) { this.ledger = ledger; this.run = run; }
        public String tableName() { return TABLE; }
        public Table open(String table) {
            return new Table() {
                public Identity preflight() { return tables.get(table).identity(); }
                public Snapshot snapshot() { return Objects.requireNonNull(tables.get(table), table); }
            };
        }
        public String identify(String table, Identity id) { return "detail:" + table + ":" + id.id() + ":" + id.directory(); }
        public boolean exists(String table) { return tables.containsKey(table); }
        public StockDetailTarget publicationTables() { return this; }
        public Prepared prepare(Snapshot before, List<StockDetailInfo> source) throws Exception {
            return StockDetailInfoStaging.prepare(before, source);
        }
        public StageWriter newStaging() {
            return (prepared, evidence, cancelled) -> {
                assertFalse(cancelled.getAsBoolean()); stagingWrites++;
                String name = "java_stock_detail_rebuilt_" + stagingWrites;
                var snapshot = StockDetailPublicationContractTest.snapshot(100 + stagingWrites, prepared.rows());
                tables.put(name, snapshot);
                return new Verified(name, snapshot, evidence.resolve("rebuilt.json").toString());
            };
        }
        public void rename(String from, String to) {
            if (fault == RenameFault.BEFORE_FIRST && renames.isEmpty()) throw new IllegalStateException("rename rejected");
            try {
                var state = new StockDetailPublicationJournal(ledger).requireSingleForRun(run).state();
                assertNotNull(new DatasetIntervalLock(ledger).findOwned(run, DatasetIntervalLock.Scope.allDates("stock_detail_info")));
                assertEquals(SyncRunState.RUNNING, new SyncRunLedger(ledger).get(run).state());
                assertFalse(tables.containsKey(to));
                var moved = Objects.requireNonNull(tables.remove(from), from);
                tables.put(to, moved); renames.add(from + "->" + to); renameStates.add(state);
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (fault == RenameFault.AFTER_FIRST && renames.size() == 1
                    || fault == RenameFault.AFTER_SECOND && renames.size() == 2)
                throw new IllegalStateException("rename acknowledgement lost");
        }
    }

    private static StockDetailInfo row(String code, String name) {
        return new StockDetailInfo(code, OBSERVED, null, name, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }
    private static Snapshot snapshot(long id, List<StockDetailInfoRow> rows) throws Exception {
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(rows);
        return new Snapshot(new Identity(id, "directory-" + id), rows, FileEvidenceStore.sha256(bytes), bytes.length);
    }
}
