package com.zoutrankil.data.config;

import com.zoutrankil.data.derived.storage.RetailSentimentDailyV1MaterializationPort;

import com.zoutrankil.data.derived.storage.RetailSentimentDailyCacheReadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.RetailSentimentDailyCacheMapper;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A real readonly first-phase page captures its actual cursor before any replay/increment publication. */
class RetailSentimentDailyCacheLiveCursorCaptureTest {
    @Test void captureActualFirstPublisherCursorForLaterPhysicalVersionRejection() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D100_CAPTURE_CURSOR")));
        var firstPath = RetailSentimentDailyCacheLiveAcceptanceTest.FIRST;
        assertTrue(Files.isRegularFile(firstPath), "Original Python first-two-day evidence is required");
        byte[] bytes = Files.readAllBytes(firstPath);
        assertTrue(bytes.length > 0 && bytes.length < 2 * 1024 * 1024);
        var json = JobDefinitionJson.mapper(); var audit = json.readTree(bytes);
        assertEquals("VERIFIED_FIRST_STORED_GENERATIONS", audit.required("status").asText());
        var from = LocalDate.parse(audit.required("range_from_inclusive").asText());
        var to = LocalDate.parse(audit.required("range_to_exclusive").asText());
        assertEquals(LocalDate.of(2026, 9, 17), from); assertEquals(LocalDate.of(2026, 9, 22), to);
        var range = audit.required("range_rows"); assertEquals(2, range.size());
        assertEquals(LocalDate.of(2026, 9, 17), RetailSentimentDailyCacheLiveAcceptanceTest.record(range.get(0)).tradeDate());
        assertEquals(LocalDate.of(2026, 9, 18), RetailSentimentDailyCacheLiveAcceptanceTest.record(range.get(1)).tradeDate());
        try (var dataSource = RetailSentimentDailyCacheLiveAcceptanceTest.pool("d100-first-cursor-capture")) {
            var jdbc = RetailSentimentDailyCacheLiveAcceptanceTest.jdbc(dataSource);
            var properties = RetailSentimentDailyCacheLiveAcceptanceTest.properties();
            var attestation = new RetailSentimentDailyV1MaterializationPort(jdbc, properties, true);
            attestation.verifyPrivateInstance();
            var source = new RetailSentimentDailyV1MaterializationPort(jdbc, properties, false);
            var sourceBefore = source.snapshot(); assertEquals("9:36", sourceBefore.sourceVersion());
            assertEquals(23773L, source.sourceRawRows(from, to.minusDays(1)));
            var before = RetailSentimentDailyCacheLiveAcceptanceTest.snapshot(jdbc); assertEquals(2L, before.rowCount());
            RetailSentimentDailyCacheLiveAcceptanceTest.matchesAudit(before, audit.required("tables_before").required("retail_sentiment_daily_cache"));
            RetailSentimentDailyCacheLiveAcceptanceTest.matchesAudit(before, audit.required("tables_after").required("retail_sentiment_daily_cache"));
            var reader = new QuestDbBoundedReader(jdbc); var repository = new RetailSentimentDailyCacheReadRepository(reader);
            var query = new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date", from, to, 1, null);
            var page = repository.findRange(from, to, 1, null);
            assertEquals(1, page.rows().size()); assertTrue(page.hasMore()); assertNotNull(page.nextCursor());
            assertEquals(before.token(), page.sourceVersion()); assertEquals(before.token(), page.nextCursor().sourceVersion());
            var expected = RetailSentimentDailyCacheLiveAcceptanceTest.record(range.get(0));
            var statistics = new RetailSentimentDailyCacheLiveAcceptanceTest.Comparisons();
            RetailSentimentDailyCacheLiveAcceptanceTest.compare(expected, page.rows().getFirst(), statistics);
            RetailSentimentDailyCacheLiveAcceptanceTest.storageBits(jdbc, page.rows().getFirst(), statistics);
            assertEquals(List.of(expected.tradeDate(), expected.sourceVersion()), page.nextCursor().keyValues());
            var fingerprintParts = List.<Object>of(repository.definition().datasetId(), repository.definition().schemaVersion(),
                    repository.definition().objectName(), repository.definition().columns().toString(), repository.definition().businessKey(),
                    query.columns(), List.of("trade_date", "java.time.LocalDate:" + from, "java.time.LocalDate:" + to));
            String fingerprint = RetailSentimentDailyCacheLiveAcceptanceTest.sha(
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(fingerprintParts));
            assertEquals(reader.prepare(repository.definition(), query, before.token()).fingerprint(), fingerprint);
            assertEquals(fingerprint, page.nextCursor().queryFingerprint());
            var after = RetailSentimentDailyCacheLiveAcceptanceTest.snapshot(jdbc); assertEquals(before, after);
            var sourceAfter = source.snapshot(); assertEquals(sourceBefore, sourceAfter);
            attestation.verifyPrivateInstance();
            var mapper = new RetailSentimentDailyCacheMapper();
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("task_id", "D100"); evidence.put("checked_at", Instant.now());
            evidence.put("status", "VERIFIED_ACTUAL_FIRST_CURSOR"); evidence.put("first_artifact", firstPath.toString());
            evidence.put("first_artifact_sha256", RetailSentimentDailyCacheLiveAcceptanceTest.sha(bytes));
            evidence.put("input_target", "attested-private-127.0.0.1:18822/19010");
            evidence.put("private_data_root", Path.of("var/d098-isolated-questdb").toAbsolutePath().normalize().toString());
            evidence.put("private_process_attested_before_and_after", true);
            evidence.put("private_target_attestation", audit.required("private_target_attestation"));
            evidence.put("fields", repository.definition().storageColumns());
            evidence.put("range_from_inclusive", from); evidence.put("range_to_exclusive", to); evidence.put("query", query);
            evidence.put("nextCursor", page.nextCursor());
            evidence.put("query_fingerprint_parts", fingerprintParts); evidence.put("query_fingerprint", fingerprint);
            evidence.put("actual_first_rows", page.rows().stream().map(row -> mapper.values(row).asMap()).toList());
            evidence.put("cache_snapshot_before", before); evidence.put("cache_snapshot_after", after);
            evidence.put("source_snapshot_before", sourceBefore); evidence.put("source_snapshot_after", sourceAfter);
            evidence.put("source_version", before.token()); evidence.put("fourteen_field_comparisons", 14);
            evidence.put("jdbc_storage_double_bit_comparisons", statistics.storageBits);
            evidence.put("reference_numeric_transport", "original Python publisher PGWire binary64 evidence; strict raw-bit comparison");
            evidence.put("reference_double_rounding_evidence", statistics.rounded);
            evidence.put("publisher_invocations", 0); evidence.put("database_writes", 0); evidence.put("formal_written_rows", 0);
            Files.createDirectories(RetailSentimentDailyCacheLiveAcceptanceTest.CAPTURE.getParent());
            Files.writeString(RetailSentimentDailyCacheLiveAcceptanceTest.CAPTURE,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        }
    }
}
