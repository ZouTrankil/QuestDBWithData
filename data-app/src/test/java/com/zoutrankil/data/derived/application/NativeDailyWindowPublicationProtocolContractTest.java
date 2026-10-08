package com.zoutrankil.data.derived.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.derived.domain.NativeDailyProjection;
import com.zoutrankil.data.derived.port.NativeDailyCodec;
import com.zoutrankil.data.derived.port.NativeDailyPublicationTables;
import com.zoutrankil.data.derived.port.NativeDailyWindowSession;
import com.zoutrankil.data.derived.storage.NativeDailyWindowPublicationStorage;
import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.NativeDailyWindowSnapshot;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real runner, immutable source/slice proofs, publication journal and SQLite locks; no external DB. */
public class NativeDailyWindowPublicationProtocolContractTest {
    private static final String DATASET = "native_protocol";
    private static final String TABLE = "java_native_protocol_target";
    private static final String PREFIX = "java_native_protocol";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);
    private static final NativeDailyProjection<Row> PROJECTION = new NativeDailyProjection<>(
            Row.class, List.of("trade_date", "value"));
    private static final NativeDailyCodec<Row> CODEC = new NativeDailyCodec<>(PROJECTION);
    @TempDir Path temp;

    public record Row(Instant tradeDate, Double value) {}
    enum Fault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND, READBACK, STAGE_ONLY, DURING_READBACK }
    static final class HardStop extends Error {}

    @Test void runnerCompletesOnlyAfterBothRenamesExactReadbackAndPublicationEvidence() throws Exception {
        var f = new Fixture(temp);
        var result = f.execute();
        assertEquals(SyncRunState.VERIFIED, result.state());
        f.assertComplete();
        assertEquals(List.of("PREPARED", "OLD_MOVED", "PUBLISHED", "VERIFIED"), f.journalStates());
        var trace = f.trace();
        assertTrue(trace.indexOf("SLICE:VERIFIED") < trace.indexOf("journal:PREPARED"));
        assertTrue(trace.indexOf("journal:VERIFIED") < trace.indexOf("ATTEMPT:VERIFIED"));
        assertTrue(trace.indexOf("ATTEMPT:VERIFIED") < trace.indexOf("RUN:VERIFIED"));
        assertTrue(trace.indexOf("RUN:VERIFIED") < trace.indexOf("lease:RELEASED"));
        assertEquals(List.of(SyncRunState.PENDING, SyncRunState.RUNNING, SyncRunState.FETCHED,
                SyncRunState.VALIDATED, SyncRunState.SUBMITTED, SyncRunState.ACKNOWLEDGED,
                SyncRunState.VERIFIED), f.ledger.events(f.slice().id(), -1, 100).stream().map(SyncRunLedger.Event::state).toList());
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"BEFORE_FIRST", "AFTER_FIRST", "AFTER_SECOND", "READBACK"})
    void realHardStopRecoversEveryDurablePhysicalLayoutWithoutResending(Fault fault) throws Exception {
        var f = new Fixture(temp);
        f.stop(fault);
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertFalse(f.lease().inDoubt());
        assertEquals(fault == Fault.READBACK ? ReferencePublicationJournal.State.PUBLISHED
                : fault == Fault.AFTER_SECOND ? ReferencePublicationJournal.State.OLD_MOVED
                : ReferencePublicationJournal.State.PREPARED, f.journal().state());
        f.assertGenuineVerifiedSlice();
        byte[] source = Files.readAllBytes(f.source);
        f.clearFault();
        f.recover();
        f.assertComplete();
        assertArrayEquals(source, Files.readAllBytes(f.source));
        assertEquals(1, f.fetches);
        assertEquals(1, f.session.sends);
        assertEquals(2, f.tables.renames);
        assertEquals(0, f.recoverySession.sends);
        long revision = f.journal().revision();
        byte[] publication = Files.readAllBytes(f.publicationFile());
        f.recover();
        assertEquals(revision, f.journal().revision());
        assertArrayEquals(publication, Files.readAllBytes(f.publicationFile()));
        assertEquals(2, f.tables.renames);
        assertNull(f.lease());
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"BEFORE_FIRST", "AFTER_FIRST", "AFTER_SECOND"})
    void caughtRenameFailureRetainsUncertainJournalRunAndLeaseUntilReconciliation(Fault fault) throws Exception {
        var f = new Fixture(temp);
        f.tables.fault = fault;
        f.tables.softFailure = true;
        assertEquals(SyncRunState.IN_DOUBT, f.execute().state());
        assertEquals(SyncRunState.IN_DOUBT, f.ledger.get(f.run).state());
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT, f.journal().state());
        assertTrue(f.lease().inDoubt());
        f.assertGenuineVerifiedSlice();
        f.clearFault();
        f.recover();
        f.assertComplete();
        assertEquals(1, f.fetches);
        assertEquals(1, f.session.sends);
    }

    @Test void cancellationBetweenRenamesRetainsAuthorityForStoppedWriterRecovery() throws Exception {
        var f = new Fixture(temp);
        f.tables.cancelAfterFirst = true;
        assertEquals(SyncRunState.IN_DOUBT, f.execute().state());
        assertEquals(1, f.tables.renames);
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT, f.journal().state());
        assertTrue(f.lease().inDoubt());
        f.assertGenuineVerifiedSlice();
        f.recover();
        f.assertComplete();
        assertEquals(2, f.tables.renames);
        assertEquals(1, f.fetches);
        assertEquals(1, f.session.sends);
    }

    @Test void evidenceCreationFailureAfterJournalVerifiedStillRequiresReconciliationBeforeLeaseRelease() throws Exception {
        var f = new Fixture(temp);
        Files.createDirectories(f.publicationFile()); // fail CREATE_NEW after exact published readback
        assertEquals(SyncRunState.IN_DOUBT, f.execute().state());
        assertEquals(ReferencePublicationJournal.State.VERIFIED, f.journal().state());
        assertEquals(SyncRunState.IN_DOUBT, f.ledger.get(f.run).state());
        assertTrue(f.lease().inDoubt());
        assertEquals(2, f.tables.renames);
        Files.delete(f.publicationFile()); // remove only the test's empty directory fault
        f.recover();
        f.assertComplete();
        assertEquals(2, f.tables.renames);
    }

    @Test void leaseDeleteFailureAfterVerifiedLedgerCanBeReopenedWithoutRepeatingAnyPublication() throws Exception {
        var f = new Fixture(temp);
        f.sql("CREATE TRIGGER fail_release BEFORE DELETE ON sync_interval_locks BEGIN SELECT RAISE(ABORT,'test lease release'); END");
        assertEquals(SyncRunState.IN_DOUBT, f.execute().state());
        assertEquals(SyncRunState.VERIFIED, f.ledger.get(f.run).state());
        assertEquals(SyncRunState.VERIFIED, f.ledger.get(f.slice().parentId()).state());
        assertEquals(ReferencePublicationJournal.State.VERIFIED, f.journal().state());
        assertTrue(f.lease().inDoubt());
        byte[] evidence = Files.readAllBytes(f.publicationFile());
        f.sql("DROP TRIGGER fail_release");
        f.recover();
        f.assertComplete();
        assertArrayEquals(evidence, Files.readAllBytes(f.publicationFile()));
        assertEquals(2, f.tables.renames);
        assertEquals(1, f.session.sends);
    }

    @Test void repeatedRecoveryReadbackFailureKeepsOriginalPhysicalStateAndOwnedLease() throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.tables.fault = Fault.READBACK;
        f.tables.softFailure = true;
        assertThrows(IllegalStateException.class, f::recover);
        assertEquals(2, f.tables.renames);
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT, f.journal().state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertNotNull(f.lease());
        f.clearFault();
        f.recover();
        f.assertComplete();
        assertEquals(2, f.tables.renames);
    }

    @ParameterizedTest @ValueSource(strings = {"stopped", "producer", "model"})
    void exactStoppedWriterAndProducerModelGuardsRejectBeforeRename(String guard) throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.clearFault();
        Exception failure = assertThrows(Exception.class, () -> f.publication(new MemorySession(f)).finishInterrupted(
                f.run, !guard.equals("stopped"), guard.equals("producer") ? "java.other" : "java." + DATASET,
                guard.equals("model") ? "wrong-version" : "v1"));
        assertTrue(failure instanceof IllegalArgumentException || failure instanceof IllegalStateException);
        f.assertRejectedBeforeRename();
    }

    @ParameterizedTest @ValueSource(strings = {"bytes", "missing", "outsideOwnedRoot", "producer", "model", "fingerprint", "date", "duplicate", "empty", "changedRow"})
    void alteredSourceCannotAuthorizeRemainingRenamesEvenIfJournalScopeIsRehashed(String mutation) throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.clearFault();
        if (mutation.equals("missing")) Files.delete(f.source);
        else if (mutation.equals("bytes")) Files.writeString(f.source, " ", StandardOpenOption.APPEND);
        else if (mutation.equals("outsideOwnedRoot")) {
            Path foreign = temp.resolve("unowned-source.json");
            Files.copy(f.source, foreign);
            f.changeScope("sourceEvidence", foreign.toString());
        } else {
            var source = (ObjectNode) JobDefinitionJson.mapper().readTree(f.source.toFile());
            switch (mutation) {
                case "producer" -> source.put("producer", "java.other");
                case "model" -> source.put("modelVersion", "other-model");
                case "fingerprint" -> source.put("sourceFingerprint", "f".repeat(64));
                case "date" -> source.put("from", DAY.minusDays(1).toString());
                case "duplicate" -> source.withArray("rows").add(source.path("rows").get(0).deepCopy());
                case "empty" -> source.putArray("rows");
                case "changedRow" -> ((ObjectNode) source.path("rows").get(0)).put("value", 41.0);
                default -> throw new AssertionError(mutation);
            }
            Files.write(f.source, JobDefinitionJson.mapper().writeValueAsBytes(source));
            f.changeScope("sourceEvidenceSha256", hash(f.source));
        }
        assertThrows(IllegalStateException.class, f::recover);
        f.assertRejectedBeforeRename();
    }

    @ParameterizedTest @ValueSource(strings = {"originalId", "originalDirectory", "originalRows", "stageId", "stageRows", "extraBackup", "missingStage"})
    void completePhysicalIdentityAndOutsideWindowRowsAreRequiredBeforeRecoveryMutation(String mutation) throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.clearFault();
        var original = f.tables.physical.get(TABLE);
        var stage = f.tables.physical.get(f.session.stage());
        switch (mutation) {
            case "originalId" -> f.tables.physical.put(TABLE, new Physical(88, original.directory(), original.rows()));
            case "originalDirectory" -> f.tables.physical.put(TABLE, new Physical(original.id(), "changed-generation", original.rows()));
            case "originalRows" -> f.tables.physical.put(TABLE, new Physical(original.id(), original.directory(), List.of(row(DAY, 81.0))));
            case "stageId" -> f.tables.physical.put(f.session.stage(), new Physical(99, stage.directory(), stage.rows()));
            case "stageRows" -> f.tables.physical.put(f.session.stage(), new Physical(stage.id(), stage.directory(), f.window));
            case "extraBackup" -> f.tables.physical.put(f.journal().intent().backup(), original);
            case "missingStage" -> f.tables.physical.remove(f.session.stage());
            default -> throw new AssertionError(mutation);
        }
        assertThrows(IllegalStateException.class, f::recover);
        f.assertRejectedBeforeRename();
    }

    @ParameterizedTest @ValueSource(strings = {"job", "target", "window", "lease"})
    void FrozenOwnerTargetWindowAndWholeDatasetLeaseRemainRecoveryAuthority(String mutation) throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.clearFault();
        switch (mutation) {
            case "job" -> f.update("UPDATE sync_runs SET job_id=? WHERE id=?", "data.other", f.run);
            case "target" -> f.update("UPDATE sync_runs SET target_id=? WHERE id=?", "other-target", f.run);
            case "window" -> {
                var frozen = (ObjectNode) JobDefinitionJson.mapper().readTree(f.ledger.getRun(f.run).frozenJson());
                frozen.put("from", DAY.minusDays(1).toString());
                f.update("UPDATE sync_runs SET frozen_json=? WHERE id=?", frozen.toString(), f.run);
            }
            case "lease" -> f.update("UPDATE sync_interval_locks SET dataset_id=? WHERE run_id=?", "other_dataset", f.run);
            default -> throw new AssertionError(mutation);
        }
        assertThrows(IllegalStateException.class, f::recover);
        assertEquals(0, f.tables.renames);
        assertEquals(ReferencePublicationJournal.State.PREPARED, f.journal().state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertEquals(1, f.scalar("SELECT count(*) FROM sync_interval_locks"));
    }

    @ParameterizedTest @EnumSource(value = SyncRunState.class, names = {"FETCHED", "SUBMITTED", "ACKNOWLEDGED", "IN_DOUBT", "FAILED"})
    void currentNonverifiedSliceCannotBorrowItsOldGenuineVerifiedEvent(SyncRunState driftedState) throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.assertGenuineVerifiedSlice();
        // Negative corruption fixture only: no fabricated FETCHED or verification payload authorizes success.
        f.update("UPDATE sync_entries SET state=? WHERE id=?", driftedState.name(), f.slice().id());
        f.clearFault();
        assertThrows(IllegalStateException.class, f::recover);
        f.assertRejectedBeforeRename();
        assertEquals(driftedState, f.slice().state());
    }

    @ParameterizedTest @EnumSource(value = Fault.class, names = {"STAGE_ONLY", "DURING_READBACK"})
    void stageWithoutDurablePublicationIntentIsRefusedEvenWithRealSourceAndOwnedLease(Fault fault) throws Exception {
        var f = new Fixture(temp);
        f.stop(fault);
        assertTrue(f.publisher.findForRun(f.run).isEmpty());
        assertTrue(Files.isRegularFile(f.source));
        assertTrue(f.tables.physical.containsKey(f.session.stage()));
        assertEquals(fault == Fault.STAGE_ONLY ? SyncRunState.VERIFIED : SyncRunState.SUBMITTED, f.slice().state());
        var original = f.tables.physical.get(TABLE);
        var lease = f.lease();
        f.clearFault();
        assertThrows(IllegalStateException.class, f::recover);
        assertEquals(original, f.tables.physical.get(TABLE));
        assertEquals(0, f.tables.renames);
        assertEquals(lease, f.lease());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
    }

    @Test void conflictingExistingPublicationEvidenceIsNotOverwrittenAndDoesNotReleaseLease() throws Exception {
        var f = new Fixture(temp);
        f.stop(Fault.BEFORE_FIRST);
        f.clearFault();
        byte[] altered = "{\"published\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(f.publicationFile(), altered, StandardOpenOption.CREATE_NEW);
        assertThrows(IllegalStateException.class, f::recover);
        assertEquals(ReferencePublicationJournal.State.VERIFIED, f.journal().state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(f.run).state());
        assertNotNull(f.lease());
        assertArrayEquals(altered, Files.readAllBytes(f.publicationFile()));
        assertEquals(2, f.tables.renames);
    }

    private static final class Fixture {
        final Path path, source;
        final String run = "native-protocol-run";
        final SyncRunLedger ledger;
        final DatasetIntervalLock locks;
        final FakeTables tables;
        final MemorySession session;
        final NativeDailyWindowPublication<Row> publisher;
        final NativeDailyWindowSnapshot<Row> before;
        final List<Row> window = List.of(row(DAY, -0.0));
        final SyncJobDefinition.FrozenRequest request;
        int fetches;
        boolean cancelled;
        MemorySession recoverySession;

        Fixture(Path root) throws Exception {
            path = root.resolve("ledger.sqlite");
            source = root.resolve("sync-evidence").resolve(run).resolve("source.json");
            ledger = new SyncRunLedger(path);
            locks = new DatasetIntervalLock(path);
            new ReferencePublicationJournal(path, DATASET);
            sql("CREATE TABLE protocol_trace(seq INTEGER PRIMARY KEY, event TEXT NOT NULL)");
            sql("CREATE TRIGGER journal_insert AFTER INSERT ON reference_publications BEGIN INSERT INTO protocol_trace(event) VALUES('journal:'||NEW.state); END");
            sql("CREATE TRIGGER journal_update AFTER UPDATE ON reference_publications BEGIN INSERT INTO protocol_trace(event) VALUES('journal:'||NEW.state); END");
            sql("CREATE TRIGGER ledger_update AFTER UPDATE ON sync_entries BEGIN INSERT INTO protocol_trace(event) VALUES(NEW.kind||':'||NEW.state); END");
            sql("CREATE TRIGGER lease_release AFTER DELETE ON sync_interval_locks BEGIN INSERT INTO protocol_trace(event) VALUES('lease:RELEASED'); END");
            tables = new FakeTables(this);
            tables.physical.put(TABLE, new Physical(11, "old-generation", List.of(row(DAY.minusDays(1), null), row(DAY, 99.0), row(DAY.plusDays(1), 2.0))));
            session = new MemorySession(this);
            publisher = publication(session);
            before = session.formalSnapshot();
            var definition = new SyncJobDefinition("data." + DATASET, 1, DATASET, 1, "NativeProtocolOwner",
                    Set.of(SyncJobDefinition.Mode.MATERIALIZE), SyncJobDefinition.Mode.MATERIALIZE, Map.of(),
                    "native.rate", "native.slice", "native.verify",
                    new SyncJobDefinition.RetryPolicy(1, Duration.ofMillis(1), Duration.ofSeconds(1)),
                    Duration.ofMinutes(1), new SyncJobDefinition.Budget(366, 1, 1, 366, 1024 * 1024), 0,
                    List.of(), SyncJobDefinition.Frequency.MANUAL, ZoneOffset.UTC, true, false);
            request = definition.freeze(null, Map.of(), DAY, DAY, DAY);
        }

        NativeDailyWindowPublication<Row> publication(MemorySession writer) {
            return new NativeDailyWindowPublication<>(tables, path, DATASET, writer);
        }
        SyncJobRunner.Result execute() throws Exception {
            var adapter = new SyncJobRunner.Adapter<Row, Instant>() {
                public void preflight(SyncJobDefinition.FrozenRequest ignored) throws Exception {
                    publisher.requireNoPendingPublication();
                    session.requireSame(before);
                }
                public DatasetIntervalLock.Scope conflictScope(SyncJobDefinition.FrozenRequest ignored) { return scope(); }
                public VerifiedBatchExecutor.Codec<Row, Instant> codec() { return CODEC; }
                public VerifiedBatchExecutor.Port<Row, Instant> port() { return session; }
                public boolean recoveryRequired(String ignored) throws Exception { return publisher.findForRun(run).isPresent(); }
                public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest frozen,
                        SyncJobRunner.PageConsumer<Row> consumer, BooleanSupplier cancelled) throws Exception {
                    fetches++;
                    String fingerprint = PROJECTION.digest(window);
                    Files.createDirectories(source.getParent());
                    byte[] raw = JobDefinitionJson.mapper().writeValueAsBytes(Map.of("producer", "java." + DATASET,
                            "modelVersion", "v1", "from", frozen.from(), "to", frozen.to(),
                            "sourceFingerprint", fingerprint, "rows", window));
                    Files.write(source, raw, StandardOpenOption.CREATE_NEW);
                    session.prepare(before, frozen.from(), frozen.to());
                    consumer.accept(new SyncJobRunner.Page<>(window, fingerprint, source.toString(), null));
                    if (tables.fault == Fault.STAGE_ONLY) tables.trip();
                    var published = publisher.publish(run, lease(), before, DAY, DAY, window,
                            Map.of("sourceEvidence", source.toString(), "sourceEvidenceSha256", hash(source),
                                    "sourceFingerprint", fingerprint), cancelled);
                    return new SyncJobRunner.SourceCompletion(1, window.size(), true, published.evidence().toString());
                }
            };
            return new SyncJobRunner<Row, Instant>(ledger, locks).run(run, null, before.targetId(), request, adapter, () -> cancelled);
        }
        void stop(Fault fault) throws Exception {
            tables.fault = fault;
            assertThrows(HardStop.class, this::execute);
            assertEquals(1, fetches);
            assertNotNull(lease());
        }
        void clearFault() { tables.fault = null; tables.softFailure = false; }
        NativeDailyWindowSnapshot<Row> recover() throws Exception {
            recoverySession = new MemorySession(this); // no previous stage/write-id state is reused
            return publication(recoverySession).finishInterrupted(run, true, "java." + DATASET, "v1");
        }
        DatasetIntervalLock.Scope scope() { return DatasetIntervalLock.Scope.allDates(DATASET); }
        IntervalLockStore.Lease lease() { return locks.findOwned(run, scope()); }
        SyncRunLedger.Entry slice() throws Exception {
            return ledger.entries(run, null, 100).stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        }
        ReferencePublicationJournal.Entry journal() throws Exception { return new ReferencePublicationJournal(path, DATASET).forRun(run); }
        Path publicationFile() { return source.resolveSibling("publication.json"); }
        void assertGenuineVerifiedSlice() throws Exception {
            assertEquals(SyncRunState.VERIFIED, slice().state());
            var events = ledger.events(slice().id(), -1, 100);
            var fetched = JobDefinitionJson.mapper().readTree(events.stream().filter(e -> e.state() == SyncRunState.FETCHED).findFirst().orElseThrow().payloadJson());
            assertEquals(1, fetched.path("returnedRows").intValue());
            assertEquals(source.toString(), fetched.path("responseEvidence").asText());
            var proof = JobDefinitionJson.mapper().readTree(slice().payloadJson()).path("verification");
            assertTrue(proof.path("passed").booleanValue());
            assertEquals(PROJECTION.digest(window), proof.path("sourceFingerprint").asText());
            assertEquals(1, proof.path("expectedRows").intValue());
            assertEquals(1, proof.path("matchedRows").intValue());
            assertTrue(proof.path("readbackEvidence").asText().startsWith("ledger:" + slice().id()));
        }
        void assertComplete() throws Exception {
            assertEquals(ReferencePublicationJournal.State.VERIFIED, journal().state());
            assertEquals(SyncRunState.VERIFIED, ledger.get(run).state());
            assertEquals(SyncRunState.VERIFIED, ledger.get(slice().parentId()).state());
            assertGenuineVerifiedSlice();
            assertNull(lease());
            assertEquals(22, tables.physical.get(TABLE).id());
            assertEquals(List.of(row(DAY.minusDays(1), null), row(DAY, -0.0), row(DAY.plusDays(1), 2.0)), tables.physical.get(TABLE).rows());
            assertEquals(before.rows(), tables.physical.get(journal().intent().backup()).rows());
            assertEquals(11, tables.physical.get(journal().intent().backup()).id());
            assertFalse(tables.physical.containsKey(session.stage()));
            var proof = JobDefinitionJson.mapper().readTree(publicationFile().toFile());
            assertTrue(proof.path("published").booleanValue());
            assertEquals(PROJECTION.digest(tables.physical.get(TABLE).rows()), proof.path("fullTargetFingerprint").asText());
            assertEquals(3, proof.path("formalRows").intValue());
            assertEquals(1, proof.path("windowRows").intValue());
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(tables.physical.get(TABLE).rows().get(1).value()));
        }
        void assertRejectedBeforeRename() throws Exception {
            assertEquals(0, tables.renames);
            assertEquals(ReferencePublicationJournal.State.PREPARED, journal().state());
            assertEquals(SyncRunState.RUNNING, ledger.get(run).state());
            assertNotNull(lease());
        }
        void changeScope(String key, String value) throws Exception {
            var intent = (ObjectNode) JobDefinitionJson.mapper().valueToTree(journal().intent());
            var scope = (ObjectNode) JobDefinitionJson.mapper().readTree(intent.path("scope").asText());
            scope.put(key, value);
            intent.put("scope", scope.toString());
            update("UPDATE reference_publications SET intent_json=? WHERE run_id=?", intent.toString(), run);
        }
        void update(String sql, String first, String second) throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.prepareStatement(sql)) {
                statement.setString(1, first); statement.setString(2, second);
                assertEquals(1, statement.executeUpdate());
            }
        }
        void sql(String command) throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement()) { statement.execute(command); }
        }
        int scalar(String command) throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement(); var rows = statement.executeQuery(command)) {
                assertTrue(rows.next()); return rows.getInt(1);
            }
        }
        List<String> trace() throws Exception {
            var trace = new ArrayList<String>();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement(); var rows = statement.executeQuery("SELECT event FROM protocol_trace ORDER BY seq")) {
                while (rows.next()) trace.add(rows.getString(1));
            }
            return List.copyOf(trace);
        }
        List<String> journalStates() throws Exception { return trace().stream().filter(s -> s.startsWith("journal:")).map(s -> s.substring(8)).toList(); }
    }

    private record Physical(long id, String directory, List<Row> rows) {
        Physical { rows = List.copyOf(rows); }
    }
    private static final class FakeTables implements NativeDailyPublicationTables {
        final Fixture fixture;
        final Map<String, Physical> physical = new HashMap<>();
        // Only the production SQLite discovery methods are called; no mocked DataSource connection is opened.
        final NativeDailyWindowPublicationStorage sqlite = new NativeDailyWindowPublicationStorage(new JdbcTemplate(mock(DataSource.class)));
        Fault fault;
        boolean softFailure;
        boolean cancelAfterFirst;
        int renames;
        FakeTables(Fixture fixture) { this.fixture = fixture; }
        public void requireNoPendingPublication(Path path, String dataset) throws SQLException { sqlite.requireNoPendingPublication(path, dataset); }
        public <T> Optional<T> findForRun(Path path, String dataset, String run, Decoder<T> decoder) throws Exception {
            return sqlite.findForRun(path, dataset, run, decoder);
        }
        public boolean tableExists(String table) { return physical.containsKey(table); }
        public boolean identityMatches(String table, long id) { return physical.containsKey(table) && physical.get(table).id() == id; }
        public void rename(String from, String to) {
            try {
                fixture.assertGenuineVerifiedSlice();
                assertNotNull(fixture.lease());
                assertNotEquals(SyncRunState.VERIFIED, fixture.ledger.get(fixture.run).state());
            } catch (Exception failure) { throw new AssertionError(failure); }
            if (renames == 0 && fault == Fault.BEFORE_FIRST) trip();
            assertTrue(physical.containsKey(from), from);
            assertFalse(physical.containsKey(to), to);
            physical.put(to, physical.remove(from));
            renames++;
            if (renames == 1 && cancelAfterFirst) fixture.cancelled = true;
            if (renames == 1 && fault == Fault.AFTER_FIRST || renames == 2 && fault == Fault.AFTER_SECOND) trip();
        }
        void trip() { if (softFailure) throw new IllegalStateException("Injected native publication boundary failure"); throw new HardStop(); }
    }

    private static final class MemorySession implements NativeDailyWindowSession<Row> {
        final Fixture fixture;
        String writeStage;
        int sends;
        MemorySession(Fixture fixture) { this.fixture = fixture; }
        public String table() { return TABLE; }
        public String stage() { return java.util.Objects.requireNonNull(writeStage); }
        public String stagePrefix() { return PREFIX; }
        public Class<Row> rowType() { return Row.class; }
        public List<String> columns() { return List.of("trade_date", "value"); }
        public VerifiedBatchExecutor.Codec<Row, Instant> codec() { return CODEC; }
        public NativeDailyWindowSnapshot<Row> snapshot(String table) {
            var physical = fixture.tables.physical.get(table);
            if (physical == null) throw new IllegalStateException("Missing physical table " + table);
            return new NativeDailyWindowSnapshot<>("physical-" + physical.id(), physical.id(), physical.directory(), true,
                    physical.rows(), digest(physical.rows()));
        }
        public NativeDailyWindowSnapshot<Row> formalSnapshot() {
            if (fixture.tables.renames == 2 && fixture.tables.fault == Fault.READBACK) fixture.tables.trip();
            return snapshot(TABLE);
        }
        public void requireSame(NativeDailyWindowSnapshot<Row> expected) {
            var actual = formalSnapshot();
            if (!expected.targetId().equals(actual.targetId()) || !expected.fingerprint().equals(actual.fingerprint()))
                throw new IllegalStateException("Physical generation or content changed");
        }
        public NativeDailyWindowSnapshot<Row> prepare(NativeDailyWindowSnapshot<Row> before, LocalDate from, LocalDate to) {
            requireSame(before);
            writeStage = PREFIX + "_stage_test";
            var outside = before.rows().stream().filter(row -> outside(row, from, to)).toList();
            fixture.tables.physical.put(writeStage, new Physical(22, "replacement-generation", outside));
            return snapshot(writeStage);
        }
        public void preflight() { snapshot(stage()); }
        public void send(List<Row> rows) {
            sends++;
            var physical = fixture.tables.physical.get(stage());
            var all = new ArrayList<>(physical.rows());
            all.addAll(rows); all.sort(Comparator.comparing(Row::tradeDate));
            fixture.tables.physical.put(stage(), new Physical(physical.id(), physical.directory(), all));
        }
        public List<Row> readback(List<Instant> keys) {
            if (fixture.tables.fault == Fault.DURING_READBACK) fixture.tables.trip();
            return readAll(stage()).stream().filter(row -> keys.contains(row.tradeDate())).toList();
        }
        public boolean walSettled() { return true; }
        public boolean uncertainSenderStopped() { return true; }
        public List<Row> readAll(String table) { return snapshot(table).rows(); }
        public boolean outside(Row row, LocalDate from, LocalDate to) { return PROJECTION.outside(row, from, to); }
        public Row row(Map<String, ?> values) { return PROJECTION.row(values); }
        public Map<String, Object> values(Row row) { return PROJECTION.values(row); }
        public void requireSchema(String table) { snapshot(table); }
        public String quotedColumns() { return PROJECTION.quotedColumns(); }
        public String digest(List<Row> rows) { return PROJECTION.digest(rows); }
    }

    private static Row row(LocalDate date, Double value) { return new Row(date.atStartOfDay().toInstant(ZoneOffset.UTC), value); }
    private static String hash(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
