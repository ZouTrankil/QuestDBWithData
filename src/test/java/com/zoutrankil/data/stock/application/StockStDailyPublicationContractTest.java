package com.zoutrankil.data.stock.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.stock.domain.StockStDailyState.*;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.data.stock.port.StockStDailyTables;
import com.zoutrankil.data.stock.port.StockStDailyTarget;
import com.zoutrankil.data.stock.storage.StockStDailyStaging;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static com.zoutrankil.data.stock.application.StockStDailyPublication.State;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline fault acceptance through the real source receipts, publisher, recovery, journal and locks. */
class StockStDailyPublicationContractTest {
    @TempDir Path temp;
    private static final String TABLE = "java_d012_stk_st_daily_offline";
    private static final String STAGE = "java_d012_stk_st_daily_stage_offline";
    private static final String RUN = "st-offline";
    private static final String LOGICAL = "d012-logical-v1-" + "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2010, 1, 4);
    private enum Input { NONEMPTY, EMPTY, NO_SESSIONS }
    private enum RenameFault { BEFORE_FIRST, AFTER_FIRST, AFTER_SECOND }
    private enum EvidenceFault { STAGE, DAILY, ANNUAL }
    private static final class AbruptStop extends Error {}

    static Stream<Arguments> recoveryCases() {
        return Arrays.stream(Input.values()).flatMap(input -> Arrays.stream(RenameFault.values())
                .map(fault -> Arguments.of(input, fault)));
    }

    @ParameterizedTest @MethodSource("recoveryCases")
    void receiptBackedRecoveryFinishesOnlyMissingRenamesAndReleasesLocksInOrder(Input input, RenameFault fault)
            throws Exception {
        var f = new Fixture(temp, input, false);
        f.tables.fault = fault;
        var failure = assertThrows(StockStDailyPublication.Uncertain.class, f::publish);
        assertEquals(f.publisher.forRun(RUN).intent().id(), failure.publicationId());
        assertEquals(State.IN_DOUBT, f.publisher.forRun(RUN).state());
        assertEquals(RUN, f.mutexOwner());
        assertThrows(IllegalStateException.class, f.publisher::requireNoPendingPublication);
        f.retainUncertainRun();
        int renamed = f.tables.renames.size();
        assertThrows(IllegalStateException.class,
                () -> StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, false));
        assertEquals(renamed, f.tables.renames.size());
        f.tables.fault = null;

