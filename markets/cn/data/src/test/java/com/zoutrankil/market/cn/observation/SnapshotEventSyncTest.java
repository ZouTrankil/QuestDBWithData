package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.SnapshotEventSyncOwner;
import com.zoutrankil.market.cn.observation.mapper.SnapshotEventDefinition;
import com.zoutrankil.market.cn.observation.port.SnapshotEventTables;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

class SnapshotEventSyncTest {
    @TempDir Path root;
    static final LocalDate DATE = LocalDate.of(2026, 9, 18), LOGICAL = LocalDate.of(2026, 10, 21);

    static Map<String, JsonNode> raw(String dataset, double value) {
        var json = JobDefinitionJson.mapper();
        var row = new TreeMap<String, JsonNode>();
        for (var c : SnapshotEventDefinition.definition(dataset).columns()) {
            String n = c.sourceName();
            if (n.startsWith("derived:")) continue;
            Object v =
                    n.equals("ts_code")
                            ? "000001.SZ"
                            : n.equals(SnapshotEventDefinition.dateField(dataset))
                                    ? "20260918"
                                    : c.storageType() == DatasetDefinition.StorageType.DOUBLE
                                            ? value
                                            : Set.of("end_date", "exp_date").contains(n)
                                                    ? null
                                                    : n.equals("proc") ? "实施" : "机构专用";
            row.put(n, json.valueToTree(v));
        }
        return row;
    }

    static Map<String, Object> physical(String dataset, LocalDate date, double value) {
        var row = new TreeMap<String, Object>();
        for (var c : SnapshotEventDefinition.definition(dataset).columns()) {
            String n = c.storageName();
            Object v =
                    n.equals("ts_code")
                            ? "000001.SZ"
                            : n.equals(SnapshotEventDefinition.dateField(dataset))
                                    ? date.atStartOfDay(ZoneOffset.UTC).toInstant().toString()
                                    : n.equals(SnapshotEventDefinition.ORDINAL)
                                            ? 1L
                                            : c.storageType()
                                                            == DatasetDefinition.StorageType.DOUBLE
                                                    ? value
                                                    : Set.of("end_date", "exp_date").contains(n)
                                                            ? null
                                                            : n.equals("proc") ? "实施" : "机构专用";
            row.put(n, v);
        }
        return row;
    }

    static class Memory implements SnapshotEventTables {
        final String dataset;
        final Map<String, Snapshot> objects = new HashMap<>();
        long next = 1;
        int sends, renames, crashRename;
        boolean partialStage, dependency;

        Memory(String dataset) {
            this.dataset = dataset;
            put(
                    dataset,
                    1,
                    List.of(
                            physical(dataset, DATE.minusDays(1), 50d),
                            physical(dataset, DATE, 1d)));
        }

        Snapshot put(String table, int version, List<Map<String, Object>> rows) {
            long id = next++;
            var values = SnapshotEventDefinition.assignOrdinals(dataset, rows);
            var s =
                    new Snapshot(
                            new Identity(id, "dir" + id, "target" + id, version),
                            values,
                            SnapshotEventDefinition.fingerprint(values),
                            1L);
            objects.put(table, s);
            return s;
        }

        public void requireNoNativeDependencies() {
            if (dependency) throw new IllegalStateException("存在关联MV");
        }

        public Snapshot snapshot(String table) {
            return Objects.requireNonNull(objects.get(table), "表不存在");
        }

        public Snapshot snapshotIfPresent(String table) {
            return objects.get(table);
        }

        public Snapshot stage(
                String table, List<Map<String, Object>> rows, Path path, BooleanSupplier cancelled)
                throws Exception {
            FileEvidenceStore.writeNewDurable(path, "{}".getBytes());
            sends++;
            var snapshot = put(table, 2, partialStage ? rows.subList(0, 1) : rows);
            if (partialStage) throw new IllegalStateException("stage 发送未决");
            return snapshot;
        }

        public void rename(String from, String to) {
            if (objects.containsKey(to)) throw new IllegalStateException("目标已经存在");
            objects.put(to, objects.remove(from));
            if (++renames == crashRename) throw new IllegalStateException("模拟重命名已提交但确认丢失");
        }
    }

