package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.storage.StockBasicWritePort;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class PreparedWriteAdapterTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.of(2026,9,29);
    static StockBasicSnapshot stock() {
        return new StockBasicSnapshot(DAY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                new StockBasic("000001.SZ", "000001", "sample", null, "bank", LocalDate.of(1991,4,3)));
    }
    static DatasetValues encode(StockBasicSnapshot row) {
        var values = new LinkedHashMap<String,Object>(); var stock = row.stock();
        values.put("snapshot_ts", row.snapshotTimestamp()); values.put("ts_code", stock.tsCode());
        values.put("symbol", stock.symbol()); values.put("name", stock.name()); values.put("area", stock.area());
        values.put("industry", stock.industry()); values.put("list_date", stock.listDate());
        return new DatasetValues(values);
    }
    static StockBasicSnapshot decode(DatasetValues row) {
        return new StockBasicSnapshot(row.get("snapshot_ts", Instant.class), new StockBasic(
                row.get("ts_code", String.class), row.get("symbol", String.class), row.get("name", String.class),
                row.get("area", String.class), row.get("industry", String.class), row.get("list_date", LocalDate.class)));
    }
    static class Port implements VerifiedBatchExecutor.Port<StockBasicSnapshot,StockBasicSnapshotKey> {
        Map<StockBasicSnapshotKey,StockBasicSnapshot> rows = new LinkedHashMap<>();
        int sends;
        public void preflight() {}
        public void send(List<StockBasicSnapshot> values) { sends++; values.forEach(r -> rows.put(r.key(), r)); }
        public List<StockBasicSnapshot> readback(List<StockBasicSnapshotKey> keys) {
            return keys.stream().map(rows::get).filter(Objects::nonNull).toList();
        }
        public boolean walSettled() { return true; }
    }
    private WriteGroupPlan plan(boolean empty) {
        return WriteGroupPlan.prepare(new WriteGroupRequest("group-batch", DAY, List.of(new WriteGroupRequest.Member(
                "stock", "stock_basic_snapshot", 1, "member-batch", empty ? List.of() : List.of(encode(stock()))))),
                new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)), Map.of("stock_basic_snapshot", "target"));
    }
    private PreparedWriteAdapter<StockBasicSnapshot,StockBasicSnapshotKey> adapter(WriteGroupPlan plan, Port port,
            Function<DatasetValues,StockBasicSnapshot> decode) {
        return new PreparedWriteAdapter<>(plan, "stock", decode, PreparedWriteAdapterTest::encode,
                StockBasicWritePort.CODEC, port, () -> "target", root.resolve("evidence"));
    }
    @Test void preparedRowsUseVerifiedRunnerAndResumeWithoutAnotherSend() throws Exception {
        Path path = root.resolve("ledger.sqlite3"); var ledger = new SyncRunLedger(path); var port = new Port();
        var adapter = adapter(plan(false), port, PreparedWriteAdapterTest::decode);
        var runner = new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger, new DatasetIntervalLock(path));
        var first = runner.run("first", null, "target", adapter.request(), adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, first.state()); assertEquals(1, port.sends);
        var second = new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(new SyncRunLedger(path),
                new DatasetIntervalLock(path)).resume("resumed", "first", "target", adapter.request(), adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED, second.state()); assertEquals(1, second.reusedRows());
        assertEquals(1, port.sends); assertEquals(stock(), port.rows.get(stock().key()));
        try (var files = Files.list(root.resolve("evidence"))) { assertEquals(2, files.count()); }
    }
    @Test void lossyOwnerMappingAndTargetDriftAreRejectedBeforeSending() throws Exception {
        var port = new Port();
        var lossy = adapter(plan(false), port, row -> {
            var original = decode(row); var s = original.stock();
            return new StockBasicSnapshot(original.snapshotTimestamp(), new StockBasic(s.tsCode(), s.symbol(),
                    "changed", s.area(), s.industry(), s.listDate()));
        });
        assertThrows(IllegalArgumentException.class, () -> lossy.preflight(lossy.request()));
        var wrongTarget = new PreparedWriteAdapter<>(plan(false), "stock", PreparedWriteAdapterTest::decode,
                PreparedWriteAdapterTest::encode, StockBasicWritePort.CODEC, port, () -> "another-target", root);
        assertThrows(IllegalStateException.class, () -> wrongTarget.preflight(wrongTarget.request()));
        assertEquals(0, port.sends);
    }
    @Test void emptyPreparedBatchCompletesWithoutAWrite() throws Exception {
        Path path = root.resolve("empty.sqlite3"); var ledger = new SyncRunLedger(path); var port = new Port();
        var adapter = adapter(plan(true), port, PreparedWriteAdapterTest::decode);
        var result = new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger, new DatasetIntervalLock(path))
                .run("empty", null, "target", adapter.request(), adapter, () -> false);
        assertEquals(SyncRunState.VERIFIED_EMPTY, result.state()); assertEquals(0, port.sends);
    }
}
