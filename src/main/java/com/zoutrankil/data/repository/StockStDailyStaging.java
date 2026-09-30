package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.StockStDaily;
import com.zoutrankil.data.domain.StockStDailyKey;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import com.zoutrankil.data.service.StockStDailyJobService;
import io.questdb.client.QuestDB;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Builds an authoritative daily ST window while preserving and fingerprinting every row outside it. */
public final class StockStDailyStaging {
    public record Prepared(String target, String beforePhysicalTarget, LocalDate from, LocalDate to,
                           StockStDailyStorage.Snapshot before, StockStDailyStorage.Content outside) {
        public Prepared {
            Objects.requireNonNull(target); Objects.requireNonNull(beforePhysicalTarget);
            Objects.requireNonNull(from); Objects.requireNonNull(to);
            Objects.requireNonNull(before); Objects.requireNonNull(outside);
            if (from.isAfter(to)) throw new IllegalArgumentException("Invalid D012 stage window");
        }
    }
    public record Verified(String table, String physicalTarget, StockStDailyStorage.Snapshot snapshot,
                           StockStDailyStorage.Content outside, StockStDailyStorage.Content window,
                           int sourceRows, int batches, String sourceFingerprint, String receipt) {}

    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    public StockStDailyStaging(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(120);
        this.questdb = Objects.requireNonNull(questdb);
    }

    public Prepared prepare(String target, String expectedPhysicalTarget, LocalDate from, LocalDate to) throws Exception {
        StockStDailyJobService.requireIsolatedTableName(target);
        Objects.requireNonNull(expectedPhysicalTarget); Objects.requireNonNull(from); Objects.requireNonNull(to);
        if (from.isAfter(to) || from.isBefore(com.zoutrankil.data.service.StockStDailySource.HISTORY_ANCHOR)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1 > 366)
            throw new IllegalArgumentException("D012 replacement window must be explicit and bounded to 366 days");
        var storage = new StockStDailyStorage(jdbc, target);
        var before = storage.snapshot();
        if (!StockStDailyStorage.targetId(jdbc, target, before.identity()).equals(expectedPhysicalTarget))
            throw new IllegalStateException("D012 target physical identity changed before staging");
        return new Prepared(target, expectedPhysicalTarget, from, to, before, storage.outside(from, to));
    }

