package com.zoutrankil.data.margin.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.margin.domain.MarginAllState.Identity;
import com.zoutrankil.data.margin.domain.MarginAllState.Snapshot;
import com.zoutrankil.data.margin.port.MarginAllTables;
import com.zoutrankil.data.margin.port.MarginAllWriteSession;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

class MarginAllRunRecoveryTest {
    @TempDir Path temp;
    static final String RUN = MarginAllProtocolContractTest.RUN;
    static final LocalDate DAY = MarginAllProtocolContractTest.DAY;

    record Fixture(
            MarginAllProtocolContractTest.Harness h,
            String attempt,
            List<String> slices,
            DatasetIntervalLock locks,
            DatasetIntervalLock.Scope scope) {}

    Fixture fixture(boolean empty) throws Exception {
        return fixture(empty, true);
    }

    Fixture fixture(boolean empty, boolean formal) throws Exception {
        var h = new MarginAllProtocolContractTest.Harness(temp, formal, !empty, empty);
        String attempt = "attempt";
        var slices = new ArrayList<String>();
        h.ledger.createChild(attempt, SyncRunLedger.Kind.ATTEMPT, RUN, RUN);
        h.ledger.transition(attempt, 0, SyncRunState.RUNNING, "{}");
        for (var page : h.pages) {
            String id = "slice-" + page.cursor();
            slices.add(id);
            h.ledger.createChild(id, SyncRunLedger.Kind.SLICE, RUN, attempt);
            h.ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
            String fetched =
                    JobDefinitionJson.mapper()
                            .writeValueAsString(
                                    Map.of(
                                            "returnedRows",
                                            page.rows().size(),
                                            "sourceFingerprint",
                                            page.sourceFingerprint(),
                                            "responseEvidence",
                                            page.responseEvidence(),
                                            "cursor",
                                            page.cursor()));
            h.ledger.transition(id, 1, SyncRunState.FETCHED, fetched);
            if (!page.rows().isEmpty()) {
                h.ledger.transition(id, 2, SyncRunState.VALIDATED, fetched);
                h.ledger.transition(id, 3, SyncRunState.SUBMITTED, "{}");
                h.ledger.transition(id, 4, SyncRunState.ACKNOWLEDGED, "{}");
                h.ledger.transition(
                        id,
                        5,
                        SyncRunState.VERIFIED,
                        proof(page.rows().size(), page.sourceFingerprint()));
            }
        }
        var locks = new DatasetIntervalLock(h.path);
        var scope = new DatasetIntervalLock.Scope("margin_all", DAY, empty ? DAY : DAY.plusDays(1));
        var lease = locks.acquire(RUN, scope);
        assertNotNull(lease);
        h.tables.fault = MarginAllProtocolContractTest.Fault.AFTER_FIRST;
        assertThrows(MarginAllPublication.Uncertain.class, () -> h.publish(() -> false));
        h.tables.fault = null;
        h.ledger.transition(attempt, 1, SyncRunState.IN_DOUBT, "{}");
        h.ledger.transition(RUN, 1, SyncRunState.IN_DOUBT, "{}");
        locks.retainInDoubt(lease);
        return new Fixture(h, attempt, slices, locks, scope);
    }

