package com.zoutrankil.data.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;
import com.zoutrankil.data.domain.IntervalLockStore;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

class CompleteFrozenReconciliationTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = "market-00000000-0000-0000-0000-000000000001",
            ATTEMPT = "attempt-1";
    private static final DatasetDefinition DEFINITION =
            new DatasetDefinition(
                    "market_test",
                    1,
                    "test",
                    "test",
                    "market_test",
                    ObjectKind.TABLE,
                    List.of(
                            new Column(
                                    "ts",
                                    "ts",
                                    "ts",
                                    StorageType.TIMESTAMP,
                                    false,
                                    "真实 UTC 时刻",
                                    new TemporalContract(
                                            TemporalKind.INSTANT,
                                            "ISO-8601",
                                            "UTC",
                                            "MICROS",
                                            "真实事件时刻")),
                            new Column(
                                    "symbol",
                                    "symbol",
                                    "symbol",
                                    StorageType.SYMBOL,
                                    false,
                                    "证券业务键",
                                    null),
                            new Column(
                                    "price",
                                    "price",
                                    "price",
                                    StorageType.DOUBLE,
                                    false,
                                    "来源价格",
                                    null)),
                    List.of("ts", "symbol"),
                    List.of("ts", "symbol"),
                    "ts",
                    Partition.MONTH,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "测试只读恢复");

    @Test
    void completeMultiPageReadbackPreservesVerifiedSliceAndNeverSends() throws Exception {
        try (var f = new Fixture()) {
            var first = f.ledger.get("slice-0");
            var events = f.ledger.events("slice-0", -1, 100);
            var result = f.reconcile();
            assertEquals("VERIFIED", result.get("state"));
            assertEquals(10001, result.get("rows"));
            assertEquals(0, result.get("repairedMissingRows"));
            assertEquals(first, f.ledger.get("slice-0"));
            assertEquals(events, f.ledger.events("slice-0", -1, 100));
            assertEquals(SyncRunState.VERIFIED, f.ledger.get(RUN).state());
            assertNull(f.locks.findOwned(RUN, f.scope));
            assertEquals(0, f.writes.get());
            var proof = JSON.readTree(f.ledger.get("slice-1").payloadJson()).path("verification");
            assertEquals(1, proof.path("expectedRows").asInt());
            String reference = proof.path("readbackEvidence").asText();
            String[] parts = reference.split("#");
            byte[] readback = Files.readAllBytes(Path.of(parts[0]));
            assertEquals(parts[1], VerifiedMarketDataStore.sha(readback));
            assertEquals(1, JSON.readTree(readback).path("rows").size());
        }
    }

    @Test
    void submittedPrefixKeepsCompleteReadOnlyRecoveryClosedForUnsubmittedTail() throws Exception {
        try (var f = new Fixture(root.resolve("prefix"), false, 20850)) {
            var before = f.ledger.entries(RUN, null, 100);
            var firstEvents = f.ledger.events("slice-0", -1, 100);
            var prefix = f.submittedPrefix();
            assertEquals(
                    List.of(0, 10000),
                    prefix.stream().map(CompleteFrozenSlices.Slice::offset).toList());
            assertEquals(
                    List.of(10000, 10000),
                    prefix.stream().map(CompleteFrozenSlices.Slice::rows).toList());
            assertThrows(IllegalStateException.class, f::reconcile);
            assertEquals(before, f.ledger.entries(RUN, null, 100));
            assertEquals(firstEvents, f.ledger.events("slice-0", -1, 100));
            assertEquals(0, f.writes.get());
        }
    }

    @Test
    void submittedPrefixRejectsCompleteRunCancellationAndChangedReceipt() throws Exception {
        try (var f = new Fixture(root.resolve("no-tail"))) {
            assertThrows(IllegalStateException.class, f::submittedPrefix);
        }
        try (var f = new Fixture(root.resolve("cancelled-prefix"), false, 20850)) {
            f.ledger.requestCancellation(RUN);
            assertThrows(IllegalStateException.class, f::submittedPrefix);
        }
        try (var f = new Fixture(root.resolve("bad-prefix-receipt"), false, 20850)) {
            var event = f.ledger.events("slice-1", 4, 1).getFirst();
            var document = (ObjectNode) JSON.readTree(event.payloadJson());
            ((ObjectNode) document.path("writeResult").path("receipts").get(0))
                    .put("digest", "b".repeat(64));
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + f.ledgerFile);
                    var update =
                            c.prepareStatement(
                                    "UPDATE sync_events SET payload_json=? WHERE entry_id='slice-1' AND revision=5")) {
                update.setString(1, JSON.writeValueAsString(document));
                update.executeUpdate();
            }
            assertThrows(IllegalStateException.class, f::submittedPrefix);
            assertEquals(0, f.writes.get());
        }
    }

    @Test
    void tailRecoverySendsOnlyUnsubmittedRowsAndPreservesOriginalVerifiedEvents() throws Exception {
        try (var f = new Fixture(root.resolve("tail-success"), false, 20850)) {
            f.tailOnly = true;
            var first = f.ledger.get("slice-0");
            var events = f.ledger.events("slice-0", -1, 100);
            byte[] source = Files.readAllBytes(f.prepared);
            var result = f.reconcileTail(true, false);
            assertEquals("VERIFIED", result.get("state"));
            assertEquals(850, result.get("appendedUnsubmittedRows"));
            assertEquals(0, result.get("replayedSubmittedRows"));
            assertEquals(f.rows.subList(20000, 20850), f.receivedTail);
            assertEquals(1, f.writes.get());
            assertEquals(first, f.ledger.get("slice-0"));
            assertEquals(events, f.ledger.events("slice-0", -1, 100));
            assertEquals(VerifiedMarketDataStore.sha(source), f.preparedSha);
            assertEquals(
                    f.preparedSha, VerifiedMarketDataStore.sha(Files.readAllBytes(f.prepared)));
            assertTrue(
                    f.ledger.entries(RUN, null, 100).stream()
                            .allMatch(e -> e.state() == SyncRunState.VERIFIED));
            assertEquals(
                    3,
                    f.ledger.entries(RUN, null, 100).stream()
                            .filter(e -> e.kind() == SyncRunLedger.Kind.SLICE)
                            .count());
            assertNull(f.locks.findOwned(RUN, f.scope));
        }
    }

    @Test
    void tailRecoveryKeepsOriginalPageGeometryAcrossMultipleNewPages() throws Exception {
        try (var f = new Fixture(root.resolve("tail-multiple"), false, 30850)) {
            f.tailOnly = true;
            var result = f.reconcileTail(true, false);
            assertEquals(10850, result.get("appendedUnsubmittedRows"));
            assertEquals(2, f.writes.get());
            assertEquals(f.rows.subList(20000, 30850), f.receivedTail);
            assertEquals(
                    4,
                    f.ledger.entries(RUN, null, 100).stream()
                            .filter(e -> e.kind() == SyncRunLedger.Kind.SLICE)
                            .count());
            assertNull(f.locks.findOwned(RUN, f.scope));
        }
    }

    @Test
    void tailRecoveryCanReconcileUncertainAcknowledgmentWithoutReplaying() throws Exception {
        try (var f = new Fixture(root.resolve("tail-unknown"), false, 20850)) {
            f.tailOnly = true;
            assertEquals("VERIFIED", f.reconcileTail(true, true).get("state"));
            assertEquals(1, f.writes.get());
            assertEquals(850, f.receivedTail.size());
        }
    }

    @Test
    void tailRecoveryRejectsAuthorizationPrefixMismatchExistingTailAndCancellation()
            throws Exception {
        for (String mode :
                List.of(
                        "authorization",
                        "missing",
                        "changed",
                        "duplicate",
                        "existing-tail",
                        "cancelled",
                        "unsettled",
                        "moving-wal",
                        "moving-target")) {
            try (var f = new Fixture(root.resolve("tail-reject-" + mode), false, 20850)) {
                f.tailOnly = !mode.equals("existing-tail");
                f.mode = mode;
                if (mode.equals("cancelled")) f.ledger.requestCancellation(RUN);
                var before = f.ledger.entries(RUN, null, 100);
                assertThrows(
                        Exception.class,
                        () -> f.reconcileTail(!mode.equals("authorization"), false));
                assertEquals(before, f.ledger.entries(RUN, null, 100));
                assertEquals(0, f.writes.get());
                assertTrue(f.locks.findOwned(RUN, f.scope).inDoubt());
            }
        }
    }

    @Test
    void tailRegistrationRejectsUnverifiedOrStaleSnapshotAndCancellation() throws Exception {
        try (var f = new Fixture(root.resolve("tail-register"), false, 20850)) {
            var before = f.ledger.entries(RUN, null, 100);
            String authorization =
                    JSON.writeValueAsString(
                            Map.of(
                                    "preparedSha256",
                                    f.preparedSha,
                                    "sourceSha256",
                                    f.sourceSha,
                                    "sourceRows",
                                    20850,
                                    "submittedRows",
                                    20000,
                                    "cursor",
                                    "20000",
                                    "tailRows",
                                    850,
                                    "readbackEvidence",
                                    "proof#digest",
                                    "reconciliationIntent",
                                    "intent"));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.ledger.createFrozenTailSlice(
                                    "tail",
                                    RUN,
                                    ATTEMPT,
                                    f.ledger.getRun(RUN).frozenJson(),
                                    before,
                                    authorization));
            var proof = (ObjectNode) JSON.readTree(f.ledger.get("slice-0").payloadJson());
            ((ObjectNode) proof.path("verification")).put("writerStopped", true);
            f.ledger.transition(
                    "slice-1", 5, SyncRunState.VERIFIED, JSON.writeValueAsString(proof));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.ledger.createFrozenTailSlice(
                                    "tail",
                                    RUN,
                                    ATTEMPT,
                                    f.ledger.getRun(RUN).frozenJson(),
                                    before,
                                    authorization));
            var verified = f.ledger.entries(RUN, null, 100);
            f.ledger.requestCancellation(RUN);
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.ledger.createFrozenTailSlice(
                                    "tail",
                                    RUN,
                                    ATTEMPT,
                                    f.ledger.getRun(RUN).frozenJson(),
                                    verified,
                                    authorization));
            assertEquals(verified, f.ledger.entries(RUN, null, 100));
        }
    }

    @Test
    void verifiedRunWithRetainedLockCanFinishWithoutChangingAnyCheckpoint() throws Exception {
        try (var f = new Fixture()) {
            f.reconcile();
            f.locks.retainInDoubt(f.locks.acquire(RUN, f.scope));
            var before = f.ledger.entries(RUN, null, 100);
            assertEquals("VERIFIED", f.reconcile().get("state"));
            assertEquals(before, f.ledger.entries(RUN, null, 100));
            assertNull(f.locks.findOwned(RUN, f.scope));
            assertEquals(0, f.writes.get());
        }
    }

    @Test
    void missingChangedAndDuplicateValuesCannotAdvanceOrRepair() throws Exception {
        for (String mode : List.of("missing", "changed", "duplicate"))
            try (var f = new Fixture(root.resolve(mode))) {
                f.mode = mode;
                f.assertRejected();
            }
    }

    @Test
    void unstableWalAndPhysicalTargetAreRejected() throws Exception {
        for (String mode : List.of("unsettled", "moving-wal", "moving-target"))
            try (var f = new Fixture(root.resolve(mode))) {
                f.mode = mode;
                f.assertRejected();
            }
    }

    @Test
    void missingPageDriftedCursorDifferentSourceAndReceiptAreRejected() throws Exception {
        for (String mode : List.of("missing-page", "cursor", "source", "digest", "event"))
            try (var f = new Fixture(root.resolve(mode))) {
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + f.ledgerFile);
                        var s = c.createStatement()) {
                    if (mode.equals("missing-page"))
                        s.executeUpdate(
                                "UPDATE sync_entries SET state='RUNNING' WHERE id='slice-1'");
                    else {
                        int revision = mode.equals("digest") ? 5 : mode.equals("event") ? 3 : 2;
                        var event = f.ledger.events("slice-1", revision - 1, 1).getFirst();
                        var document = (ObjectNode) JSON.readTree(event.payloadJson());
                        switch (mode) {
                            case "cursor" -> document.put("cursor", "0");
                            case "source" -> document.put("sourceFingerprint", "b".repeat(64));
                            case "digest" ->
                                    ((ObjectNode)
                                                    document.path("writeResult")
                                                            .path("receipts")
                                                            .get(0))
                                            .put("digest", "b".repeat(64));
                            case "event" -> document.put("returnedRows", 2);
                        }
                        try (var update =
                                c.prepareStatement(
                                        "UPDATE sync_events SET payload_json=? WHERE entry_id='slice-1' AND revision=?")) {
                            update.setString(1, JSON.writeValueAsString(document));
                            update.setInt(2, revision);
                            update.executeUpdate();
                        }
                    }
                }
                f.assertRejected();
            }
    }

    @Test
    void originalLiveWriterSourceChangeCancellationAndAbsentLockAreRejected() throws Exception {
        for (String mode : List.of("live", "source-sha", "cancelled", "no-lock"))
            try (var f = new Fixture(root.resolve(mode), mode.equals("live"))) {
                if (mode.equals("source-sha")) Files.writeString(f.raw, "modified source");
                if (mode.equals("cancelled")) f.ledger.requestCancellation(RUN);
                if (mode.equals("no-lock"))
                    f.locks.releaseAfterReconciliation(f.locks.findOwned(RUN, f.scope), true, true);
                f.assertRejected();
            }
    }

    @Test
    void changedPreparedAndStaleStoppedEvidenceAreRejected() throws Exception {
        try (var f = new Fixture(root.resolve("prepared"))) {
            Files.writeString(f.prepared, "{}");
            f.assertRejected();
        }
        try (var f = new Fixture(root.resolve("stopped"))) {
            Files.write(
                    f.stopped,
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    RUN,
                                    "writerStopped",
                                    true,
                                    "inspectedAt",
                                    Instant.now().minusSeconds(301).toString(),
                                    "evidence",
                                    "旧检查")));
            f.assertRejected();
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path folder, ledgerFile, raw, prepared, stopped;
        final SyncRunLedger ledger;
        final DatasetIntervalLock locks;
        final IntervalLockStore.Scope scope =
                new IntervalLockStore.Scope(
                        "market_test", LocalDate.of(2019, 1, 2), LocalDate.of(2019, 1, 2));
        final List<Map<String, Object>> rows = new ArrayList<>();
        final AtomicInteger writes = new AtomicInteger();
        final HttpServer server;
        final VerifiedMarketDataStore store;
        final String preparedSha, sourceSha;
        String mode = "complete";
        boolean tailOnly;
        final List<Map<String, Object>> receivedTail = new ArrayList<>();
        final Set<Object> receivedTailTimes = new HashSet<>();
        int walReads, targetReads;

        Fixture() throws Exception {
            this(root);
        }

        Fixture(Path folder) throws Exception {
            this(folder, false);
        }

        Fixture(Path folder, boolean live) throws Exception {
            this(folder, live, 10001);
        }

        Fixture(Path folder, boolean live, int rowCount) throws Exception {
            this.folder = Files.createDirectories(folder);
            ledgerFile = folder.resolve("sync-ledger.sqlite3");
            ledger = new SyncRunLedger(ledgerFile);
            raw = Files.writeString(folder.resolve("source.json"), "original frozen source");
            sourceSha = VerifiedMarketDataStore.sha(Files.readAllBytes(raw));
            for (int i = 0; i < rowCount; i++)
                rows.add(
                        Map.of(
                                "ts",
                                Instant.parse("2019-01-02T00:00:00Z").plusSeconds(i).toString(),
                                "symbol",
                                "X",
                                "price",
                                i + 0.5));
            prepared = Files.createDirectories(folder.resolve("prepared")).resolve(RUN + ".json");
            long pid = live ? ProcessHandle.current().pid() : 900000000L;
            assertTrue(live || ProcessHandle.of(pid).isEmpty());
            byte[] bytes =
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "definition",
                                    DEFINITION,
                                    "raw",
                                    raw.toAbsolutePath().toString(),
                                    "sourceSha256",
                                    sourceSha,
                                    "rows",
                                    rows,
                                    "writerPid",
                                    pid,
                                    "writerStartedAt",
                                    live
                                            ? ProcessHandle.current()
                                                    .info()
                                                    .startInstant()
                                                    .orElseThrow()
                                                    .toString()
                                            : Instant.EPOCH.toString(),
                                    "publicationPage",
                                    new VerifiedMarketDataStore.PublicationPage(10000, 8388608)));
            Files.write(prepared, bytes);
            preparedSha = VerifiedMarketDataStore.sha(bytes);
            var identity = new LinkedHashMap<String, Object>();
            identity.put("id", 1);
            identity.put("directoryName", "market_test~1");
            String target =
                    "market-" + VerifiedMarketDataStore.sha(JSON.writeValueAsBytes(identity));
            var request =
                    Map.of(
                            "definition",
                            Map.of(
                                    "version",
                                    2,
                                    "datasetId",
                                    "market_test",
                                    "datasetVersion",
                                    1,
                                    "budget",
                                    Map.of(
                                            "maxRows",
                                            100000,
                                            "maxPages",
                                            100,
                                            "maxSlices",
                                            100,
                                            "maxBatchBytes",
                                            8388608)),
                            "parameters",
                            Map.of(
                                    "prepared_sha256",
                                    preparedSha,
                                    "source_sha256",
                                    sourceSha,
                                    "publication_page_rows",
                                    10000,
                                    "publication_page_bytes",
                                    8388608),
                            "from",
                            "2019-01-02",
                            "to",
                            "2019-01-02");
            ledger.createRun(
                    new SyncRunLedger.Run(
                            RUN,
                            null,
                            "market.market_test",
                            2,
                            "2019-01-02",
                            target,
                            JSON.writeValueAsString(request)));
            ledger.transition(RUN, 0, SyncRunState.RUNNING, "{}");
            ledger.createChild(ATTEMPT, SyncRunLedger.Kind.ATTEMPT, RUN, RUN);
            ledger.transition(ATTEMPT, 0, SyncRunState.RUNNING, "{}");
            for (int i = 0; i < 2; i++) {
                String id = "slice-" + i;
                int offset = i * 10000, count = Math.min(10000, rows.size() - offset);
                ledger.createChild(id, SyncRunLedger.Kind.SLICE, RUN, ATTEMPT);
                ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
                String source =
                        JSON.writeValueAsString(
                                Map.of(
                                        "returnedRows",
                                        count,
                                        "sourceFingerprint",
                                        sourceSha,
                                        "responseEvidence",
                                        prepared.toAbsolutePath().normalize().toString(),
                                        "cursor",
                                        Integer.toString(offset)));
                ledger.transition(id, 1, SyncRunState.FETCHED, source);
                ledger.transition(id, 2, SyncRunState.VALIDATED, source);
                ledger.transition(id, 3, SyncRunState.SUBMITTED, source);
                var hash = MessageDigest.getInstance("SHA-256");
                int size = 0;
                for (var row : rows.subList(offset, offset + count)) {
                    byte[] value = VerifiedMarketDataStore.codec(DEFINITION).canonicalBytes(row);
                    size += value.length;
                    hash.update(ByteBuffer.allocate(4).putInt(value.length).array());
                    hash.update(value);
                }
                var receipt =
                        Map.of(
                                "batch",
                                1,
                                "submittedRows",
                                count,
                                "verifiedRows",
                                i == 0 ? count : 0,
                                "bytes",
                                size,
                                "digest",
                                HexFormat.of().formatHex(hash.digest()),
                                "delivery",
                                i == 0 ? "ACKNOWLEDGED" : "ACKNOWLEDGED_UNVERIFIED");
                ledger.transition(
                        id,
                        4,
                        i == 0 ? SyncRunState.ACKNOWLEDGED : SyncRunState.IN_DOUBT,
                        JSON.writeValueAsString(
                                Map.of(
                                        "writeResult",
                                        Map.of(
                                                "status",
                                                i == 0 ? "VERIFIED" : "IN_DOUBT",
                                                "submittedRows",
                                                count,
                                                "verifiedRows",
                                                i == 0 ? count : 0,
                                                "receipts",
                                                List.of(receipt)))));
                if (i == 0)
                    ledger.transition(
                            id,
                            5,
                            SyncRunState.VERIFIED,
                            JSON.writeValueAsString(
                                    Map.of(
                                            "verification",
                                            Map.of(
                                                    "passed",
                                                    true,
                                                    "expectedRows",
                                                    count,
                                                    "actualRows",
                                                    count,
                                                    "matchedRows",
                                                    count,
                                                    "missingKeys",
                                                    0,
                                                    "duplicateKeys",
                                                    0,
                                                    "mismatchedRows",
                                                    0,
                                                    "sourceFingerprint",
                                                    sourceSha,
                                                    "readbackEvidence",
                                                    "original-proof",
                                                    "writerStopped",
                                                    false))));
            }
            ledger.transition(ATTEMPT, 1, SyncRunState.IN_DOUBT, "{}");
            ledger.transition(RUN, 1, SyncRunState.IN_DOUBT, "{}");
            locks = new DatasetIntervalLock(ledgerFile);
            locks.retainInDoubt(locks.acquire(RUN, scope));
            Files.createDirectories(folder.resolve("receipts"));
            Files.write(
                    folder.resolve("receipts/" + RUN + ".json"),
                    JSON.writeValueAsBytes(
                            Map.of("preparedSha256", preparedSha, "targetId", target)));
            stopped = folder.resolve("stopped.json");
            Files.write(
                    stopped,
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "runId",
                                    RUN,
                                    "writerStopped",
                                    true,
                                    "inspectedAt",
                                    Instant.now().toString(),
                                    "evidence",
                                    "测试原进程身份")));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        if (!exchange.getRequestMethod().equals("GET")
                                || !exchange.getRequestURI().getPath().equals("/exec")) {
                            writes.incrementAndGet();
                            exchange.sendResponseHeaders(500, -1);
                            exchange.close();
                            return;
                        }
                        String sql =
                                URLDecoder.decode(
                                        exchange.getRequestURI()
                                                .getRawQuery()
                                                .split("&query=", 2)[1],
                                        StandardCharsets.UTF_8);
                        List<String> names;
                        List<List<Object>> values;
                        if (sql.contains("table_columns(")) {
                            names = List.of("column", "type", "designated", "upsertKey");
                            values =
                                    List.of(
                                            List.of("ts", "TIMESTAMP", true, true),
                                            List.of("symbol", "SYMBOL", false, true),
                                            List.of("price", "DOUBLE", false, false));
                        } else if (sql.startsWith("SELECT partitionBy")) {
                            names = List.of("partitionBy", "walEnabled", "table_type");
                            values = List.of(List.of("MONTH", true, "T"));
                        } else if (sql.startsWith("SELECT id,directoryName")) {
                            names = List.of("id", "directoryName");
                            values =
                                    List.of(
                                            List.of(
                                                    mode.equals("moving-target")
                                                                    && ++targetReads > 2
                                                            ? 2
                                                            : 1,
                                                    "market_test~1"));
                        } else if (sql.contains("wal_tables()")) {
                            int version = mode.equals("moving-wal") ? ++walReads : 1;
                            if (sql.startsWith("SELECT writerTxn")) {
                                names =
                                        List.of(
                                                "writerTxn",
                                                "sequencerTxn",
                                                "suspended",
                                                "bufferedTxnSize");
                                values = List.of(List.of(version, version, false, 0));
                            } else {
                                names =
                                        List.of(
                                                "walEnabled",
                                                "table_suspended",
                                                "writerTxn",
                                                "sequencerTxn",
                                                "suspended",
                                                "bufferedTxnSize");
                                values =
                                        List.of(
                                                List.of(
                                                        true,
                                                        false,
                                                        version,
                                                        mode.equals("unsettled")
                                                                ? version + 1
                                                                : version,
                                                        false,
                                                        0));
                            }
                        } else if (sql.startsWith("SELECT \"ts\"")) {
                            names = List.of("ts", "symbol", "price");
                            values = new ArrayList<>();
                            var matcher =
                                    Pattern.compile("\"ts\">='([^']+)'.*\"ts\"<='([^']+)'")
                                            .matcher(sql);
                            if (!matcher.find()) throw new IllegalArgumentException(sql);
                            Instant from = Instant.parse(matcher.group(1)),
                                    to = Instant.parse(matcher.group(2));
                            for (int i = 0; i < rows.size(); i++) {
                                if (tailOnly
                                        && i >= 20000
                                        && !receivedTailTimes.contains(rows.get(i).get("ts")))
                                    continue;
                                var row = rows.get(i);
                                Instant time = Instant.parse(row.get("ts").toString());
                                if (time.isBefore(from)
                                        || time.isAfter(to)
                                        || mode.equals("missing") && i == 10000) continue;
                                var value =
                                        List.<Object>of(
                                                row.get("ts"),
                                                "X",
                                                mode.equals("changed") && i == 10000
                                                        ? 99.
                                                        : row.get("price"));
                                values.add(value);
                                if (mode.equals("duplicate") && i == 10000) values.add(value);
                            }
                        } else {
                            writes.incrementAndGet();
                            exchange.sendResponseHeaders(500, -1);
                            exchange.close();
                            return;
                        }
                        byte[] body =
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "columns",
                                                names.stream().map(n -> Map.of("name", n)).toList(),
                                                "dataset",
                                                values));
                        exchange.sendResponseHeaders(200, body.length);
                        try (var out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            server.start();
            store =
                    new VerifiedMarketDataStore(
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                            "",
                            "",
                            "",
                            folder);
        }

        Map<String, Object> reconcile() throws Exception {
            return store.reconcileCompleteFrozen(RUN, preparedSha, stopped);
        }

        Map<String, Object> reconcileTail(boolean authorized, boolean uncertain) throws Exception {
            return store.reconcileFrozenTail(
                    RUN,
                    preparedSha,
                    stopped,
                    authorized,
                    (definition, batch, run, policy) -> {
                        assertEquals(RUN, run);
                        assertEquals(VerifiedMarketDataStore.PublicationPolicy.LEGACY, policy);
                        Instant tailStart = Instant.parse(rows.get(20000).get("ts").toString());
                        assertTrue(
                                batch.stream()
                                        .allMatch(
                                                row ->
                                                        !Instant.parse(row.get("ts").toString())
                                                                .isBefore(tailStart)));
                        writes.incrementAndGet();
                        receivedTail.addAll(batch);
                        batch.forEach(row -> receivedTailTimes.add(row.get("ts")));
                        if (uncertain) throw new IOException("测试发送确认丢失，数据已经可见");
                    });
        }

        List<CompleteFrozenSlices.Slice> submittedPrefix() throws Exception {
            return CompleteFrozenSlices.requireSubmittedPrefix(
                    ledger,
                    RUN,
                    prepared,
                    DEFINITION,
                    rows,
                    sourceSha,
                    new VerifiedMarketDataStore.PublicationPage(10000, 8388608),
                    ledger.entries(RUN, null, 100));
        }

        void assertRejected() throws Exception {
            var before = ledger.entries(RUN, null, 100);
            assertThrows(Exception.class, this::reconcile);
            assertEquals(before, ledger.entries(RUN, null, 100));
            assertEquals(0, writes.get());
        }

        public void close() {
            server.stop(0);
        }
    }
}
