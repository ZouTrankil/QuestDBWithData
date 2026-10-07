package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.RetailSentimentDailyCacheMapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only JDBC acceptance of the attested private Python publisher's historical cache generations. */
class RetailSentimentDailyCacheLiveAcceptanceTest {
    private static final String CACHE = "retail_sentiment_daily_cache";
    private static final Path COMMANDS = Path.of("artifacts/java-migration/D100/commands");
    private static final Path INPUT = COMMANDS.resolve("cache-isolated-acceptance-20261006.json");
    static final Path FIRST = COMMANDS.resolve("cache-isolated-first-resume-20261006.json");
    static final Path CAPTURE = COMMANDS.resolve("java-cache-cursor-first-20261006.json");
    static final List<String> DOUBLE_FIELDS = List.of("avg_retail_ratio", "avg_retail_entropy", "total_retail_amount_yi",
            "total_retail_net_inflow_yi", "avg_rel_aggro", "avg_wash_trade_ratio", "avg_mfi_score", "total_main_net_yi");
    private static final Set<String> FIELDS = Set.copyOf(RetailSentimentDailyCacheDataset.DEFINITION.storageColumns());
    private static final Comparator<RetailSentimentDailyCache> KEYS = Comparator
            .comparing(RetailSentimentDailyCache::tradeDate).thenComparing(RetailSentimentDailyCache::sourceVersion);

