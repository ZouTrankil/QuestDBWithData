package com.zoutrankil.data.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.RetailSentimentDailyV1Mapper;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** D098 actual SELECT acceptance after the isolated canonical job has materialized all three real days. */
class RetailSentimentDailyV1LiveReadAcceptanceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final LocalDate THROUGH = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO_EXCLUSIVE = THROUGH.plusDays(1);
    private static final List<LocalDate> DAYS = List.of(FROM, FROM.plusDays(1), THROUGH);
    private static final Path DIRECTORY = Path.of("artifacts/java-migration/D098/commands");

    @Test void actualNativePagesAndConfiguredReadGroupMatchAllThirteenBoundedSourceFields() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D098_LIVE_READ")));
        var privateJdbc = jdbc("127.0.0.1", 18822, "admin", "quest");
        var properties = new QuestDbProperties();
        properties.setHost("127.0.0.1");
        properties.setPgPort(18822);
        properties.setQwpPort(19010);
        properties.setUsername("admin");
        properties.setPassword("quest");
        properties.setDatabase("qdb");
        var attestation = new RetailSentimentDailyV1MaterializationPort(privateJdbc, properties, true);
        attestation.verifyPrivateInstance();
        var source = new RetailSentimentDailyV1MaterializationPort(privateJdbc, properties, false);
        var before = source.snapshot();
        assertTrue(before.valid(), "The enabled acceptance requires a valid MV; missing setup is a failure");
        assertTrue(before.caughtUp() && before.sourceSettled() && before.mvSettled());
        long rawBefore = source.sourceRawRows(FROM, THROUGH);
        assertEquals(23773L, rawBefore, "The three real source days must already be installed");
        var expected = source.expected(FROM, THROUGH);
        assertEquals(DAYS, expected.stream().map(RetailSentimentDailyV1::tradeDate).toList());
        assertEquals(3L, source.outputRowCount(), "Run this acceptance after all three canonical materialization days");
        assertEquals(before, source.snapshot(), "Source aggregation must use the frozen native snapshot");

        var reader = new QuestDbBoundedReader(privateJdbc);
        var repository = new RetailSentimentDailyV1ReadRepository(reader);
        var actual = new ArrayList<RetailSentimentDailyV1>();
        DatasetReadCursor cursor = null;
        String physicalVersion = null;
        int pages = 0;
        var pageEvidence = new ArrayList<Map<String, Object>>();
        do {
            var page = repository.findRange(FROM, TO_EXCLUSIVE, 1, cursor);
            assertEquals(repository.definition().datasetId(), page.datasetId());
            assertEquals(repository.definition().schemaVersion(), page.definitionVersion());
            assertEquals(1, page.rows().size());
            assertNotNull(page.sourceVersion());
            if (physicalVersion == null) physicalVersion = page.sourceVersion();
            else assertEquals(physicalVersion, page.sourceVersion(), "Cursor pages must retain one actual physical version");
            actual.addAll(page.rows());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("page", ++pages);
            entry.put("sourceVersion", page.sourceVersion());
            entry.put("completeDateKeys", page.rows().stream().map(RetailSentimentDailyV1::tradeDate).toList());
            entry.put("hasMore", page.hasMore());
            pageEvidence.add(entry);
            cursor = page.nextCursor();
            if (cursor != null) assertEquals(physicalVersion, cursor.sourceVersion());
            assertTrue(pages <= 3, "Three finite daily buckets cannot produce a fourth page");
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(DAYS, actual.stream().map(RetailSentimentDailyV1::tradeDate).toList());
        assertEquals(3, new HashSet<>(actual.stream().map(RetailSentimentDailyV1::tradeDate).toList()).size());
        assertTrue(physicalVersion.contains("base-id:") && physicalVersion.contains("mv-txn:")
                && physicalVersion.contains("mv-seq-txn:"));
        assertRowsEquivalent(expected, actual);
        for (int index = 0; index < DAYS.size(); index++) {
            var byKey = repository.findForDate(DAYS.get(index));
            assertEquals(physicalVersion, byKey.sourceVersion());
            assertEquals(List.of(actual.get(index)), byKey.rows());
            assertFalse(byKey.hasMore());
        }
        var noBucket = repository.findForDate(LocalDate.of(2026, 9, 20));
        assertTrue(noBucket.rows().isEmpty());
        assertEquals(physicalVersion, noBucket.sourceVersion());

        var registry = registry(repository, reader);
        var group = new ReadGroupConfiguration().readGroupReader(registry, reader);
        var query = new DatasetReadQuery(repository.definition().storageColumns(), Map.of(), "trade_date",
                FROM, TO_EXCLUSIVE, 31, null);
        var requestDocument = Map.of("timeoutMillis", 30000, "members", List.of(Map.of(
                "memberId", "retail", "datasetId", repository.definition().datasetId(),
                "definitionVersion", repository.definition().schemaVersion(), "query", query)));
        Files.createDirectories(DIRECTORY);
        var requestPath = DIRECTORY.resolve("strictread-group-request-D098.json");
        Files.writeString(requestPath, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(requestDocument));
        var request = group.readRequest(requestPath);
        assertEquals(query, request.members().getFirst().query(), "Use the strict JSON parser and actual configured binding");
        var grouped = group.read(request, () -> false);
        assertTrue(grouped.complete());
        var member = grouped.require("retail");
        assertEquals(RetailSentimentDailyV1.class, member.rowType());
        var typed = member.typedPage(RetailSentimentDailyV1.class);
        assertEquals(physicalVersion, typed.sourceVersion());
        assertEquals(actual, typed.rows());
        assertRowsEquivalent(expected, typed.rows());
        assertFalse(typed.hasMore());
        var cancelled = group.read(request, () -> true);
        assertFalse(cancelled.complete());
        assertEquals(ReadGroupReader.Status.CANCELLED, cancelled.require("retail").status());
        assertNull(cancelled.require("retail").page());
        assertThrows(IllegalArgumentException.class,
                () -> repository.definition().requireCapability(DatasetDefinition.Capability.WRITE));
        assertThrows(IllegalArgumentException.class,
                () -> repository.definition().requireCapability(DatasetDefinition.Capability.STATIC_REPLACE));
        assertThrows(IllegalArgumentException.class,
                () -> repository.definition().requireCapability(DatasetDefinition.Capability.WAL_REPLACE));

        var after = source.snapshot();
        long rawAfter = source.sourceRawRows(FROM, THROUGH);
        assertEquals(rawBefore, rawAfter);
        assertEquals(before, after, "Actual typed reads must not change the frozen source or MV");
        attestation.verifyPrivateInstance();

        var formalJdbc = jdbc(required("APP_QUESTDB_HOST"),
                Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812")),
                required("APP_QUESTDB_USERNAME"), required("APP_QUESTDB_PASSWORD"));
        var formalReader = new QuestDbBoundedReader(formalJdbc);
        var formalRepository = new RetailSentimentDailyV1ReadRepository(formalReader);
        var formalStateBefore = formalMetadata(formalJdbc);
        assertEquals("invalid", formalStateBefore.get("view_status"));
        assertThrows(IllegalStateException.class, () -> formalRepository.findForDate(FROM));
        var formalGroup = new ReadGroupConfiguration().readGroupReader(registry(formalRepository, formalReader), formalReader);
        var formalRequest = formalGroup.readRequest(requestPath);
        var refused = formalGroup.read(formalRequest, () -> false);
        assertFalse(refused.complete());
        assertEquals(ReadGroupReader.Status.FAILED, refused.require("retail").status());
        assertEquals("IllegalStateException", refused.require("retail").errorCode());
        assertNull(refused.require("retail").page());
        assertEquals(formalStateBefore, formalMetadata(formalJdbc), "Formal SELECT rejection must not mutate MV metadata");

        var mapper = new RetailSentimentDailyV1Mapper();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("taskId", "D098");
        evidence.put("checkedAt", Instant.now());
        evidence.put("status", "VERIFIED_ISOLATED_READ");
        evidence.put("target", "private-127.0.0.1:19010/18822");
        evidence.put("targetId", source.targetId());
        evidence.put("privateDataRoot", Path.of("var/d098-isolated-questdb").toAbsolutePath().normalize().toString());
        evidence.put("privateProcessAttestedBeforeAndAfter", true);
        evidence.put("rangeFromInclusive", FROM);
        evidence.put("rangeToExclusive", TO_EXCLUSIVE);
        evidence.put("sourceRawRowsBefore", rawBefore);
        evidence.put("sourceRawRowsAfter", rawAfter);
        evidence.put("rows", actual.size());
        evidence.put("completeDateKeys", DAYS);
        evidence.put("fields", repository.definition().storageColumns());
        evidence.put("fieldComparisons", actual.size() * 13);
        evidence.put("pages", pages);
        evidence.put("pageEvidence", pageEvidence);
        evidence.put("actualMvRows", actual.stream().map(row -> mapper.values(row).asMap()).toList());
        evidence.put("expectedDirectBaseAggregates", expected.stream().map(row -> mapper.values(row).asMap()).toList());
        evidence.put("comparison", "Date and nullable LONG exact; nullable DOUBLE finite with atol=1e-8, rtol=1e-10; no zero filling");
        evidence.put("physicalReadVersion", physicalVersion);
        evidence.put("sourceVersion", before.sourceVersion());
        evidence.put("sourceStableVersion", before.stableVersion());
        evidence.put("sourceSnapshotBefore", before);
        evidence.put("sourceSnapshotAfter", after);
        evidence.put("validCaughtUpAndWalSettled", true);
        evidence.put("sourceUniverseReadyCertified", false);
        evidence.put("configuredReadGroupMatched", true);
        evidence.put("configuredBindingRowType", RetailSentimentDailyV1.class.getName());
        evidence.put("readGroupRequest", requestPath.toString());
        evidence.put("cancelledGroupRejectedWithoutPage", true);
        evidence.put("directWriteAndReplacementRejected", true);
        evidence.put("formalInvalidMetadata", formalStateBefore);
        evidence.put("formalInvalidMvRejected", true);
        evidence.put("formalFailedMemberHasNoPage", true);
        evidence.put("formalWrittenRows", 0);
        evidence.put("readWrites", 0);
        evidence.put("baseRefreshJobId", RetailSentimentDailyV1JobService.JOB_ID);
        Files.writeString(DIRECTORY.resolve("java-mv-read-acceptance-20261006.json"),
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private static DatasetRegistry registry(RetailSentimentDailyV1ReadRepository repository,
                                             QuestDbBoundedReader reader) {
        // Only the MV member is queried. Source dependency definitions do not assert a full source schema in this fixture.
        return new DatasetRegistry(List.of(repository, new L2DailyFeaturesReadRepository(reader, "l2_daily_features"),
                new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"), new ExchangeCalendarReadRepository(reader)));
    }

    private static void assertRowsEquivalent(List<RetailSentimentDailyV1> expected,
                                             List<RetailSentimentDailyV1> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(RetailSentimentDailyV1MaterializationPort.equivalent(expected.get(index), actual.get(index)),
                    "All thirteen source and output values must match for " + expected.get(index).tradeDate());
        }
    }

    private static Map<String, Object> formalMetadata(JdbcTemplate jdbc) {
        return jdbc.queryForMap("SELECT view_status,invalidation_reason,base_table_name,view_sql,"
                + "refresh_base_table_txn,base_table_txn FROM materialized_views() "
                + "WHERE view_name='mv_retail_sentiment_daily_v1'");
    }

    private static JdbcTemplate jdbc(String host, int port, String user, String password) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + host + ":" + port + "/qdb?sslmode=disable");
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);
        return jdbc;
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing setting: " + name);
        return value;
    }
}
