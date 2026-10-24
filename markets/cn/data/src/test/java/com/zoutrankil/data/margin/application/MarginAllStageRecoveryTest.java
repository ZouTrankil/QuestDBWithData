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
import com.zoutrankil.data.margin.port.MarginAllTables;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;

class MarginAllStageRecoveryTest {
    @TempDir Path temp;
    static final String RUN = MarginAllProtocolContractTest.RUN;
    static final String TARGET = MarginAllProtocolContractTest.TARGET;
    static final LocalDate DAY = MarginAllProtocolContractTest.DAY;

    MarginAllProtocolContractTest.Harness fixture() throws Exception {
        var h = new MarginAllProtocolContractTest.Harness(temp);
        h.ledger.createChild("attempt", SyncRunLedger.Kind.ATTEMPT, RUN, RUN);
        h.ledger.transition("attempt", 0, SyncRunState.RUNNING, "{}");
        h.ledger.createChild("slice", SyncRunLedger.Kind.SLICE, RUN, "attempt");
        h.ledger.transition("slice", 0, SyncRunState.RUNNING, "{}");
        h.ledger.transition("slice", 1, SyncRunState.FETCHED, "{}");
        h.ledger.transition("slice", 2, SyncRunState.VALIDATED, "{}");
        h.ledger.transition("slice", 3, SyncRunState.SUBMITTED, "{}");
        h.ledger.transition("slice", 4, SyncRunState.IN_DOUBT, "{}");
        h.ledger.transition("attempt", 1, SyncRunState.IN_DOUBT, "{}");
        h.ledger.transition(RUN, 1, SyncRunState.IN_DOUBT, "{}");
        var locks = new DatasetIntervalLock(h.path);
        var lease = locks.acquire(RUN, new DatasetIntervalLock.Scope("margin_all", DAY, DAY));
        locks.retainInDoubt(lease);
        return h;
    }

    @Test
    void discardCancelsAllOriginalEntriesAtomicallyAndRequiresFullRefetch() throws Exception {
        var h = fixture();
        assertThrows(
                IllegalArgumentException.class,
                () -> h.ledger.transition("slice", 5, SyncRunState.CANCELLED, "{}"));
        byte[] raw = Files.readAllBytes(h.raw);
        h.publication.discardStageOnly(TARGET, RUN, true);
        assertEquals(1, h.tables.drops);
        assertEquals(0, h.tables.renames);
        assertEquals(h.before, h.tables.data.get(TARGET));
        for (var e : h.ledger.entries(RUN, null, 100)) {
            assertEquals(SyncRunState.CANCELLED, e.state());
            var p = JobDefinitionJson.mapper().readTree(e.payloadJson());
            assertTrue(p.path("discardedStage").asBoolean());
            assertFalse(p.path("dataVerified").asBoolean());
        }
        assertTrue(MarginAllStageRecovery.requiresRefetch(h.path, RUN));
        assertArrayEquals(raw, Files.readAllBytes(h.raw));
        long revision = h.ledger.get(RUN).revision();
        h.publication.discardStageOnly(TARGET, RUN, true);
        assertEquals(revision, h.ledger.get(RUN).revision());
        assertEquals(1, h.tables.drops);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "lateChild", "proof", "target", "stage"})
    void failureKeepsOriginalLeaseAndCannotCertifyData(String fault) throws Exception {
        var h = fixture();
        // 先构造账本落盘前的显式丢弃意图，失败时事务不可部分取消子项。
        if (fault.equals("event")) {
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + h.path);
                    var st = c.createStatement()) {
                st.executeUpdate("DELETE FROM sync_events WHERE entry_id='slice' AND revision=5");
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> h.publication.discardStageOnly(TARGET, RUN, true));
        } else {
            h.publication.discardStageOnly(TARGET, RUN, true);
            // 再次协调仍核对当前原表、stage和耐久凭证；不能因CANCELLED就跳过。
            switch (fault) {
                case "lateChild" -> {
                    try (var c = DriverManager.getConnection("jdbc:sqlite:" + h.path);
                            var st = c.createStatement()) {
                        st.executeUpdate(
                                "INSERT INTO sync_entries SELECT 'late',kind,run_id,parent_id,state,revision,payload_json,updated_at FROM sync_entries WHERE id='attempt'");
                    }
                }
                case "proof" ->
                        Files.writeString(h.root.resolve("ledger-discard-intent.json"), "{}");
                case "target" ->
                        h.tables.data.put(
                                TARGET,
                                MarginAllProtocolContractTest.snapshot(
                                        new Identity(1, "generation-1", 99), h.before.rows()));
                case "stage" -> h.tables.data.put(h.prepared.stage(), h.staged);
            }
            assertThrows(Exception.class, () -> h.publication.discardStageOnly(TARGET, RUN, true));
            assertEquals(SyncRunState.CANCELLED, h.ledger.get(RUN).state());
            return;
        }
        assertEquals(SyncRunState.IN_DOUBT, h.ledger.get(RUN).state());
        assertEquals(SyncRunState.IN_DOUBT, h.ledger.get("slice").state());
        assertNotNull(
                new DatasetIntervalLock(h.path)
                        .findOwned(RUN, new DatasetIntervalLock.Scope("margin_all", DAY, DAY)));
    }

    @Test
    void finalTargetCheckFailureKeepsLeaseAfterAtomicCancellation() throws Exception {
        var h = fixture();
        var fail = new AtomicBoolean(true);
        var tables = Mockito.mock(MarginAllTables.class, AdditionalAnswers.delegatesTo(h.tables));
        Mockito.doAnswer(
                        invocation -> {
                            if (h.ledger.get(RUN).state() == SyncRunState.CANCELLED
                                    && fail.getAndSet(false))
                                throw new IllegalStateException("夹具：取消后最终原表回读失败");
                            return h.tables.open(invocation.getArgument(0));
                        })
                .when(tables)
                .open(TARGET);
        var publication = new MarginAllPublication(tables, h.path);
        assertThrows(
                IllegalStateException.class, () -> publication.discardStageOnly(TARGET, RUN, true));
        assertEquals(SyncRunState.CANCELLED, h.ledger.get(RUN).state());
        var locks = new DatasetIntervalLock(h.path);
        var scope = new DatasetIntervalLock.Scope("margin_all", DAY, DAY);
        assertNotNull(locks.findOwned(RUN, scope));
        assertThrows(Exception.class, () -> MarginAllStageRecovery.requiresRefetch(h.path, RUN));
        byte[] originalIntent = Files.readAllBytes(h.root.resolve("ledger-discard-intent.json"));
        publication.discardStageOnly(TARGET, RUN, true);
        assertNull(locks.findOwned(RUN, scope));
        assertTrue(MarginAllStageRecovery.requiresRefetch(h.path, RUN));
        assertArrayEquals(
                originalIntent, Files.readAllBytes(h.root.resolve("ledger-discard-intent.json")));
    }

    @Test
    void missingStopOrLeaseCannotDiscard() throws Exception {
        var h = fixture();
        assertThrows(
                IllegalStateException.class,
                () -> h.publication.discardStageOnly(TARGET, RUN, false));
        var locks = new DatasetIntervalLock(h.path);
        var scope = new DatasetIntervalLock.Scope("margin_all", DAY, DAY);
        locks.releaseAfterReconciliation(locks.findOwned(RUN, scope), true, true);
        assertThrows(
                IllegalStateException.class,
                () -> h.publication.discardStageOnly(TARGET, RUN, true));
        assertEquals(0, h.tables.drops);
        assertFalse(MarginAllStageRecovery.requiresRefetch(h.path, RUN));
    }
}