    @Test
    void verifiedPhysicalPublicationStillRequiresRecoveryWhenCompletionEpilogueFails()
            throws Exception {
        var h = new MarginAllProtocolContractTest.Harness(temp, true);
        h.publish(() -> false);
        var adapter =
                new MarginAllSyncAdapter(
                        Mockito.mock(MarginAllSource.class),
                        Mockito.mock(MarginAllWriteSession.class),
                        h.staging,
                        h.publication,
                        h.root,
                        h.tables);
        assertEquals(ReferencePublicationJournal.State.VERIFIED, h.journal.forRun(RUN).state());
        assertTrue(adapter.recoveryRequired(RUN));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitFinalizationClosesOriginalChildrenThenReleasesOnlyItsLease(boolean empty)
            throws Exception {
        var f = fixture(empty);
        var h = f.h();
        assertThrows(
                IllegalArgumentException.class,
                () -> MarginAllRunRecovery.reconcile(h.tables, h.path, "margin_all", RUN, false));
        assertThrows(
                IllegalStateException.class,
                () -> MarginAllRunRecovery.reconcile(h.tables, h.path, "margin_all", RUN, true));
        h.publication.finish(RUN, true);
        int renames = h.tables.renames;
        String rawHash = FileEvidenceStore.sha256(Files.readAllBytes(h.raw));
        var result = MarginAllRunRecovery.reconcile(h.tables, h.path, "margin_all", RUN, true);
        var end = empty ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
        assertEquals(end, result.get("state"));
        assertEquals(end, h.ledger.get(RUN).state());
        assertEquals(end, h.ledger.get(f.attempt()).state());
        assertNull(f.locks().findOwned(RUN, f.scope()));
        for (String slice : f.slices())
            assertTrue(
                    Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                            .contains(h.ledger.get(slice).state()));
        long revision = h.ledger.get(RUN).revision();
        assertEquals(
                result, MarginAllRunRecovery.reconcile(h.tables, h.path, "margin_all", RUN, true));
        assertEquals(revision, h.ledger.get(RUN).revision());
        assertEquals(renames, h.tables.renames);
        assertEquals(rawHash, FileEvidenceStore.sha256(Files.readAllBytes(h.raw)));
        assertEquals(h.staged, h.tables.data.get("margin_all"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "raw",
                "stageReceipt",
                "foreignParent",
                "eventRevision",
                "extraAttempt",
                "missingLease",
                "wrongTarget"
            })
    void invalidAuthorityCannotAdvanceTheOriginalLedger(String fault) throws Exception {
        var f = fixture(false);
        var h = f.h();
        h.publication.finish(RUN, true);
        switch (fault) {
            case "raw" -> Files.writeString(h.raw, "{}");
            case "stageReceipt" -> Files.writeString(Path.of(h.verified.receipt()), "{}");
            case "extraAttempt" -> {
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + h.path);
                        var q =
                                c.prepareStatement(
                                        "INSERT INTO sync_entries SELECT 'extra',kind,run_id,parent_id,state,revision,payload_json,updated_at FROM sync_entries WHERE id=?")) {
                    q.setString(1, f.attempt());
                    assertEquals(1, q.executeUpdate());
                }
            }
            case "missingLease" ->
                    f.locks()
                            .releaseAfterReconciliation(
                                    f.locks().findOwned(RUN, f.scope()), true, true);
            case "foreignParent", "eventRevision" -> {
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + h.path);
                        var q =
                                c.prepareStatement(
                                        fault.equals("foreignParent")
                                                ? "UPDATE sync_entries SET parent_id='foreign' WHERE id=?"
                                                : "UPDATE sync_entries SET revision=revision+1 WHERE id=?")) {
                    q.setString(1, f.slices().getFirst());
                    assertEquals(1, q.executeUpdate());
                }
            }
        }
        var before = h.ledger.get(RUN);
        int renames = h.tables.renames;
        assertThrows(
                Exception.class,
                () ->
                        MarginAllRunRecovery.reconcile(
                                h.tables,
                                h.path,
                                fault.equals("wrongTarget")
                                        ? "java_d028_margin_all_other"
                                        : "margin_all",
                                RUN,
                                true));
        assertEquals(before, h.ledger.get(RUN));
        assertEquals(SyncRunState.IN_DOUBT, h.ledger.get(f.attempt()).state());
        assertEquals(renames, h.tables.renames);
        assertFalse(Files.exists(h.root.resolve("ledger-finalization-intent.json")));
        if (!fault.equals("missingLease"))
            assertTrue(f.locks().findOwned(RUN, f.scope()).inDoubt());
    }

    @Test
    void originalIsolatedV1RequestAlsoFinalizesWithoutRebinding() throws Exception {
        var f = fixture(false, false);
        var h = f.h();
        h.publication.finish(RUN, true);
        var frozen = h.ledger.getRun(RUN).frozenJson();
        var result =
                MarginAllRunRecovery.reconcile(
                        h.tables, h.path, MarginAllProtocolContractTest.TARGET, RUN, true);
        assertEquals(SyncRunState.VERIFIED, result.get("state"));
        assertEquals(1, h.ledger.getRun(RUN).jobVersion());
        assertEquals(frozen, h.ledger.getRun(RUN).frozenJson());
        assertNull(f.locks().findOwned(RUN, f.scope()));
    }

    @Test
    void crashAfterLedgerCertificationRetainsTheLeaseAndResumesTheSameIntent() throws Exception {
        var f = fixture(false);
        var h = f.h();
        h.publication.finish(RUN, true);
        var fail = new AtomicBoolean(true);
        MarginAllTables guarded =
                new MarginAllTables() {
                    public Table open(String name) {
                        var t = h.tables.open(name);
                        return new Table() {
                            public Identity preflight() {
                                return t.preflight();
                            }

                            public Snapshot snapshot() throws Exception {
                                if (fail.get()
                                        && h.ledger.get(RUN).state() == SyncRunState.VERIFIED)
                                    throw new IllegalStateException("夹具最终复核失败");
                                return t.snapshot();
                            }

                            public Snapshot outside(LocalDate a, LocalDate b) throws Exception {
                                return t.outside(a, b);
                            }

                            public Snapshot window(LocalDate a, LocalDate b) throws Exception {
                                return t.window(a, b);
                            }
                        };
                    }

                    public String logicalTargetId(String t) {
                        return h.tables.logicalTargetId(t);
                    }

                    public String physicalTargetId(String t, Identity i) {
                        return h.tables.physicalTargetId(t, i);
                    }

                    public int tableCount(String t) {
                        return h.tables.tableCount(t);
                    }

                    public void rename(String a, String b) {
                        throw new AssertionError("收尾不能重命名");
                    }

                    public DiscardSession newDiscardSession() {
                        throw new AssertionError("收尾不能清理stage");
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> MarginAllRunRecovery.reconcile(guarded, h.path, "margin_all", RUN, true));
        assertEquals(SyncRunState.VERIFIED, h.ledger.get(RUN).state());
        assertNotNull(f.locks().findOwned(RUN, f.scope()));
        byte[] intent = Files.readAllBytes(h.root.resolve("ledger-finalization-intent.json"));
        fail.set(false);
        MarginAllRunRecovery.reconcile(guarded, h.path, "margin_all", RUN, true);
        assertArrayEquals(
                intent, Files.readAllBytes(h.root.resolve("ledger-finalization-intent.json")));
        assertNull(f.locks().findOwned(RUN, f.scope()));
    }

    private static String proof(int rows, String hash) throws Exception {
        return JobDefinitionJson.mapper()
                .writeValueAsString(
                        Map.of(
                                "verification",
                                Map.of(
                                        "passed",
                                        true,
                                        "expectedRows",
                                        rows,
                                        "actualRows",
                                        rows,
                                        "matchedRows",
                                        rows,
                                        "mismatchedRows",
                                        0,
                                        "duplicateKeys",
                                        0,
                                        "missingKeys",
                                        0,
                                        "sourceFingerprint",
                                        hash,
                                        "readbackEvidence",
                                        "夹具已验证批次")));
    }
}