    class Fixture {
        final String dataset;
        final Memory tables;
        final SnapshotEventSyncOwner owner;
        final AtomicInteger calls = new AtomicInteger();
        List<Map<String, JsonNode>> rows;
        boolean fail;

        Fixture(String dataset) {
            this.dataset = dataset;
            tables = new Memory(dataset);
            rows = List.of(raw(dataset, 2d), raw(dataset, 2d), raw(dataset, 3d));
            var pages = mock(TusharePageService.class);
            when(pages.fetcher(any(), any()))
                    .thenReturn(
                            params -> {
                                calls.incrementAndGet();
                                assertEquals(
                                        Map.of(
                                                SnapshotEventDefinition.dateField(dataset),
                                                "20260918"),
                                        params);
                                if (fail) throw new IllegalStateException("来源中断");
                                return new PageExecutor.Page(rows, null, false, null);
                            });
            owner =
                    new SnapshotEventSyncOwner(
                            dataset, tables, pages, root.resolve(dataset + ".sqlite"));
        }

        SyncJobRunner.Result run(boolean migrate) throws Exception {
            return owner.run(owner.plan(DATE, LOGICAL, migrate));
        }
    }

    @Test
    void duplicateRowsNullableDatesRevisionsAndOutsideRowsSurvive() throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS) {
            var f = new Fixture(dataset);
            assertThrows(IllegalArgumentException.class, () -> f.run(false));
            var first = f.run(true);
            assertEquals(SyncRunState.VERIFIED, first.state());
            assertEquals(4, f.tables.snapshot(dataset).rows().size());
            assertEquals(2, f.tables.snapshot(dataset).identity().schemaVersion());
            assertEquals(1, f.tables.sends);
            f.rows = List.of(raw(dataset, 4d), raw(dataset, 4d));
            assertEquals(SyncRunState.VERIFIED, f.run(false).state());
            var actual = f.tables.snapshot(dataset).rows();
            assertEquals(3, actual.size());
            assertEquals(
                    2,
                    actual.stream()
                            .filter(r -> SnapshotEventDefinition.date(dataset, r).equals(DATE))
                            .count());
            assertEquals(
                    50d,
                    actual.stream()
                            .filter(
                                    r ->
                                            SnapshotEventDefinition.date(dataset, r)
                                                    .equals(DATE.minusDays(1)))
                            .findFirst()
                            .orElseThrow()
                            .get(dataset.equals("repurchase") ? "vol" : "price"));
            var before = f.tables.snapshot(dataset);
            f.rows = List.of();
            assertEquals(SyncRunState.FAILED, f.run(false).state());
            assertEquals(before, f.tables.snapshot(dataset));
            assertEquals(2, f.tables.sends);
        }
    }

    @Test
    void everyRenameCrashKeepsOriginalLockAndOnlyContinuesFrozenTail() throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS)
            for (int crash : List.of(1, 2)) {
                var f = new Fixture(dataset);
                f.tables.crashRename = crash;
                var run = f.run(true);
                assertEquals(SyncRunState.IN_DOUBT, run.state());
                assertNotNull(
                        new DatasetIntervalLock(root.resolve(dataset + ".sqlite"))
                                .findOwned(
                                        run.runId(), DatasetIntervalLock.Scope.allDates(dataset)));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> f.owner.reconcile(run.runId(), false));
                int calls = f.calls.get();
                var result = f.owner.reconcile(run.runId(), true);
                assertEquals(4, result.rows().size());
                assertEquals(calls, f.calls.get());
                assertEquals(1, f.tables.sends);
                assertEquals(
                        SyncRunState.VERIFIED,
                        SyncRunLedger.openReadOnly(root.resolve(dataset + ".sqlite"))
                                .get(run.runId())
                                .state());
                assertNull(
                        new DatasetIntervalLock(root.resolve(dataset + ".sqlite"))
                                .findOwned(
                                        run.runId(), DatasetIntervalLock.Scope.allDates(dataset)));
                // 同一数据集下一次演练用独立账本，保留上一份恢复原件。
                Path saved = root.resolve(dataset + "-crash" + crash + ".sqlite");
                Files.move(root.resolve(dataset + ".sqlite"), saved);
            }
    }

    @Test
    void sourceCapOrMissingFieldCannotCreateStageAndPartialStageNeverReplays() throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS) {
            var f = new Fixture(dataset);
            f.rows =
                    Collections.nCopies(
                            SnapshotEventDefinition.sourceLimit(dataset), raw(dataset, 2d));
            assertEquals(SyncRunState.FAILED, f.run(true).state());
            assertEquals(0, f.tables.sends);
            var invalid = new TreeMap<>(raw(dataset, 1d));
            invalid.remove("amount");
            f.rows = List.of(invalid);
            assertEquals(SyncRunState.FAILED, f.run(true).state());
            assertEquals(0, f.tables.sends);
            f.rows = List.of(raw(dataset, 2d));
            f.tables.partialStage = true;
            var run = f.run(true);
            assertEquals(SyncRunState.IN_DOUBT, run.state());
            assertEquals(0, f.tables.renames);
            assertThrows(IllegalStateException.class, () -> f.owner.reconcile(run.runId(), true));
            assertEquals(1, f.tables.sends);
        }
    }

    @Test
    void frozenSourceFailureCanResumeButChangedTargetOrRawCannotBeAdopted() throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS) {
            var f = new Fixture(dataset);
            f.fail = true;
            var failed = f.run(true);
            assertEquals(SyncRunState.FAILED, failed.state());
            f.fail = false;
            assertEquals(SyncRunState.VERIFIED, f.owner.resume(failed.runId()).state());
            f.tables.crashRename = f.tables.renames + 1;
            var pending = f.run(false);
            assertEquals(SyncRunState.IN_DOUBT, pending.state());
            assertThrows(IllegalStateException.class, () -> f.owner.resume(pending.runId()));
            var ledger = SyncRunLedger.openReadOnly(root.resolve(dataset + ".sqlite"));
            var current = ledger.get(pending.runId());
            Path raw =
                    root.resolve("sync-evidence")
                            .resolve(pending.runId())
                            .resolve("source-raw.json");
            Files.writeString(raw, "{}");
            assertThrows(
                    IllegalStateException.class, () -> f.owner.reconcile(pending.runId(), true));
            assertEquals(current, ledger.get(pending.runId()));
        }
    }

    @Test
    void nativeDependencyBlocksBeforeSourceOrStageAndEqualCountsCannotHideMutation()
            throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS) {
            var f = new Fixture(dataset);
            f.tables.dependency = true;
            assertEquals(SyncRunState.FAILED, f.run(true).state());
            assertEquals(0, f.calls.get());
            assertEquals(0, f.tables.sends);
            f.tables.dependency = false;
            f.tables.crashRename = 2;
            var pending = f.run(true);
            assertEquals(SyncRunState.IN_DOUBT, pending.state());
            var old = f.tables.snapshot(dataset);
            var rows = new ArrayList<>(old.rows());
            var changed = new TreeMap<>(rows.getFirst());
            changed.put("amount", 999d);
            rows.set(0, changed);
            f.tables.objects.put(
                    dataset,
                    new SnapshotEventTables.Snapshot(
                            old.identity(),
                            rows,
                            SnapshotEventDefinition.fingerprint(rows),
                            old.writerTxn() + 1));
            assertThrows(
                    IllegalStateException.class, () -> f.owner.reconcile(pending.runId(), true));
            assertNotNull(
                    new DatasetIntervalLock(root.resolve(dataset + ".sqlite"))
                            .findOwned(
                                    pending.runId(), DatasetIntervalLock.Scope.allDates(dataset)));
        }
    }

    @Test
    void completeEmptyScopeCanPublishAndRecoverWithoutInventingEvents() throws Exception {
        for (String dataset : SnapshotEventDefinition.DATASETS) {
            var f = new Fixture(dataset);
            f.tables.put(dataset, 1, List.of(physical(dataset, DATE.minusDays(1), 50d)));
            f.rows = List.of();
            f.tables.crashRename = 1;
            var run = f.run(true);
            assertEquals(SyncRunState.IN_DOUBT, run.state());
            assertEquals(1, f.owner.reconcile(run.runId(), true).rows().size());
            assertEquals(
                    SyncRunState.VERIFIED_EMPTY,
                    SyncRunLedger.openReadOnly(root.resolve(dataset + ".sqlite"))
                            .get(run.runId())
                            .state());
        }
    }
}
