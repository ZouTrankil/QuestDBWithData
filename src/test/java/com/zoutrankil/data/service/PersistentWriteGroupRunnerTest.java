package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.storage.StockBasicWritePort;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistentWriteGroupRunnerTest {
    @TempDir Path root;
    static DatasetDefinition definition(String id) {
        var d = StockBasicDataset.DEFINITION;
        return new DatasetDefinition(id, d.schemaVersion(), d.provider(), d.owner(), id, d.objectKind(),
                d.columns(), d.businessKey(), d.dedupKey(), d.designatedTimestamp(), d.partition(), d.wal(),
                d.capabilities(), List.of(), "Test-only isolated write binding");
    }
    static class Port extends PreparedWriteAdapterTest.Port {
        boolean rejectPreflight, unknownDelivery;
        int preflightCalls;
        @Override public void preflight() {
            if (++preflightCalls > 1 && rejectPreflight)
                throw new IllegalStateException("Injected target failure after group preflight");
        }
        @Override public void send(List<StockBasicSnapshot> rows) {
            super.send(rows);
            if (unknownDelivery) throw new IllegalStateException("Injected missing acknowledgement");
        }
    }
    record Fixture(WriteGroupPlan plan, DatasetRegistry registry, Map<String,PreparedWriteAdapter<?,?>> adapters,
                   Port first, Port second, Path ledger) {}
    private Fixture fixture() {
        var a = definition("write_a"); var b = definition("write_b");
        var registry = new DatasetRegistry(List.of(() -> a, () -> b));
        var row = PreparedWriteAdapterTest.encode(PreparedWriteAdapterTest.stock());
        var plan = WriteGroupPlan.prepare(new WriteGroupRequest("batch-one", PreparedWriteAdapterTest.DAY,
                List.of(new WriteGroupRequest.Member("a", a.datasetId(), 1, "batch-a", List.of(row)),
                        new WriteGroupRequest.Member("b", b.datasetId(), 1, "batch-b", List.of(row)))), registry,
                Map.of("write_a", "target-a", "write_b", "target-b"));
        var first = new Port(); var second = new Port();
        Map<String,PreparedWriteAdapter<?,?>> adapters = Map.of("a", adapter(plan, "a", first, "target-a"),
                "b", adapter(plan, "b", second, "target-b"));
        return new Fixture(plan, registry, adapters, first, second, root.resolve("ledger.sqlite3"));
    }
    private PreparedWriteAdapter<StockBasicSnapshot,StockBasicSnapshotKey> adapter(WriteGroupPlan plan,
            String id, Port port, String target) {
        return new PreparedWriteAdapter<>(plan, id, PreparedWriteAdapterTest::decode, PreparedWriteAdapterTest::encode,
                StockBasicWritePort.CODEC, port, () -> target, root.resolve("inputs").resolve(id));
    }
    private PersistentWriteGroupRunner runner(Fixture f) {
        return new PersistentWriteGroupRunner(f.ledger(), root.resolve("receipts"), f.registry());
    }
    @Test void secondTargetFailureReopensLedgerAndReusesFirstWithFreshReadback() throws Exception {
        var f = fixture(); f.second().rejectPreflight = true;
        var first = runner(f).run("first-group", f.plan(), f.adapters(), null);
        assertEquals(SyncRunState.PARTIAL, first.state());
        assertEquals(1, f.first().sends); assertEquals(0, f.second().sends);
        var ledger = new SyncRunLedger(f.ledger());
        var oldSlots = ledger.groupMembers(first.runId());
        f.second().rejectPreflight = false;
        var resumed = runner(f).run("resumed-group", f.plan(), f.adapters(), first.runId());
        assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(1, f.first().sends); assertEquals(1, f.second().sends);
        assertTrue(resumed.members().getFirst().reused());
        assertEquals(oldSlots.getFirst().childRunId(), resumed.members().getFirst().childRunId());
        assertEquals(resumed.runId(), ledger.getRun(resumed.members().getLast().childRunId()).parentRunId());
        assertEquals(SyncRunState.PARTIAL, ledger.get(first.runId()).state());
        assertTrue(ledger.get(resumed.runId()).payloadJson().contains("reusedChildReadback"));
        var repeat = runner(f).run("repeat-group", f.plan(), f.adapters(), resumed.runId());
        assertEquals(SyncRunState.VERIFIED, repeat.state());
        assertTrue(repeat.members().stream().allMatch(SyncGroupRunner.MemberOutcome::reused));
        assertEquals(1, f.first().sends); assertEquals(1, f.second().sends);
    }
    @Test void missingCurrentValueRefusesReuseAndDoesNotRepairOrStartLaterMember() throws Exception {
        var f = fixture();
        var first = runner(f).run("first-group", f.plan(), f.adapters(), null);
        assertEquals(SyncRunState.VERIFIED, first.state());
        f.first().rows.clear();
        var resumed = runner(f).run("drift-group", f.plan(), f.adapters(), first.runId());
        assertEquals(SyncRunState.FAILED, resumed.state());
        assertEquals(1, f.first().sends); assertEquals(1, f.second().sends);
        assertTrue(f.first().rows.isEmpty());
    }
    @Test void mismatchedMemberBindingsFailBeforeAnyRunOrWrite() {
        var f = fixture();
        assertThrows(IllegalArgumentException.class,
                () -> runner(f).run("bad-group", f.plan(), Map.of("a", f.adapters().get("b"), "b", f.adapters().get("a")), null));
        assertEquals(0, f.first().sends); assertEquals(0, f.second().sends);
        assertFalse(Files.exists(f.ledger()));
    }
    @Test void unknownFirstDeliveryRetainsInDoubtAndCannotBeAutoReplayed() throws Exception {
        var f = fixture(); f.first().unknownDelivery = true;
        var result = runner(f).run("uncertain-group", f.plan(), f.adapters(), null);
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        assertEquals(1, f.first().sends);
        assertEquals(0, f.second().sends);
        var ledger = new SyncRunLedger(f.ledger());
        assertEquals(SyncRunState.IN_DOUBT, ledger.get(result.runId()).state());
        assertThrows(IllegalStateException.class,
                () -> runner(f).run("unsafe-replay", f.plan(), f.adapters(), result.runId()));
        assertEquals(1, f.first().sends);
    }
}