        var result = StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true);

        var desired = input == Input.NONEMPTY ? SyncRunState.VERIFIED : SyncRunState.VERIFIED_EMPTY;
        assertEquals(desired, result.state());
        assertEquals(input == Input.NONEMPTY ? 1 : 0, result.sourceRows());
        assertEquals(result.sourceRows(), result.verifiedRows());
        assertEquals(2, f.tables.renames.size());
        assertEquals(f.after, f.tables.snapshot(TABLE));
        assertEquals(f.before, f.tables.snapshot(f.publisher.forRun(RUN).intent().backup()));
        assertNull(f.tables.snapshotIfPresent(STAGE));
        assertEquals(f.outside, f.target.openTable().outside(DAY, DAY));
        f.assertVerifiedAndReleased(desired);
        assertEquals(input == Input.NO_SESSIONS ? 0 : 1, f.pages.calls, "Recovery never calls the source");
        verify(f.target, never()).newStaging();
        verify(f.target, never()).newWriter(anyString());
        var order = f.audit();
        assertBefore(order, "publication:VERIFIED", "mutex-free");
        assertBefore(order, "mutex-free", "entry:" + RUN + "-attempt:" + desired);
        assertBefore(order, "entry:" + RUN + "-attempt:" + desired, "entry:" + RUN + ":" + desired);
        assertBefore(order, "entry:" + RUN + ":" + desired, "lease-free:" + desired);
        f.publisher.finish(RUN, true);
        assertEquals(2, f.tables.renames.size(), "Publication acknowledgement may be repeated without another rename");
    }

    @ParameterizedTest @EnumSource(RenameFault.class)
    void hardStopWithFetchedEmptySliceStillRecoversFromCompleteReceipts(RenameFault fault) throws Exception {
        var f = new Fixture(temp, Input.EMPTY, true);
        f.tables.fault = fault; f.tables.hardStop = true;
        assertThrows(AbruptStop.class, f::publish);
        assertEquals(SyncRunState.FETCHED, f.ledger.get(RUN + "-slice").state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(RUN).state());
        assertNotNull(f.ownedLease());
        assertEquals(RUN, f.mutexOwner());
        f.tables.fault = null;

        var result = StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true);

        assertEquals(SyncRunState.VERIFIED_EMPTY, result.state());
        assertEquals(SyncRunState.VERIFIED_EMPTY, f.ledger.get(RUN + "-slice").state());
        assertEquals(2, f.tables.renames.size());
        assertEquals(f.after, f.tables.snapshot(TABLE));
        f.assertVerifiedAndReleased(SyncRunState.VERIFIED_EMPTY);
        assertEquals(1, f.pages.calls);
    }

    @Test void fetchedNonemptySliceCannotBePromotedByEmptySliceRecoveryException() throws Exception {
        var f = new Fixture(temp, Input.NONEMPTY, true);
        f.tables.fault = RenameFault.AFTER_FIRST; f.tables.hardStop = true;
        assertThrows(AbruptStop.class, f::publish);
        var before = f.publisher.forRun(RUN);
        f.tables.fault = null;

        assertThrows(IllegalStateException.class,
                () -> StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true));

        assertEquals(before, f.publisher.forRun(RUN));
        assertEquals(1, f.tables.renames.size());
        assertEquals(SyncRunState.FETCHED, f.ledger.get(RUN + "-slice").state());
        assertEquals(RUN, f.mutexOwner());
        assertNotNull(f.ownedLease());
    }

    @ParameterizedTest @ValueSource(strings = {"-0.5", "\"0\"", "false"})
    void malformedFetchedRowCountCannotBeCoercedIntoEmptyRecovery(String rowCountJson) throws Exception {
        var f = new Fixture(temp, Input.EMPTY, true);
        f.tables.fault = RenameFault.AFTER_FIRST; f.tables.hardStop = true;
        assertThrows(AbruptStop.class, f::publish);
        var before = f.publisher.forRun(RUN);
        // Corrupt only the durable event fixture: every stage/daily/annual receipt stays intact.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + f.path)) {
            String payload;
            try (var query = db.prepareStatement("SELECT payload_json FROM sync_events WHERE entry_id=? AND state='FETCHED'")) {
                query.setString(1, RUN + "-slice");
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next()); payload = rows.getString(1); assertFalse(rows.next());
                }
            }
            var json = JobDefinitionJson.mapper();
            var event = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(payload);
            event.set("returnedRows", json.readTree(rowCountJson));
            try (var update = db.prepareStatement("UPDATE sync_events SET payload_json=? WHERE entry_id=? AND state='FETCHED'")) {
                update.setString(1, json.writeValueAsString(event)); update.setString(2, RUN + "-slice");
                assertEquals(1, update.executeUpdate());
            }
        }
        f.tables.fault = null;

        var error = assertThrows(IllegalStateException.class,
                () -> StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true));

        assertEquals("D012 slice state is not publication-recoverable", error.getMessage());
        assertEquals(before, f.publisher.forRun(RUN));
        assertEquals(1, f.tables.renames.size());
        assertEquals(SyncRunState.FETCHED, f.ledger.get(RUN + "-slice").state());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(RUN).state());
        assertEquals(RUN, f.mutexOwner());
        assertNotNull(f.ownedLease());
    }

    @ParameterizedTest @EnumSource(EvidenceFault.class)
    void corruptedEmptyEvidenceAfterJournalCommitRejectsBeforeAnyRecoveryRename(EvidenceFault fault) throws Exception {
        var f = new Fixture(temp, Input.EMPTY, true);
        f.tables.fault = RenameFault.AFTER_FIRST; f.tables.hardStop = true;
        assertThrows(AbruptStop.class, f::publish);
        var before = f.publisher.forRun(RUN);
        Path tampered = switch (fault) {
            case STAGE -> Path.of(f.stage.receipt());
            case DAILY -> Path.of(f.sourcePages.getFirst().responseEvidence());
            case ANNUAL -> {
                var receipt = JobDefinitionJson.mapper().readTree(Path.of(f.sourcePages.getFirst().responseEvidence()).toFile());
                yield f.evidence.resolve("source").resolve(receipt.path("historyReceipts").get(0).path("file").asText());
            }
        };
        Files.writeString(tampered, "\n", StandardOpenOption.APPEND);
        f.tables.fault = null;

        var error = assertThrows(IllegalStateException.class,
                () -> StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true));

        assertTrue(error.getMessage().contains("changed"), error.getMessage());
        assertEquals(before, f.publisher.forRun(RUN));
        assertEquals(1, f.tables.renames.size());
        assertEquals(SyncRunState.FETCHED, f.ledger.get(RUN + "-slice").state());
        assertEquals(RUN, f.mutexOwner());
        assertNotNull(f.ownedLease());
    }

    @Test void cancellationBetweenRenamesLeavesOldMovedLayoutAndOwnedMutexForRecovery() throws Exception {
        var f = new Fixture(temp, Input.NONEMPTY, false);
        assertThrows(StockStDailyPublication.Uncertain.class, () -> f.publisher.publish(RUN, LOGICAL,
                f.physical, TABLE, f.prepared, f.stage, () -> !f.tables.renames.isEmpty()));
        assertEquals(1, f.tables.renames.size());
        assertNull(f.tables.snapshotIfPresent(TABLE));
        assertEquals(State.IN_DOUBT, f.publisher.forRun(RUN).state());
        assertEquals(RUN, f.mutexOwner());
        StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true);
        f.assertVerifiedAndReleased(SyncRunState.VERIFIED);
        assertEquals(2, f.tables.renames.size());
    }

    @Test void conflictingStageIdentityRetainsMutexAndLeaseWithoutRenaming() throws Exception {
        var f = new Fixture(temp, Input.NONEMPTY, false);
        f.tables.fault = RenameFault.AFTER_FIRST;
        assertThrows(StockStDailyPublication.Uncertain.class, f::publish);
        f.tables.fault = null;
        f.tables.tables.put(STAGE, new Physical(new Identity(999, "foreign-directory", 0), f.afterRows));
        var entry = f.publisher.forRun(RUN);
        var error = assertThrows(IllegalStateException.class,
                () -> StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true));
        assertEquals("D012 publication tables differ from exact journal identities/content", error.getMessage());
        assertEquals(entry, f.publisher.forRun(RUN));
        assertEquals(1, f.tables.renames.size());
        assertEquals(RUN, f.mutexOwner());
        assertNotNull(f.ownedLease());
    }

    @Test void successfulPublisherReleasesMutexButLeavesRunLeaseUntilLedgerAcknowledgement() throws Exception {
        var f = new Fixture(temp, Input.NONEMPTY, false);
        var result = f.publish();
        assertEquals(State.VERIFIED, result.entry().state());
        assertNull(f.mutexOwner());
        assertNotNull(f.ownedLease());
        assertEquals(SyncRunState.RUNNING, f.ledger.get(RUN).state());
        assertEquals(List.of(State.PREPARED, State.OLD_MOVED), f.tables.renameStates);
        assertEquals(List.of("publication:OLD_MOVED", "publication:PUBLISHED", "publication:VERIFIED", "mutex-free"), f.audit());
        StockStDailyRunRecovery.finishInterrupted(f.target, f.path, RUN, true);
        f.assertVerifiedAndReleased(SyncRunState.VERIFIED);
        assertEquals(2, f.tables.renames.size());
    }

    private static final class Fixture {
        final Path path, evidence;
        final SyncRunLedger ledger;
        final DatasetIntervalLock locks;
        final DatasetIntervalLock.Lease lease;
        final FakeTables tables;
        final StockStDailyTarget target = mock(StockStDailyTarget.class);
        final StockStDailyPublication publisher;
        final OfflinePages pages;
        final List<SyncJobRunner.Page<StockStDaily>> sourcePages = new ArrayList<>();
        final Snapshot before, after;
        final Content outside;
        final List<StockStDaily> afterRows;
        final String physical;
        final Prepared prepared;
        final Verified stage;
        Fixture(Path temp, Input input, boolean leaveFetched) throws Exception {
            path = temp.resolve("ledger.sqlite").toAbsolutePath(); evidence = temp.resolve("sync-evidence").resolve(RUN);
            ledger = new SyncRunLedger(path); locks = new DatasetIntervalLock(path);
            tables = new FakeTables(path);
            var outsideRows = List.of(new StockStDaily("600001.SH", DAY.minusDays(1)));
            var beforeRows = new ArrayList<>(outsideRows); beforeRows.add(new StockStDaily("600000.SH", DAY));
            tables.tables.put(TABLE, new Physical(new Identity(11, "old-directory", 2), beforeRows));
            before = tables.snapshot(TABLE); physical = tables.targetId(TABLE, before.identity());
            var dates = input == Input.NO_SESSIONS ? List.<LocalDate>of() : List.of(DAY);
            var request = StockStDailySyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.BACKFILL,
                    Map.of("targetId", LOGICAL, "physicalTargetId", physical,
                            "trade_dates", StockStDailySyncAdapter.encodeTradeDates(dates)), DAY, DAY, DAY);
            ledger.createRun(RUN, null, LOGICAL, request); transition(RUN, SyncRunState.RUNNING, "{}");
            ledger.createChild(RUN + "-attempt", SyncRunLedger.Kind.ATTEMPT, RUN, RUN);
            transition(RUN + "-attempt", SyncRunState.RUNNING, "{}");
            lease = locks.acquire(RUN, new DatasetIntervalLock.Scope("stk_st_daily", DAY, DAY));
            pages = new OfflinePages(input == Input.NONEMPTY);
            new StockStDailySource(pages, new StockStDailyMapper(), evidence.resolve("source"))
                    .fetchWindow(DAY, DAY, dates, sourcePages::add, () -> false);
            var sourceRows = sourcePages.stream().flatMap(page -> page.rows().stream()).toList();
            afterRows = new ArrayList<>(outsideRows); afterRows.addAll(sourceRows);
            tables.tables.put(STAGE, new Physical(new Identity(22, "new-directory", 3), afterRows));
            after = tables.snapshot(STAGE); outside = content(outsideRows);
            var window = content(sourceRows);
            var refs = sourcePages.stream().map(page -> Map.<String,Object>of("tradeDate", DAY.toString(),
                    "path", page.responseEvidence(), "fingerprint", page.sourceFingerprint(), "rows", page.rows().size())).toList();
            String combined = combinedFingerprint(sourcePages.stream().map(SyncJobRunner.Page::sourceFingerprint).toList());
            Path receipt = evidence.resolve("stage-verified.json");
            FileEvidenceStore.writeNew(receipt, JobDefinitionJson.mapper().writeValueAsBytes(Map.ofEntries(
                    Map.entry("target", TABLE), Map.entry("stage", STAGE), Map.entry("beforePhysicalTarget", physical),
                    Map.entry("stagePhysicalTarget", tables.targetId(STAGE, after.identity())),
                    Map.entry("before", before), Map.entry("after", after), Map.entry("windowFrom", DAY), Map.entry("windowTo", DAY),
                    Map.entry("preservedOutside", outside), Map.entry("authoritativeWindow", window),
                    Map.entry("sourceRows", sourceRows.size()), Map.entry("batches", sourceRows.isEmpty() ? 0 : 1),
                    Map.entry("sourceFingerprint", combined), Map.entry("sourceReceipts", refs), Map.entry("sourceComplete", true))));
            prepared = new Prepared(TABLE, physical, DAY, DAY, before, outside);
            stage = new Verified(STAGE, tables.targetId(STAGE, after.identity()), after, outside, window,
                    sourceRows.size(), sourceRows.isEmpty() ? 0 : 1, combined, receipt.toString());
            if (!sourcePages.isEmpty()) {
                var page = sourcePages.getFirst();
                ledger.createChild(RUN + "-slice", SyncRunLedger.Kind.SLICE, RUN, RUN + "-attempt");
                transition(RUN + "-slice", SyncRunState.RUNNING, "{}");
                transition(RUN + "-slice", SyncRunState.FETCHED, JobDefinitionJson.mapper().writeValueAsString(Map.of(
                        "cursor", page.cursor(), "returnedRows", page.rows().size(), "sourceComplete", true,
                        "sourceFingerprint", page.sourceFingerprint(), "responseEvidence", page.responseEvidence())));
                if (!leaveFetched) {
                    if (!page.rows().isEmpty()) transition(RUN + "-slice", SyncRunState.VALIDATED, "{}");
                    transition(RUN + "-slice", page.rows().isEmpty() ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED,
                            completionProof(page));
                }
            }
            when(target.tableName()).thenReturn(TABLE);
            when(target.newPublicationTables()).thenReturn(tables);
            when(target.openTable()).thenReturn(new StockStDailyTables.Table() {
                public Snapshot snapshot() throws Exception { return tables.snapshot(TABLE); }
                public Content window(LocalDate from, LocalDate to) throws Exception {
                    return content(tables.tables.get(TABLE).rows().stream().filter(r -> !r.timestamp().isBefore(from) && !r.timestamp().isAfter(to)).toList());
                }
                public Content outside(LocalDate from, LocalDate to) throws Exception {
                    return content(tables.tables.get(TABLE).rows().stream().filter(r -> r.timestamp().isBefore(from) || r.timestamp().isAfter(to)).toList());
                }
            });
            when(target.fingerprintWindow(anyList(), any(), any())).thenAnswer(call ->
                    StockStDailyStaging.fingerprintWindow(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
            publisher = new StockStDailyPublication(tables, path);
            installAudit();
        }
        StockStDailyPublication.Result publish() throws Exception {
            return publisher.publish(RUN, LOGICAL, physical, TABLE, prepared, stage, () -> false);
        }
        void transition(String id, SyncRunState state, String payload) throws Exception {
            ledger.transition(id, ledger.get(id).revision(), state, payload);
        }
        void retainUncertainRun() throws Exception {
            transition(RUN + "-attempt", SyncRunState.IN_DOUBT, "{}");
            transition(RUN, SyncRunState.IN_DOUBT, "{}");
            locks.retainInDoubt(lease);
        }
        DatasetIntervalLock.Lease ownedLease() { return locks.findOwned(RUN, new DatasetIntervalLock.Scope("stk_st_daily", DAY, DAY)); }
        String mutexOwner() throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var stmt = db.createStatement();
                 var rows = stmt.executeQuery("SELECT run_id FROM stk_st_daily_publication_mutex WHERE singleton=1")) {
                assertTrue(rows.next()); return rows.getString(1);
            }
        }
        void assertVerifiedAndReleased(SyncRunState desired) throws Exception {
            assertEquals(State.VERIFIED, publisher.forRun(RUN).state());
            assertEquals(desired, ledger.get(RUN).state());
            assertEquals(desired, ledger.get(RUN + "-attempt").state());
            if (!sourcePages.isEmpty()) assertEquals(desired, ledger.get(RUN + "-slice").state());
            assertNull(mutexOwner()); assertNull(ownedLease()); publisher.requireNoPendingPublication();
        }
        void installAudit() throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var s = db.createStatement()) {
                s.execute("CREATE TABLE publication_test_audit(event TEXT NOT NULL)");
                s.execute("CREATE TRIGGER audit_publication AFTER UPDATE OF state ON stk_st_daily_publications BEGIN INSERT INTO publication_test_audit VALUES('publication:'||NEW.state); END");
                s.execute("CREATE TRIGGER audit_mutex AFTER UPDATE OF run_id ON stk_st_daily_publication_mutex WHEN NEW.run_id IS NULL BEGIN INSERT INTO publication_test_audit VALUES('mutex-free'); END");
                s.execute("CREATE TRIGGER audit_entries AFTER UPDATE OF state ON sync_entries BEGIN INSERT INTO publication_test_audit VALUES('entry:'||NEW.id||':'||NEW.state); END");
                s.execute("CREATE TRIGGER audit_lease AFTER DELETE ON sync_interval_locks BEGIN INSERT INTO publication_test_audit SELECT 'lease-free:'||state FROM sync_entries WHERE id=OLD.run_id; END");
            }
        }
        List<String> audit() throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var s = db.createStatement();
                 var rows = s.executeQuery("SELECT event FROM publication_test_audit ORDER BY rowid")) {
                var events = new ArrayList<String>(); while (rows.next()) events.add(rows.getString(1)); return events;
            }
        }
        String completionProof(SyncJobRunner.Page<StockStDaily> page) throws Exception {
            int count = page.rows().size();
            return JobDefinitionJson.mapper().writeValueAsString(Map.of("sourceComplete", true,
                    "returnedRows", count, "submittedRows", count, "responseEvidence", page.responseEvidence(),
                    "verification", Map.of("passed", true, "expectedRows", count, "actualRows", count,
                            "matchedRows", count, "mismatchedRows", 0, "duplicateKeys", 0, "missingKeys", 0,
                            "readbackEvidence", stage.receipt(), "sourceFingerprint", page.sourceFingerprint(), "writerStopped", true)));
        }
    }

    private record Physical(Identity identity, List<StockStDaily> rows) {
        Physical { rows = List.copyOf(rows); }
        Snapshot snapshot() throws Exception { return new Snapshot(identity, content(rows)); }
    }
    private static final class FakeTables implements StockStDailyTables {
        final Map<String, Physical> tables = new HashMap<>();
        final List<String> renames = new ArrayList<>();
        final List<State> renameStates = new ArrayList<>();
        final Path ledger;
        RenameFault fault;
        boolean hardStop;
        FakeTables(Path ledger) { this.ledger = ledger; }
        public Snapshot snapshot(String table) throws Exception { return Objects.requireNonNull(tables.get(table), table).snapshot(); }
        public Snapshot snapshotIfPresent(String table) throws Exception { return tables.containsKey(table) ? snapshot(table) : null; }
        public String targetId(String table, Identity identity) {
            return "static-v2-" + String.format(Locale.ROOT, "%064x", identity.id());
        }
        public void rename(String from, String to) {
            if (fault == RenameFault.BEFORE_FIRST && renames.isEmpty()) fail();
            try {
                var entry = new StockStDailyPublication(this, ledger).forRun(RUN);
                assertNotNull(new DatasetIntervalLock(ledger).findOwned(RUN, new DatasetIntervalLock.Scope("stk_st_daily", DAY, DAY)));
                assertTrue(Set.of(SyncRunState.RUNNING, SyncRunState.IN_DOUBT).contains(new SyncRunLedger(ledger).get(RUN).state()));
                assertFalse(tables.containsKey(to));
                tables.put(to, Objects.requireNonNull(tables.remove(from), from));
                renames.add(from + "->" + to); renameStates.add(entry.state());
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new IllegalStateException(failure); }
            if (fault == RenameFault.AFTER_FIRST && renames.size() == 1
                    || fault == RenameFault.AFTER_SECOND && renames.size() == 2) fail();
        }
        void fail() { if (hardStop) throw new AbruptStop(); throw new IllegalStateException("rename acknowledgement lost"); }
    }
    private static final class OfflinePages extends TusharePageService {
        final boolean nonempty;
        int calls;
        OfflinePages(boolean nonempty) { super(null); this.nonempty = nonempty; }
        @Override public PageExecutor.Fetcher fetcher(PageContract contract, BooleanSupplier cancelled) {
            assertEquals(StockStDailySource.CONTRACT, contract);
            return params -> {
                assertFalse(cancelled.getAsBoolean()); calls++;
                assertEquals(Map.of("start_date", "20100101", "end_date", "20100104"), params);
                var json = JobDefinitionJson.mapper();
                Map<String, JsonNode> row = Map.of("ts_code", json.valueToTree("000001.SZ"),
                        "name", json.valueToTree("*ST fixture"), "start_date", json.valueToTree("20100101"),
                        "end_date", json.nullNode());
                return new PageExecutor.Page(nonempty ? List.of(row) : List.of(), null, false, null);
            };
        }
    }
    private static Content content(List<StockStDaily> rows) throws Exception {
        return StockStDailyStaging.fingerprintWindow(rows, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));
    }
    private static String combinedFingerprint(List<String> fingerprints) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String fingerprint : fingerprints) {
            digest.update(fingerprint.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void assertBefore(List<String> events, String first, String second) {
        assertTrue(events.contains(first) && events.contains(second) && events.indexOf(first) < events.indexOf(second), events.toString());
    }
}
