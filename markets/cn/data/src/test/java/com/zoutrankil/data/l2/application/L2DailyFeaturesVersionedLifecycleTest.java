package com.zoutrankil.data.l2.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.cli.L2Commands;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.l2.L2DailyFeatureField;
import com.zoutrankil.data.domain.l2.L2DailyFeatures;
import com.zoutrankil.data.domain.l2.L2DailyFeaturesKey;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

class L2DailyFeaturesVersionedLifecycleTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void restoresAndResumesOriginalVersionAfterDefaultFlipWithoutReplanning(int version)
            throws Exception {
        var h = new Harness(version);
        h.add(DAY);
        h.add(DAY.plusDays(1));
        h.failAfterFirstPage = true;
        var plan = h.plan(DAY, DAY.plusDays(1));
        var failed = h.service(version).run(plan);
        assertEquals(SyncRunState.PARTIAL, failed.state());
        var ledger = SyncRunLedger.openReadOnly(h.path);
        String frozen = ledger.getRun(failed.runId()).frozenJson();
        h.failAfterFirstPage = false;
        var flipped = h.service(3 - version);
        clearInvocations(h.calendars);
        var restored = flipped.restorePlan(failed.runId());
        assertEquals(
                SyncRequestIdentity.snapshotJson(plan.request()),
                SyncRequestIdentity.snapshotJson(restored.request()));
        verifyNoInteractions(h.calendars);
        int sent = h.sends;
        var resumed = flipped.resume(restored, failed.runId());
        assertEquals(SyncRunState.VERIFIED, resumed.state());
        assertEquals(1, resumed.reusedRows());
        assertEquals(sent + 1, h.sends);
        assertEquals(frozen, ledger.getRun(failed.runId()).frozenJson());
        assertEquals(frozen, ledger.getRun(resumed.runId()).frozenJson());
        assertEquals(version, ledger.getRun(resumed.runId()).jobVersion());
        String payload = ledger.get(resumed.runId()).payloadJson();
        int before = h.sends;
        var report = Path.of(flipped.revalidate(resumed.runId()));
        assertTrue(Files.isRegularFile(report));
        var proof = JobDefinitionJson.mapper().readTree(Files.readString(report));
        assertTrue(proof.path("passed").asBoolean());
        assertTrue(proof.path("sourceEvidence").asText().contains("stableVersion"));
        assertEquals(before, h.sends);
        assertEquals(payload, ledger.get(resumed.runId()).payloadJson());
        assertThrows(IllegalStateException.class, () -> flipped.run(plan));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void changedOriginalPagesOrPhysicalObjectNeverResumeAsNewInput(int version) throws Exception {
        var h = new Harness(version);
        h.add(DAY);
        h.add(DAY.plusDays(1));
        h.failAfterFirstPage = true;
        var first = h.service(version).run(h.plan(DAY, DAY.plusDays(1)));
        assertEquals(SyncRunState.PARTIAL, first.state());
        h.failAfterFirstPage = false;
        int sent = h.sends;
        h.revision++;
        assertThrows(Exception.class, () -> h.service(3 - version).resume(first.runId()));
        assertEquals(sent, h.sends);
        h.revision--;
        h.generation++;
        assertThrows(
                IllegalStateException.class,
                () -> h.service(3 - version).restorePlan(first.runId()));
        assertEquals(sent, h.sends);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void checkpointsRequireContiguousSourceAndFreshScopeValidation(int version) throws Exception {
        var h = new Harness(version);
        h.add(DAY);
        h.add(DAY.plusDays(1));
        h.add(DAY.plusDays(2));
        var service = h.service(version);
        assertEquals(SyncRunState.VERIFIED, service.run(h.frozen(DAY, DAY, version)).state());
        assertEquals(
                SyncRunState.VERIFIED,
                service.run(h.frozen(DAY.plusDays(2), DAY.plusDays(2), version)).state());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.plan(
                                DAY,
                                DAY.plusDays(2),
                                DAY.plusDays(3),
                                Mode.INCREMENTAL,
                                List.of()));
        assertEquals(
                SyncRunState.VERIFIED,
                service.run(h.frozen(DAY.plusDays(1), DAY.plusDays(1), version)).state());
        int sent = h.sends;
        var plan = service.plan(DAY, DAY.plusDays(2), DAY.plusDays(3), Mode.INCREMENTAL, List.of());
        assertEquals(DAY.plusDays(2), plan.verifiedThrough());
        assertEquals(sent, h.sends);
        h.rows.put(new L2DailyFeaturesKey(DAY, "000002.SZ"), row(DAY, "000002.SZ"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.plan(
                                DAY,
                                DAY.plusDays(2),
                                DAY.plusDays(3),
                                Mode.INCREMENTAL,
                                List.of()));
        assertEquals(sent, h.sends);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void explicitReconcileCertifiesARevisionAndFreshPrefixesReplaceObsoleteProofs(int version)
            throws Exception {
        var h = new Harness(version);
        h.add(DAY);
        h.add(DAY.plusDays(1));
        h.add(DAY.plusDays(2));
        var service = h.service(version);
        var old = service.run(h.frozen(DAY, DAY.plusDays(2), version));
        assertEquals(SyncRunState.VERIFIED, old.state());
        String frozen = SyncRunLedger.openReadOnly(h.path).getRun(old.runId()).frozenJson();
        h.revision++;
        assertThrows(
                Exception.class,
                () ->
                        service.plan(
                                DAY,
                                DAY.plusDays(2),
                                DAY.plusDays(3),
                                Mode.INCREMENTAL,
                                List.of()));
        var prefix = service.plan(DAY, DAY.plusDays(1), DAY.plusDays(3), Mode.RECONCILE, List.of());
        assertNull(prefix.verifiedThrough());
        assertEquals(SyncRunState.VERIFIED, service.run(prefix).state());
        var tail =
                service.plan(
                        DAY.plusDays(2),
                        DAY.plusDays(2),
                        DAY.plusDays(3),
                        Mode.RECONCILE,
                        List.of());
        assertEquals(SyncRunState.VERIFIED, service.run(tail).state());
        var current =
                service.plan(DAY, DAY.plusDays(2), DAY.plusDays(3), Mode.INCREMENTAL, List.of());
        assertEquals(DAY.plusDays(2), current.verifiedThrough());
        assertEquals(frozen, SyncRunLedger.openReadOnly(h.path).getRun(old.runId()).frozenJson());
        var reports = Files.list(h.path.getParent().resolve("sync-evidence"));
        try (reports) {
            assertTrue(
                    reports.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .startsWith("l2-checkpoint-"))
                            .anyMatch(
                                    path -> {
                                        try {
                                            return JobDefinitionJson.mapper()
                                                    .readTree(Files.readString(path))
                                                    .path("rejectedOriginalRuns")
                                                    .toString()
                                                    .contains(old.runId());
                                        } catch (IOException failure) {
                                            throw new IllegalStateException(failure);
                                        }
                                    }));
        }
    }

    @Test
    void v2CompletionRejectsExtraKeysAndUnstableFinalReadback() throws Exception {
        var orphan = new Harness(2);
        orphan.add(DAY);
        orphan.rows.put(new L2DailyFeaturesKey(DAY, "000002.SZ"), row(DAY, "000002.SZ"));
        var result = orphan.service(2).run(orphan.frozen(DAY, DAY, 2));
        assertEquals(SyncRunState.PARTIAL, result.state());
        var drift = new Harness(2);
        drift.add(DAY);
        drift.bumpOnRead = 2;
        var changed = drift.service(2).run(drift.frozen(DAY, DAY, 2));
        assertEquals(SyncRunState.PARTIAL, changed.state());
    }

    @Test
    void selectedSymbolsDoNotRejectOtherConsumersRowsAndRevalidationCannotSend() throws Exception {
        var h = new Harness(2);
        h.add(DAY);
        h.rows.put(new L2DailyFeaturesKey(DAY, "000002.SZ"), row(DAY, "000002.SZ"));
        var inspection = h.inspect(DAY, DAY, List.of("000001.SZ"));
        var parameters =
                Map.<String, Object>of(
                        "source_root_id",
                        "c".repeat(64),
                        "symbols",
                        List.of("000001.SZ"),
                        "source_inspection",
                        JobDefinitionJson.mapper().writeValueAsString(inspection));
        var request =
                L2DailyFeaturesJobService.definition(2)
                        .freeze(Mode.BACKFILL, parameters, DAY, DAY, DAY);
        var target = h.new Target(2);
        var plan =
                new L2DailyFeaturesJobService.Plan(
                        request, target.targetId(), DAY, null, 0, inspection);
        assertEquals(SyncRunState.VERIFIED, h.service(2).run(plan).state());
        var readonly = new L2DailyFeaturesBoundSession(target, target.targetId(), false);
        int sent = h.sends;
        assertThrows(
                IllegalStateException.class, () -> readonly.send(List.of(row(DAY, "000001.SZ"))));
        assertEquals(sent, h.sends);
    }

    @Test
    void unknownWalCannotReleaseTheOriginalLeaseOrResume() throws Exception {
        var h = new Harness(2);
        h.add(DAY);
        h.wal = false;
        var result = h.service(2).run(h.frozen(DAY, DAY, 2));
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        var scope = new IntervalLockStore.Scope("l2_daily_features", DAY, DAY);
        var locks = new DatasetIntervalLock(h.path);
        var owned = locks.findOwned(result.runId(), scope);
        assertNotNull(owned);
        assertTrue(owned.inDoubt());
        int made = h.versions.size();
        assertThrows(IllegalStateException.class, () -> h.service(1).restorePlan(result.runId()));
        assertEquals(made, h.versions.size());
        assertEquals(owned, locks.findOwned(result.runId(), scope));
    }

    @Test
    void targetRaceAndCrossVersionCheckpointAreRejectedBeforeWriting() throws Exception {
        var race = new Harness(2);
        race.add(DAY);
        race.flipInFactory = true;
        assertThrows(IllegalStateException.class, () -> race.plan(DAY, DAY));
        assertFalse(Files.exists(race.path));
        assertEquals(0, race.sends);
        var h = new Harness(1);
        h.add(DAY);
        h.sameIdentity = true;
        assertEquals(SyncRunState.VERIFIED, h.service(1).run(h.frozen(DAY, DAY, 1)).state());
        h.physicalLayout = 2;
        h.versions.clear();
        assertThrows(
                IllegalStateException.class,
                () -> h.service(2).plan(DAY, DAY, DAY, Mode.INCREMENTAL, List.of()));
        assertEquals(List.of(2), h.versions);
    }

    @Test
    void resumeCliUsesOnlyTheOriginalRequestAndRejectsChangedArguments() throws Exception {
        var h = new Harness(2);
        h.add(DAY);
        var plan = h.frozen(DAY, DAY, 2);
        var service = mock(L2DailyFeaturesJobService.class);
        when(service.restorePlan("prior")).thenReturn(plan);
        when(service.resume(plan, "prior"))
                .thenReturn(new SyncJobRunner.Result("resumed", SyncRunState.VERIFIED, 1, 1, null));
        var cli = new L2Commands(null, service, null, null, null);
        cli.execute("run-l2-daily-features-job", Map.of("--resume-from", "prior"));
        verify(service).resume(plan, "prior");
        verify(service, never()).plan(any(), any(), any(), any(), anyList());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        cli.execute(
                                "run-l2-daily-features-job",
                                Map.of(
                                        "--resume-from",
                                        "prior",
                                        "--to",
                                        DAY.plusDays(1).toString())));
        assertThrows(
                IllegalArgumentException.class,
                () -> cli.execute("plan-l2-daily-features-job", Map.of("--resume-from", "prior")));
    }

    private static L2DailyFeatures row(LocalDate day, String symbol) {
        var values = new EnumMap<L2DailyFeatureField, Object>(L2DailyFeatureField.class);
        for (var field : L2DailyFeatureField.values())
            if (!field.identity()) values.put(field, null);
        values.put(L2DailyFeatureField.MEAN_REL_AGGRO, 1.25);
        return new L2DailyFeatures(day, symbol, values);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // 同一内存物理表模拟原版本、代际及事务变化；来源与会话的外部访问由有限端口代替。
    private final class Harness {
        final Path path = root.resolve(UUID.randomUUID() + ".sqlite3");
        final ExchangeCalendarReadPort calendars = mock(ExchangeCalendarReadPort.class);
        final L2DailyFeaturesParquetSource source = mock(L2DailyFeaturesParquetSource.class);
        final TreeMap<LocalDate, List<L2DailyFeatures>> raw = new TreeMap<>();
        final Map<L2DailyFeaturesKey, L2DailyFeatures> rows = new HashMap<>();
        final List<Integer> versions = new ArrayList<>();
        int physicalLayout,
                generation = 1,
                revision = 1,
                sequence = 0,
                sends = 0,
                reads = 0,
                bumpOnRead = -1;
        boolean failAfterFirstPage, flipInFactory, sameIdentity, wal = true;

        Harness(int version) throws Exception {
            physicalLayout = version;
            when(source.sourceRootIdentity()).thenReturn("c".repeat(64));
            when(calendars.findPage(any()))
                    .thenAnswer(
                            call -> {
                                DatasetReadQuery query = call.getArgument(0);
                                LocalDate from = (LocalDate) query.fromInclusive(),
                                        to = (LocalDate) query.toExclusive();
                                var days =
                                        raw.keySet().stream()
                                                .filter(d -> !d.isBefore(from) && d.isBefore(to))
                                                .map(
                                                        d ->
                                                                new ExchangeCalendar(
                                                                        "SSE",
                                                                        d,
                                                                        true,
                                                                        d.minusDays(1)))
                                                .toList();
                                return new DatasetReadPage<>(
                                        "exchange_calendar", 1, null, Instant.EPOCH, days, null);
                            });
            when(source.inspect(any(), any(), anyList(), anyInt(), anyInt()))
                    .thenAnswer(
                            call ->
                                    inspect(
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2)));
            when(source.stream(any(), anyList(), anyInt(), anyInt(), any(), any(), any()))
                    .thenAnswer(
                            call -> {
                                L2DailyFeaturesParquetSource.Inspection expected =
                                        call.getArgument(0);
                                List<String> symbols = call.getArgument(1);
                                if (!expected.equals(
                                        inspect(expected.from(), expected.to(), symbols)))
                                    throw new IOException("来源已变化");
                                SyncJobRunner.PageConsumer<L2DailyFeatures> consumer =
                                        call.getArgument(4);
                                BooleanSupplier cancelled = call.getArgument(5);
                                int pages = 0, count = 0;
                                for (var day : expected.dates()) {
                                    if (cancelled.getAsBoolean()) throw new CancellationException();
                                    var values =
                                            raw.get(day).stream()
                                                    .filter(
                                                            r ->
                                                                    symbols.isEmpty()
                                                                            || symbols.contains(
                                                                                    r.symbol()))
                                                    .toList();
                                    if (values.isEmpty()) continue;
                                    consumer.accept(
                                            new SyncJobRunner.Page<>(
                                                    values,
                                                    hash(expected.sourceFingerprint() + day),
                                                    "{\"fixture\":true}",
                                                    "day-" + day));
                                    pages++;
                                    count += values.size();
                                    if (failAfterFirstPage) throw new IOException("第一页后中断");
                                }
                                return new SyncJobRunner.SourceCompletion(
                                        pages, count, true, "{\"complete\":true}");
                            });
        }

        void add(LocalDate day) {
            raw.put(day, List.of(row(day, "000001.SZ")));
        }

        L2DailyFeaturesParquetSource.Inspection inspect(
                LocalDate from, LocalDate to, List<String> symbols) throws Exception {
            var dates =
                    raw.keySet().stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
            int count =
                    dates.stream()
                            .mapToInt(
                                    d ->
                                            (int)
                                                    raw.get(d).stream()
                                                            .filter(
                                                                    r ->
                                                                            symbols.isEmpty()
                                                                                    || symbols
                                                                                            .contains(
                                                                                                    r
                                                                                                            .symbol()))
                                                            .count())
                            .sum();
            return new L2DailyFeaturesParquetSource.Inspection(
                    from,
                    to,
                    dates,
                    count,
                    count,
                    dates.size(),
                    dates.size(),
                    12,
                    hash(from + ":" + to + ":" + revision),
                    "b".repeat(64),
                    "l2-daily-features-parquet-v1",
                    "c".repeat(64),
                    true);
        }

        L2DailyFeaturesJobService service(int version) {
            return new L2DailyFeaturesJobService(calendars, new Target(version), source, path);
        }

        L2DailyFeaturesJobService.Plan plan(LocalDate from, LocalDate to) throws Exception {
            return service(physicalLayout).plan(from, to, to, Mode.BACKFILL, List.of());
        }

        L2DailyFeaturesJobService.Plan frozen(LocalDate from, LocalDate to, int version)
                throws Exception {
            var definition = L2DailyFeaturesJobService.definition(version);
            var inspection = inspect(from, to, List.of());
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("source_root_id", "c".repeat(64));
            if (version == 2)
                parameters.put(
                        "source_inspection",
                        JobDefinitionJson.mapper().writeValueAsString(inspection));
            return new L2DailyFeaturesJobService.Plan(
                    definition.freeze(Mode.BACKFILL, parameters, from, to, to),
                    new Target(version).targetId(),
                    from,
                    null,
                    0,
                    inspection);
        }

        final class Target implements L2DailyFeaturesTarget.Versioned {
            final int version;

            Target(int version) {
                this.version = version;
            }

            public int datasetVersion() {
                return version;
            }

            public String tableName() {
                return "java_d086_l2_daily_features_memory";
            }

            public String targetId() {
                return "questdb-"
                        + String.valueOf(sameIdentity ? 1 : version).repeat(62)
                        + String.format("%02d", generation);
            }

            public L2DailyFeaturesTarget forVersion(int requested) {
                return new Target(requested);
            }

            public void createIsolatedTarget(String table) {
                throw new AssertionError("不应建表");
            }

            public L2DailyFeaturesWriteSession newWriter() {
                versions.add(version);
                if (flipInFactory) {
                    generation++;
                    flipInFactory = false;
                }
                return new L2DailyFeaturesWriteSession.Versioned() {
                    public int datasetVersion() {
                        return version;
                    }

                    public String tableName() {
                        return Target.this.tableName();
                    }

                    public VerifiedBatchExecutor.Codec<L2DailyFeatures, L2DailyFeaturesKey>
                            codec() {
                        return L2DailyFeaturesWriteSession.codec(
                                CnTradeDateLayout.fromVersion(version));
                    }

                    public void preflight() {
                        if (physicalLayout != version) throw new IllegalStateException("布局不同");
                    }

                    public void send(List<L2DailyFeatures> values) {
                        sends++;
                        sequence++;
                        for (var r : values) rows.put(r.key(), r);
                    }

                    public List<L2DailyFeatures> readback(List<L2DailyFeaturesKey> keys) {
                        reads++;
                        if (reads == bumpOnRead) sequence++;
                        return keys.stream().filter(rows::containsKey).map(rows::get).toList();
                    }

                    public int countRows(LocalDate day) {
                        return countRows(day, List.of());
                    }

                    public int countRows(LocalDate day, List<String> symbols) {
                        return (int)
                                rows.values().stream()
                                        .filter(
                                                r ->
                                                        r.tradeDate().equals(day)
                                                                && (symbols.isEmpty()
                                                                        || symbols.contains(
                                                                                r.symbol())))
                                        .count();
                    }

                    public String stableVersion() {
                        if (!wal) throw new IllegalStateException("WAL 未知");
                        return "wal:" + sequence;
                    }

                    public List<LocalDate> readExistingDates() {
                        return rows.values().stream()
                                .map(L2DailyFeatures::tradeDate)
                                .distinct()
                                .sorted()
                                .toList();
                    }

                    public boolean walSettled() {
                        return wal;
                    }

                    public boolean uncertainSenderStopped() {
                        return true;
                    }
                };
            }
        }
    }
}