    @Test void originalPythonPublishedCacheMatchesExactKeysBoundedPagesAndConfiguredReadGroup() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D100_LIVE_READ")), "Set D100_LIVE_READ=true for private read-only acceptance");
        assertTrue(Files.isRegularFile(INPUT), "A successful isolated publisher audit is required when live acceptance is enabled");
        var json = JobDefinitionJson.mapper();
        byte[] inputBytes = Files.readAllBytes(INPUT);
        assertTrue(inputBytes.length > 0 && inputBytes.length < 2 * 1024 * 1024, "Finite complete publisher evidence required");
        var audit = json.readTree(inputBytes);
        assertEquals("VERIFIED_STORED_GENERATIONS", audit.required("status").asText(),
                "Failed or partial generation audits cannot become successful Java acceptance");
        assertTrue(audit.required("stable_physical_and_wal_versions").asBoolean());
        assertFalse(audit.path("current_cache_hit_certified").asBoolean());
        assertFalse(audit.path("production_latest_certified").asBoolean());
        assertTrue(audit.has("input_target") || audit.has("target") || audit.has("attestation"),
                "The audit must identify its private input target");
        var generationNodes = audit.required("verified_generations");
        assertTrue(generationNodes.isArray()); assertEquals(3, generationNodes.size());
        var rangeNodes = audit.required("range_rows");
        assertTrue(rangeNodes.isArray() && !rangeNodes.isEmpty() && rangeNodes.size() <= 100,
                "A nonempty complete bounded range snapshot is required");
        var from = LocalDate.parse(audit.required("range_from_inclusive").asText());
        var to = LocalDate.parse(audit.required("range_to_exclusive").asText());
        assertTrue(to.isAfter(from) && Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() <= 31);
        var expectedRange = new ArrayList<RetailSentimentDailyCache>();
        for (var node : rangeNodes) expectedRange.add(record(node));
        assertEquals(3, expectedRange.size());
        assertEquals(List.of(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 21)),
                expectedRange.stream().map(RetailSentimentDailyCache::tradeDate).toList());
        assertEquals(expectedRange.stream().sorted(KEYS).toList(), expectedRange,
                "QWP range snapshot must be ordered by the complete date/source_version key");
        assertEquals(expectedRange.size(), new HashSet<>(expectedRange.stream().map(RetailSentimentDailyCache::key).toList()).size());
        assertTrue(expectedRange.stream().allMatch(row -> !row.tradeDate().isBefore(from) && row.tradeDate().isBefore(to)));

        try (var dataSource = pool("d100-final-cache-read")) {
        var jdbc = jdbc(dataSource);
        var properties = properties();
        // This only attests the listener PID, private -d root and configuration; it submits no database mutation.
        new RetailSentimentDailyV1MaterializationPort(jdbc, properties, true).verifyPrivateInstance();
        var source = new RetailSentimentDailyV1MaterializationPort(jdbc, properties, false);
        var sourceBefore = source.snapshot(); assertEquals("9:36", sourceBefore.sourceVersion());
        assertEquals(23773L, source.sourceRawRows(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 21)));
        var before = snapshot(jdbc);
        assertEquals(3L, before.rowCount());
        matchesAudit(before, audit.required("tables_before").required(CACHE));
        matchesAudit(before, audit.required("tables_after").required(CACHE));
        var guardedJdbc = spy(jdbc);
        var reader = new QuestDbBoundedReader(guardedJdbc);
        var repository = new RetailSentimentDailyCacheReadRepository(reader);
        // A real earlier Java read returned this cursor before the Python replay and third-day publication.
        var phases = audit.required("phases");
        assertTrue(phases.isArray() && phases.size() >= 2);
        var oldState = phases.get(0).required("cache_snapshot");
        var oldSnapshot = auditSnapshot(oldState);
        assertNotEquals(before.token(), oldSnapshot.token(), "The real publisher must have advanced the physical cache version");
        var rangeQuery = new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date", from, to, 1, null);
        assertTrue(Files.isRegularFile(FIRST) && Files.isRegularFile(CAPTURE));
        byte[] firstBytes = Files.readAllBytes(FIRST), captureBytes = Files.readAllBytes(CAPTURE);
        String firstSha = sha(firstBytes), captureSha = sha(captureBytes);
        assertEquals(firstSha, audit.required("first_artifact_sha256").asText());
        assertEquals(captureSha, audit.required("java_cursor_capture_sha256").asText());
        var captured = json.readTree(captureBytes);
        assertEquals("VERIFIED_ACTUAL_FIRST_CURSOR", captured.required("status").asText());
        assertEquals(firstSha, captured.required("first_artifact_sha256").asText());
        assertEquals("attested-private-127.0.0.1:18822/19010", captured.required("input_target").asText());
        assertEquals(Path.of("var/d098-isolated-questdb").toAbsolutePath().normalize().toString(), captured.required("private_data_root").asText());
        assertTrue(captured.required("private_process_attested_before_and_after").asBoolean());
        assertEquals(json.readTree(json.writeValueAsBytes(rangeQuery)), captured.required("query"));
        var capturedSnapshot = json.treeToValue(captured.required("cache_snapshot_before"), Snapshot.class);
        assertEquals(oldSnapshot, capturedSnapshot);
        assertEquals(captured.required("cache_snapshot_before"), captured.required("cache_snapshot_after"));
        var savedCursor = captured.required("nextCursor");
        assertEquals(2, savedCursor.required("keyValues").size());
        var oldCursor = new DatasetReadCursor(savedCursor.required("queryFingerprint").asText(),
                List.of(LocalDate.parse(savedCursor.required("keyValues").get(0).asText()), savedCursor.required("keyValues").get(1).asText()),
                savedCursor.required("sourceVersion").asText());
        assertEquals(oldSnapshot.token(), oldCursor.sourceVersion());
        assertEquals(reader.prepare(repository.definition(), rangeQuery, oldSnapshot.token()).fingerprint(), oldCursor.queryFingerprint());
        assertEquals(oldCursor.queryFingerprint(), captured.required("query_fingerprint").asText());
        assertEquals(oldCursor.queryFingerprint(), sha(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsBytes(captured.required("query_fingerprint_parts"))));
        assertEquals(expectedRange.getFirst().key(), record(captured.required("actual_first_rows").get(0)).key());
        clearInvocations(guardedJdbc);
        assertThrows(IllegalArgumentException.class, () -> repository.findRange(from, to, 1, oldCursor),
                "A cursor from the earlier actual publisher phase cannot return a page from the final cache version");
        verify(guardedJdbc, never()).query(any(org.springframework.jdbc.core.PreparedStatementCreator.class), any(org.springframework.jdbc.core.RowMapper.class));
        var statistics = new Comparisons();
        var exactActual = new ArrayList<RetailSentimentDailyCache>();
        var exactKeys = new HashSet<RetailSentimentDailyCacheKey>();
        for (var generation : generationNodes) {
            assertTrue(generation.required("receipt_digest_matches").asBoolean());
            assertEquals(1, generation.required("exact_key_row_count").asInt());
            assertEquals(generation.required("receipt").required("content_digest").asText(),
                    generation.required("actual_python_record_digest").asText());
            var expected = record(generation.required("cache_record"));
            assertTrue(exactKeys.add(expected.key()), "Generation audit must use distinct complete keys");
            var keyPage = repository.findKey(expected.key());
            assertEquals(1, keyPage.rows().size()); assertNull(keyPage.nextCursor());
            assertEquals(before.token(), keyPage.sourceVersion());
            var versionPage = repository.findVersion(expected.tradeDate(), expected.sourceVersion());
            assertEquals(keyPage.rows(), versionPage.rows()); assertNull(versionPage.nextCursor());
            assertEquals(before.token(), versionPage.sourceVersion());
            var actual = keyPage.rows().getFirst();
            compare(expected, actual, statistics);
            storageBits(jdbc, actual, statistics);
            exactActual.add(actual);
        }

        var actualRange = new ArrayList<RetailSentimentDailyCache>();
        DatasetReadCursor cursor = null; int pages = 0;
        do {
            var page = repository.findRange(from, to, 1, cursor);
            assertEquals(before.token(), page.sourceVersion());
            assertEquals(1, page.rows().size(), "Each live bounded page must contain one complete key");
            actualRange.addAll(page.rows()); cursor = page.nextCursor();
            if (cursor != null) assertEquals(before.token(), cursor.sourceVersion());
            assertTrue(++pages <= expectedRange.size(), "Range pagination exceeded its frozen complete snapshot");
        } while (cursor != null);
        assertEquals(expectedRange.size(), pages); assertEquals(expectedRange.size(), actualRange.size());
        assertEquals(actualRange.stream().sorted(KEYS).toList(), actualRange);
        assertEquals(actualRange.size(), new HashSet<>(actualRange.stream().map(RetailSentimentDailyCache::key).toList()).size());
        for (int index = 0; index < expectedRange.size(); index++) {
            compare(expectedRange.get(index), actualRange.get(index), statistics);
            storageBits(jdbc, actualRange.get(index), statistics);
        }

        var registry = new DatasetRegistry(List.of(repository));
        var group = new ReadGroupConfiguration().readGroupReader(registry, reader);
        var requestPath = COMMANDS.resolve("read-group-request.json");
        Files.createDirectories(COMMANDS);
        var root = json.createObjectNode(); root.put("timeoutMillis", 30_000);
        var member = root.putArray("members").addObject(); member.put("memberId", "cache");
        member.put("datasetId", CACHE); member.put("definitionVersion", 1);
        var query = member.putObject("query"); query.set("columns", json.valueToTree(repository.definition().storageColumns()));
        query.putObject("equalities"); query.put("rangeColumn", "trade_date");
        query.put("fromInclusive", from.toString()); query.put("toExclusive", to.toString());
        query.put("pageSize", expectedRange.size()); query.putNull("cursor");
        Files.writeString(requestPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        var request = group.readRequest(requestPath);
        assertEquals(new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date",
                from, to, expectedRange.size(), null), request.members().getFirst().query());
        var grouped = group.read(request, () -> false); assertTrue(grouped.complete());
        var groupPage = grouped.require("cache").typedPage(RetailSentimentDailyCache.class);
        assertEquals(actualRange, groupPage.rows()); assertNull(groupPage.nextCursor());
        assertEquals(before.token(), groupPage.sourceVersion());
        var cancelled = group.read(request, () -> true).require("cache");
        assertEquals(ReadGroupReader.Status.CANCELLED, cancelled.status()); assertNull(cancelled.page());
        assertEquals(Set.of(DatasetDefinition.Capability.READ), repository.definition().capabilities());
        assertThrows(IllegalArgumentException.class,
                () -> repository.definition().requireCapability(DatasetDefinition.Capability.WRITE));
        assertThrows(IllegalArgumentException.class, () -> repository.definition().requireCapability(DatasetDefinition.Capability.STATIC_REPLACE));
        assertThrows(IllegalArgumentException.class, () -> repository.definition().requireCapability(DatasetDefinition.Capability.WAL_REPLACE));

        // The same configured JSON parser rejects invalid schema versions before any JDBC interaction.
        var untouchedJdbc = mock(JdbcTemplate.class);
        var validationGroup = new ReadGroupConfiguration().readGroupReader(registry, new QuestDbBoundedReader(untouchedJdbc));
        var invalid = root.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.withArray("members").get(0)).put("definitionVersion", 2);
        var badVersionPath = COMMANDS.resolve("read-group-request-bad-version.json");
        Files.writeString(badVersionPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(invalid) + "\n");
        assertThrows(IllegalArgumentException.class, () -> validationGroup.readRequest(badVersionPath));
        var unknown = root.deepCopy(); unknown.put("unexpected", true);
        var badPropertyPath = COMMANDS.resolve("read-group-request-bad-property.json");
        Files.writeString(badPropertyPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(unknown) + "\n");
        assertThrows(IllegalArgumentException.class, () -> validationGroup.readRequest(badPropertyPath));
        var badGeneration = root.deepCopy();
        var badQuery = (com.fasterxml.jackson.databind.node.ObjectNode) badGeneration.withArray("members").get(0).get("query");
        ((com.fasterxml.jackson.databind.node.ObjectNode) badQuery.get("equalities")).put("source_version", "bad-version");
        var badGenerationPath = COMMANDS.resolve("read-group-request-bad-source-version.json");
        Files.writeString(badGenerationPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(badGeneration) + "\n");
        assertThrows(IllegalArgumentException.class, () -> validationGroup.readRequest(badGenerationPath));
        assertEquals(ReadGroupReader.Status.CANCELLED, validationGroup.read(request, () -> true).require("cache").status());
        verifyNoInteractions(untouchedJdbc);
        var after = snapshot(jdbc); assertEquals(before, after, "Java acceptance must not revise cache rows or physical/WAL state");
        assertEquals(sourceBefore, source.snapshot(), "Historical cache SELECTs do not modify or refresh source/MV");
        new RetailSentimentDailyV1MaterializationPort(jdbc, properties, true).verifyPrivateInstance();

        var mapper = new RetailSentimentDailyCacheMapper();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("task_id", "D100"); evidence.put("checked_at", Instant.now());
        evidence.put("status", "VERIFIED_STORED_GENERATIONS"); evidence.put("input_artifact", INPUT.toString());
        evidence.put("input_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inputBytes)));
        evidence.put("input_target", "attested-private-127.0.0.1:18822/19010");
        evidence.put("private_target_attestation", audit.required("private_target_attestation"));
        evidence.put("scope", "Historical Python-published private cache generation integrity; no current source freshness certification");
        evidence.put("reference_numeric_transport", "original Python publisher PGWire binary64 evidence; strict raw-bit comparison");
        evidence.put("current_cache_hit_certified", false); evidence.put("production_latest_certified", false);
        evidence.put("source_version", before.token()); evidence.put("cache_snapshot_before", before); evidence.put("cache_snapshot_after", after);
        evidence.put("actual_publisher_old_cursor_rejected", true); evidence.put("old_cursor_physical_source_version", oldSnapshot.token());
        evidence.put("actual_java_cursor_capture", CAPTURE.toString()); evidence.put("actual_java_cursor_capture_sha256", captureSha);
        evidence.put("actual_captured_cursor_rejected_before_row_query", true); evidence.put("old_actual_cursor", oldCursor);
        evidence.put("old_cursor_cache_snapshot", oldState);
        evidence.put("exact_generation_rows", exactActual.size()); evidence.put("exact_key_and_find_version_matched", true);
        evidence.put("range_from_inclusive", from); evidence.put("range_to_exclusive", to);
        evidence.put("range_rows", actualRange.size()); evidence.put("range_pages", pages);
        evidence.put("actual_exact_rows", exactActual.stream().map(row -> mapper.values(row).asMap()).toList());
        evidence.put("actual_range_rows", actualRange.stream().map(row -> mapper.values(row).asMap()).toList());
        evidence.put("fourteen_field_comparisons", actualRange.size() * 14);
        evidence.put("all_key_and_range_field_comparisons", statistics.fields);
        evidence.put("reference_double_bit_exact_comparisons", statistics.referenceBitExact);
        evidence.put("reference_double_tolerance_comparisons", statistics.rounded.size());
        evidence.put("reference_double_rounding_evidence", statistics.rounded);
        evidence.put("jdbc_storage_double_bit_comparisons", statistics.storageBits);
        evidence.put("configured_read_group_matched", true); evidence.put("cancelled_group_rejected", true);
        evidence.put("read_only_write_capability_rejected", true); evidence.put("bad_json_before_jdbc_rejected", true);
        evidence.put("bad_business_source_version_before_jdbc_rejected", true);
        evidence.put("publisher_invocations", 0); evidence.put("database_writes", 0); evidence.put("formal_written_rows", 0);
        evidence.put("source_snapshot_before", sourceBefore); evidence.put("source_snapshot_after", source.snapshot());
        Files.writeString(COMMANDS.resolve("java-cache-read-acceptance-20261006.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        }
    }

    record Snapshot(long id, String directory, long physicalTxn, long rowCount, long sequenceTxn, long writerTxn,
                            long pendingRows, long bufferedTxns, boolean tableSuspended, boolean walSuspended) {
        String token() { return "table:" + CACHE + ":id:" + id + ":directory:" + directory + ":txn:" + physicalTxn + ":wal-seq:" + sequenceTxn; }
    }

    static Snapshot snapshot(JdbcTemplate jdbc) {
        return jdbc.query("SELECT t.id,t.directoryName,t.table_txn,t.table_row_count,t.wal_pending_row_count,t.table_suspended,"
                + "t.walEnabled,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                + "FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + CACHE + "'", (org.springframework.jdbc.core.ResultSetExtractor<Snapshot>) rs -> {
            assertTrue(rs.next(), "Private cache table and WAL metadata required");
            assertTrue(rs.getBoolean("walEnabled"));
            var value = new Snapshot(number(rs, "id"), rs.getString("directoryName"), number(rs, "table_txn"),
                    number(rs, "table_row_count"), number(rs, "sequencerTxn"), number(rs, "writerTxn"),
                    number(rs, "wal_pending_row_count"), number(rs, "bufferedTxnSize"),
                    rs.getBoolean("table_suspended"), rs.getBoolean("suspended"));
            assertNotNull(value.directory()); assertFalse(value.directory().isBlank()); assertFalse(rs.next());
            assertEquals(value.sequenceTxn(), value.writerTxn()); assertEquals(0L, value.pendingRows());
            assertEquals(0L, value.bufferedTxns()); assertFalse(value.tableSuspended()); assertFalse(value.walSuspended());
            return value;
        });
    }

    static Snapshot auditSnapshot(JsonNode node) {
        assertTrue(node.required("settled").asBoolean());
        var physical = node.required("physical"); var wal = node.required("wal");
        assertTrue(physical.required("walEnabled").asBoolean());
        var value = new Snapshot(physical.required("id").longValue(), physical.required("directoryName").asText(),
                physical.required("table_txn").longValue(), physical.required("table_row_count").longValue(),
                wal.required("sequencerTxn").longValue(), wal.required("writerTxn").longValue(),
                physical.required("wal_pending_row_count").longValue(), wal.required("bufferedTxnSize").longValue(),
                physical.required("table_suspended").booleanValue(), wal.required("suspended").booleanValue());
        assertEquals(value.sequenceTxn(), value.writerTxn()); assertEquals(0L, value.pendingRows());
        assertEquals(0L, value.bufferedTxns()); assertFalse(value.tableSuspended()); assertFalse(value.walSuspended());
        return value;
    }
    static void matchesAudit(Snapshot current, JsonNode node) {
        assertTrue(node.required("settled").asBoolean());
        var physical = node.required("physical"); var wal = node.required("wal");
        assertEquals(current.id(), physical.required("id").longValue());
        assertEquals(current.directory(), physical.required("directoryName").asText());
        assertEquals(current.physicalTxn(), physical.required("table_txn").longValue());
        assertEquals(current.rowCount(), physical.required("table_row_count").longValue());
        assertEquals(current.sequenceTxn(), wal.required("sequencerTxn").longValue());
        assertEquals(current.writerTxn(), wal.required("writerTxn").longValue());
        assertEquals(current.pendingRows(), physical.required("wal_pending_row_count").longValue());
        assertEquals(current.bufferedTxns(), wal.required("bufferedTxnSize").longValue());
        assertEquals(current.tableSuspended(), physical.required("table_suspended").booleanValue());
        assertEquals(current.walSuspended(), wal.required("suspended").booleanValue());
    }

    static RetailSentimentDailyCache record(JsonNode node) {
        assertTrue(node.isObject()); var fields = new HashSet<String>(); node.fieldNames().forEachRemaining(fields::add);
        assertEquals(FIELDS, fields, "All fourteen cache fields and no inferred extra fields required");
        assertTrue(node.required("trade_date").isTextual()); assertTrue(node.required("source_version").isTextual());
        String text = node.required("trade_date").asText();
        LocalDate date;
        if (text.length() == 10) date = LocalDate.parse(text);
        else {
            var instant = Instant.parse(text); date = instant.atOffset(ZoneOffset.UTC).toLocalDate();
            assertEquals(date.atStartOfDay().toInstant(ZoneOffset.UTC), instant, "Cache key is an exact UTC-midnight date carrier");
        }
        return new RetailSentimentDailyCache(date, decimal(node, "avg_retail_ratio"), decimal(node, "avg_retail_entropy"),
                decimal(node, "total_retail_amount_yi"), decimal(node, "total_retail_net_inflow_yi"), decimal(node, "avg_rel_aggro"),
                integer(node, "total_q1"), integer(node, "total_q3"), decimal(node, "avg_wash_trade_ratio"),
                integer(node, "total_spoof_count"), integer(node, "total_manipulation_count"), decimal(node, "avg_mfi_score"),
                decimal(node, "total_main_net_yi"), node.required("source_version").asText());
    }

    private static long integer(JsonNode node, String name) {
        var value = node.required(name); assertTrue(value.isIntegralNumber() && value.canConvertToLong()); return value.longValue();
    }
    private static Double decimal(JsonNode node, String name) {
        var value = node.required(name); if (value.isNull()) return null;
        assertTrue(value.isNumber() && Double.isFinite(value.doubleValue())); return value.doubleValue();
    }
    private static long number(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name); assertFalse(rs.wasNull(), "Required metadata: " + name); assertTrue(value >= 0); return value;
    }
    static final class Comparisons {
        long fields, referenceBitExact, storageBits;
        final List<Map<String, Object>> rounded = new ArrayList<>(); // Expected evidence uses authoritative PGWire binary64.
    }
    static void compare(RetailSentimentDailyCache expected, RetailSentimentDailyCache actual, Comparisons statistics) {
        assertEquals(expected.key(), actual.key()); assertEquals(expected.totalQ1(), actual.totalQ1());
        assertEquals(expected.totalQ3(), actual.totalQ3()); assertEquals(expected.totalSpoofCount(), actual.totalSpoofCount());
        assertEquals(expected.totalManipulationCount(), actual.totalManipulationCount());
        var mapper = new RetailSentimentDailyCacheMapper();
        var expectedValues = mapper.values(expected); var actualValues = mapper.values(actual);
        for (var field : DOUBLE_FIELDS)
            decimal(expectedValues.get(field, Double.class), actualValues.get(field, Double.class), field, expected.key(), statistics);
        statistics.fields += 14;
    }
    private static void decimal(Double expected, Double actual, String field, RetailSentimentDailyCacheKey key, Comparisons statistics) {
        if (expected == null || actual == null) { assertEquals(expected, actual, field); return; }
        assertTrue(Double.isFinite(expected) && Double.isFinite(actual));
        long expectedBits = Double.doubleToRawLongBits(expected), actualBits = Double.doubleToRawLongBits(actual);
        assertEquals(expectedBits, actualBits, "Authoritative Python PGWire audit/JDBC binary64 mismatch: " + field + " " + key);
        statistics.referenceBitExact++;
    }
    static void storageBits(JdbcTemplate jdbc, RetailSentimentDailyCache actual, Comparisons statistics) {
        var stored = jdbc.query(connection -> {
            var statement = connection.prepareStatement("SELECT " + String.join(",", DOUBLE_FIELDS) + " FROM " + CACHE
                    + " WHERE trade_date=? AND source_version=? LIMIT 2");
            statement.setQueryTimeout(20); statement.setMaxRows(2);
            statement.setTimestamp(1, Timestamp.from(actual.tradeDate().atStartOfDay().toInstant(ZoneOffset.UTC)),
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.setString(2, actual.sourceVersion()); return statement;
        }, (rs, row) -> {
            var values = new LinkedHashMap<String, Object>();
            for (var field : DOUBLE_FIELDS) values.put(field, nullableDouble(rs, field));
            return new DatasetValues(values);
        });
        assertEquals(1, stored.size());
        var actualValues = new RetailSentimentDailyCacheMapper().values(actual);
        for (var field : DOUBLE_FIELDS) bits(stored.getFirst().get(field, Double.class), actualValues.get(field, Double.class), statistics);
    }
    private static Double nullableDouble(ResultSet rs, String field) throws SQLException {
        double value = rs.getDouble(field); return rs.wasNull() ? null : value;
    }
    private static void bits(Double stored, Double actual, Comparisons statistics) {
        if (stored == null || actual == null) assertEquals(stored, actual);
        else { assertEquals(Double.doubleToRawLongBits(stored), Double.doubleToRawLongBits(actual)); statistics.storageBits++; }
    }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static QuestDbProperties properties() {
        var properties = new QuestDbProperties(); properties.setHost("127.0.0.1"); properties.setPgPort(18822);
        properties.setQwpPort(19010); properties.setDatabase("qdb"); properties.setUsername("admin"); properties.setPassword("quest");
        return properties;
    }
    static HikariDataSource pool(String name) {
        var dataSource = new HikariDataSource(); dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl("jdbc:postgresql://127.0.0.1:18822/qdb?sslmode=disable");
        dataSource.setUsername("admin"); dataSource.setPassword("quest"); dataSource.setPoolName(name);
        dataSource.setMaximumPoolSize(4); dataSource.setMinimumIdle(0); dataSource.setConnectionTimeout(5000); dataSource.setValidationTimeout(3000);
        return dataSource;
    }
    static JdbcTemplate jdbc(HikariDataSource dataSource) { var jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(20); return jdbc; }
}