    public Verified write(Prepared prepared, List<StockStDaily> rows, String sourceFingerprint,
                          List<Map<String,Object>> sourceReceipts, Path evidence,
                          BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(prepared); Objects.requireNonNull(rows); Objects.requireNonNull(cancelled);
        if (rows.size() > 1_000_000 || sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bounded complete D012 authoritative output required");
        var expectedWindow = fingerprintWindow(rows, prepared.from(), prepared.to());
        if (expectedWindow.rows() != rows.size()) throw new IllegalArgumentException("D012 authoritative rows differ from bounded window");
        check(cancelled);
        var current = new StockStDailyStorage(jdbc, prepared.target()).snapshot();
        if (!current.equals(prepared.before())
                || !StockStDailyStorage.targetId(jdbc, prepared.target(), current.identity()).equals(prepared.beforePhysicalTarget()))
            throw new IllegalStateException("D012 target changed after stage preparation");

        String stage = StockStDailyJobService.ISOLATED_TABLE_PREFIX + "stage_"
                + UUID.randomUUID().toString().replace("-", "");
        DatasetDefinition.identifier(stage);
        Files.createDirectories(evidence);
        Path intent = evidence.resolve(stage + "-intent.json");
        var json = JobDefinitionJson.mapper();
        Files.write(intent, json.writeValueAsBytes(Map.of("target", prepared.target(), "stage", stage,
                "windowFrom", prepared.from(), "windowTo", prepared.to(), "before", prepared.before(),
                "outside", prepared.outside(), "sourceFingerprint", sourceFingerprint,
                "sourceRows", rows.size(), "sourceReceipts", sourceReceipts)), StandardOpenOption.CREATE_NEW);

        String start = timestampLiteral(prepared.from());
        String end = timestampLiteral(prepared.to().plusDays(1));
        String target = quote(prepared.target()), stageName = quote(stage);
        jdbc.execute("CREATE TABLE " + stageName + " AS (SELECT ts_code,is_st,timestamp FROM " + target
                + " WHERE timestamp<cast('" + start + "' AS TIMESTAMP) OR timestamp>=cast('" + end + "' AS TIMESTAMP)) "
                + "TIMESTAMP(timestamp) PARTITION BY YEAR WAL DEDUP UPSERT KEYS(ts_code,timestamp)");
        awaitWal(stage, cancelled);

        var stageStorage = new StockStDailyStorage(jdbc, stage);
        var stageIdentity = stageStorage.preflight();
        String stageTarget = StockStDailyStorage.targetId(jdbc, stage, stageIdentity);
        var copiedOutside = stageStorage.outside(prepared.from(), prepared.to());
        if (!StockStDailyStorage.sameContent(prepared.outside(), copiedOutside))
            throw new IllegalStateException("D012 stage failed to preserve exact rows outside the authoritative window");

        var port = new StockStDailyWritePort(stage, stageTarget, jdbc, questdb);
        int batches = 0;
        for (int offset = 0; offset < rows.size(); offset += 250) {
            check(cancelled);
            var batch = rows.subList(offset, Math.min(offset + 250, rows.size()));
            port.send(batch);
            awaitWal(stage, cancelled);
            var keys = batch.stream().map(StockStDaily::key).toList();
            if (!sameRows(batch, port.readback(keys)))
                throw new IllegalStateException("D012 staged batch full-key/all-field readback mismatch");
            batches++;
        }
        awaitWal(stage, cancelled);
        check(cancelled);
        var full = stageStorage.snapshot();
        var actualOutside = stageStorage.outside(prepared.from(), prepared.to());
        var actualWindow = stageStorage.window(prepared.from(), prepared.to());
        if (!StockStDailyStorage.sameContent(prepared.outside(), actualOutside)
                || !StockStDailyStorage.sameContent(expectedWindow, actualWindow)
                || full.content().rows() != prepared.outside().rows() + expectedWindow.rows())
            throw new IllegalStateException("D012 full stage differs from preserved outside rows plus authoritative window");
        Path receipt = evidence.resolve(stage + "-verified.json");
        Files.write(receipt, json.writeValueAsBytes(Map.ofEntries(
                Map.entry("target", prepared.target()), Map.entry("stage", stage),
                Map.entry("beforePhysicalTarget", prepared.beforePhysicalTarget()), Map.entry("stagePhysicalTarget", stageTarget),
                Map.entry("before", prepared.before()), Map.entry("after", full),
                Map.entry("windowFrom", prepared.from()), Map.entry("windowTo", prepared.to()),
                Map.entry("preservedOutside", actualOutside), Map.entry("authoritativeWindow", actualWindow),
                Map.entry("sourceRows", rows.size()), Map.entry("batches", batches),
                Map.entry("sourceFingerprint", sourceFingerprint), Map.entry("sourceReceipts", sourceReceipts),
                Map.entry("sourceComplete", true))),
                StandardOpenOption.CREATE_NEW);
        return new Verified(stage, stageTarget, full, actualOutside, actualWindow, rows.size(), batches,
                sourceFingerprint, receipt.toString());
    }

    public static StockStDailyStorage.Content fingerprintWindow(List<StockStDaily> rows, LocalDate from, LocalDate to) throws Exception {
        var ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(StockStDaily::timestamp).thenComparing(StockStDaily::tsCode));
        var digest = MessageDigest.getInstance("SHA-256"); var keys = new HashSet<StockStDailyKey>();
        for (var row : ordered) {
            if (row == null || row.timestamp().isBefore(from) || row.timestamp().isAfter(to) || !keys.add(row.key()))
                throw new IllegalArgumentException("D012 authoritative window has a duplicate or out-of-range key");
            digest.update(StockStDailyWritePort.CODEC.canonicalBytes(row)); digest.update((byte) '\n');
        }
        return new StockStDailyStorage.Content(ordered.size(), HexFormat.of().formatHex(digest.digest()));
    }
    private void awaitWal(String table, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            check(cancelled);
            if (System.nanoTime() > deadline) throw new IllegalStateException("D012 stage WAL did not settle; retain stage");
            Thread.sleep(50);
        }
    }
    private static String timestampLiteral(LocalDate day) {
        return day + "T00:00:00.000000Z";
    }
    private static String quote(String identifier) {
        DatasetDefinition.identifier(identifier);
        return "\"" + identifier + "\"";
    }
    private static boolean sameRows(List<StockStDaily> expected, List<StockStDaily> actual) {
        if (actual == null || expected.size() != actual.size()) return false;
        var expectedByKey = new HashMap<StockStDailyKey, byte[]>();
        var actualByKey = new HashMap<StockStDailyKey, byte[]>();
        for (var row : expected) if (expectedByKey.putIfAbsent(row.key(),
                StockStDailyWritePort.CODEC.canonicalBytes(row)) != null) return false;
        for (var row : actual) if (actualByKey.putIfAbsent(row.key(),
                StockStDailyWritePort.CODEC.canonicalBytes(row)) != null) return false;
        return expectedByKey.keySet().equals(actualByKey.keySet()) && expectedByKey.keySet().stream()
                .allMatch(key -> Arrays.equals(expectedByKey.get(key), actualByKey.get(key)));
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("D012 staging cancelled; retain owned stage");
    }
}
