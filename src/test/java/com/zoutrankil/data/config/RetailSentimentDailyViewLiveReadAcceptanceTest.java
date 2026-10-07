package com.zoutrankil.data.config;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarReadRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zaxxer.hikari.HikariDataSource;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.RetailSentimentDailyViewMapper;
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

/** D099 actual ordinary alias SELECT acceptance after the isolated canonical job has materialized all three real days. */
class RetailSentimentDailyViewLiveReadAcceptanceTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final LocalDate THROUGH = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO_EXCLUSIVE = THROUGH.plusDays(1);
    private static final List<LocalDate> DAYS = List.of(FROM, FROM.plusDays(1), THROUGH);
    private static final Path DIRECTORY = Path.of("artifacts/java-migration/D099/commands");

    @Test void actualAliasPagesAndConfiguredReadGroupMatchAllThirteenParentAndSourceFields() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D099_LIVE_READ")));
        try (var privatePool = pool("d099-private-read", "127.0.0.1", 18822, "admin", "quest");
             var formalPool = pool("d099-formal-read", required("APP_QUESTDB_HOST"),
                     Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812")),
                     required("APP_QUESTDB_USERNAME"), required("APP_QUESTDB_PASSWORD"))) {
        var privateJdbc = jdbc(privatePool);
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
        assertEquals("9:36", before.sourceVersion(), "The accepted D098 source frontier must be retained");
        assertEquals(11L, before.mvId());
        var aliasBefore = aliasMetadata(privateJdbc);
        assertAliasContract(aliasBefore);
        long rawBefore = source.sourceRawRows(FROM, THROUGH);
        assertEquals(23773L, rawBefore, "The three real source days must already be installed");
        var expected = source.expected(FROM, THROUGH);
        assertEquals(DAYS, expected.stream().map(RetailSentimentDailyV1::tradeDate).toList());
        assertEquals(3L, source.outputRowCount(), "Run this acceptance after all three canonical materialization days");
        assertEquals(before, source.snapshot(), "Source aggregation must use the frozen native snapshot");

        var reader = new QuestDbBoundedReader(privateJdbc);
        var repository = new RetailSentimentDailyViewReadRepository(reader);
        var parentRepository = new RetailSentimentDailyV1ReadRepository(reader);
        var parentPage = parentRepository.findRange(FROM, TO_EXCLUSIVE, 31, null);
        assertEquals(DAYS, parentPage.rows().stream().map(RetailSentimentDailyV1::tradeDate).toList());
        assertFalse(parentPage.hasMore());
        assertNotNull(parentPage.sourceVersion());
        var actual = new ArrayList<RetailSentimentDailyView>();
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
            entry.put("completeDateKeys", page.rows().stream().map(RetailSentimentDailyView::tradeDate).toList());
            entry.put("hasMore", page.hasMore());
            pageEvidence.add(entry);
            cursor = page.nextCursor();
            if (cursor != null) assertEquals(physicalVersion, cursor.sourceVersion());
            assertTrue(pages <= 3, "Three finite daily buckets cannot produce a fourth page");
        } while (cursor != null);
        assertEquals(3, pages);
        assertEquals(DAYS, actual.stream().map(RetailSentimentDailyView::tradeDate).toList());
        assertEquals(3, new HashSet<>(actual.stream().map(RetailSentimentDailyView::tradeDate).toList()).size());
        assertTrue(physicalVersion.contains("base-id:") && physicalVersion.contains("mv-txn:")
                && physicalVersion.contains("mv-seq-txn:"));
        assertRowsEquivalent(expected, actual);
        assertAliasParentExact(parentPage.rows(), actual);
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
        var requestPath = DIRECTORY.resolve("strictread-group-request-D099.json");
        Files.writeString(requestPath, JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(requestDocument));
        var request = group.readRequest(requestPath);
        assertEquals(query, request.members().getFirst().query(), "Use the strict JSON parser and actual configured binding");
        var grouped = group.read(request, () -> false);
        assertTrue(grouped.complete());
        var member = grouped.require("retail");
        assertEquals(RetailSentimentDailyView.class, member.rowType());
        var typed = member.typedPage(RetailSentimentDailyView.class);
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
        assertEquals(aliasBefore, aliasMetadata(privateJdbc), "Alias SQL and schema must retain their exact binding throughout reads");
        attestation.verifyPrivateInstance();

        var formalJdbc = jdbc(formalPool);
        var formalReader = new QuestDbBoundedReader(formalJdbc);
        var formalRepository = new RetailSentimentDailyViewReadRepository(formalReader);
        var formalStateBefore = formalMetadata(formalJdbc);
        var formalAliasBefore = aliasMetadata(formalJdbc);
        assertAliasContract(formalAliasBefore);
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
        assertEquals(formalAliasBefore, aliasMetadata(formalJdbc), "Formal alias SQL and schema must remain unchanged");

        var mapper = new RetailSentimentDailyViewMapper();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("taskId", "D099");
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
        var parentMapper = new RetailSentimentDailyV1Mapper();
        evidence.put("actualAliasRows", actual.stream().map(row -> mapper.values(row).asMap()).toList());
        evidence.put("actualParentMvRows", parentPage.rows().stream().map(row -> parentMapper.values(row).asMap()).toList());
        evidence.put("expectedDirectBaseAggregates", expected.stream().map(row -> parentMapper.values(row).asMap()).toList());
        evidence.put("aliasParentFieldComparisons", actual.size() * 13);
        evidence.put("aliasParentAllFieldsExact", true);
        evidence.put("aliasDirectBaseFieldComparisons", actual.size() * 13);
        evidence.put("aliasDirectBaseAllFieldsMatched", true);
        evidence.put("parentPhysicalReadVersion", parentPage.sourceVersion());
        evidence.put("aliasMetadataBefore", aliasBefore);
        evidence.put("aliasMetadataAfter", aliasMetadata(privateJdbc));
        evidence.put("comparison", "Date and nullable LONG exact; nullable DOUBLE finite with atol=1e-8, rtol=1e-10; no zero filling");
        evidence.put("physicalReadVersion", physicalVersion);
        evidence.put("sourceVersion", before.sourceVersion());
        evidence.put("sourceStableVersion", before.stableVersion());
        evidence.put("sourceSnapshotBefore", before);
        evidence.put("sourceSnapshotAfter", after);
        evidence.put("validCaughtUpAndWalSettled", true);
        evidence.put("sourceUniverseReadyCertified", false);
        evidence.put("configuredReadGroupMatched", true);
        evidence.put("configuredBindingRowType", RetailSentimentDailyView.class.getName());
        evidence.put("readGroupRequest", requestPath.toString());
        evidence.put("cancelledGroupRejectedWithoutPage", true);
        evidence.put("directWriteAndReplacementRejected", true);
        evidence.put("formalInvalidMetadata", formalStateBefore);
        evidence.put("formalAliasMetadata", formalAliasBefore);
        evidence.put("formalInvalidMvRejected", true);
        evidence.put("formalFailedMemberHasNoPage", true);
        evidence.put("formalWrittenRows", 0);
        evidence.put("readWrites", 0);
        evidence.put("baseRefreshJobId", RetailSentimentDailyV1JobService.JOB_ID);
        Files.writeString(DIRECTORY.resolve("java-alias-read-acceptance-20261006.json"),
                JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        }
    }

    private static DatasetRegistry registry(RetailSentimentDailyViewReadRepository repository,
                                             QuestDbBoundedReader reader) {
        // Only the MV member is queried. Source dependency definitions do not assert a full source schema in this fixture.
        return new DatasetRegistry(List.of(repository, new RetailSentimentDailyV1ReadRepository(reader),
                new L2DailyFeaturesReadRepository(reader, "l2_daily_features"),
                new L2DatasetManifestReadRepository(reader, "l2_dataset_manifest"), new ExchangeCalendarReadRepository(reader)));
    }

    private static void assertRowsEquivalent(List<RetailSentimentDailyV1> expected,
                                             List<RetailSentimentDailyView> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(RetailSentimentDailyV1MaterializationPort.equivalent(expected.get(index), parent(actual.get(index))),
                    "All thirteen source and output values must match for " + expected.get(index).tradeDate());
        }
    }

    private static RetailSentimentDailyV1 parent(RetailSentimentDailyView row) {
        return new RetailSentimentDailyV1(row.tradeDate(), row.avgRetailRatio(), row.avgRetailEntropy(),
                row.totalRetailAmountYi(), row.totalRetailNetInflowYi(), row.avgRelAggro(), row.totalQ1(), row.totalQ3(),
                row.avgWashTradeRatio(), row.totalSpoofCount(), row.totalManipulationCount(), row.avgMfiScore(), row.totalMainNetYi());
    }

    private static void assertAliasParentExact(List<RetailSentimentDailyV1> expected, List<RetailSentimentDailyView> actual) {
        var parentMapper = new RetailSentimentDailyV1Mapper();
        var aliasMapper = new RetailSentimentDailyViewMapper();
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(parentMapper.values(expected.get(index)).asMap(), aliasMapper.values(actual.get(index)).asMap(),
                    "Date, nullable LONG, nullable DOUBLE bits and nulls must pass through exactly");
        }
    }

    private record AliasMetadata(Map<String, Object> view, List<Map<String, Object>> columns) {}

    private static AliasMetadata aliasMetadata(JdbcTemplate jdbc) {
        var aliases = jdbc.queryForList("SELECT view_name,view_sql FROM views() WHERE view_name='v_retail_sentiment_daily' LIMIT 2");
        assertEquals(1, aliases.size(), "Exactly one real ordinary alias must exist");
        return new AliasMetadata(aliases.getFirst(), jdbc.queryForList(
                "SELECT \"column\",type FROM table_columns('v_retail_sentiment_daily') LIMIT 14"));
    }

    private static void assertAliasContract(AliasMetadata alias) {
        assertEquals("v_retail_sentiment_daily", alias.view().get("view_name"));
        var sql = String.valueOf(alias.view().get("view_sql")).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s;()]", "");
        assertEquals("select*frommv_retail_sentiment_daily_v1", sql, "Alias must select the canonical MV exactly");
        assertEquals(13, alias.columns().size());
        var fields = RetailSentimentDailyViewDataset.DEFINITION.columns();
        for (int index = 0; index < fields.size(); index++) {
            assertEquals(fields.get(index).storageName(), alias.columns().get(index).get("column"));
            assertEquals(fields.get(index).storageType().name(), String.valueOf(alias.columns().get(index).get("type")).toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static Map<String, Object> formalMetadata(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<>(jdbc.queryForMap("SELECT view_status,invalidation_reason,base_table_name,view_sql,"
                + "refresh_base_table_txn,base_table_txn FROM materialized_views() WHERE view_name='mv_retail_sentiment_daily_v1'"));
        result.put("physicalSourceAndMv", jdbc.queryForList("SELECT t.table_name,t.id,t.directoryName,t.table_txn,"
                + "t.table_row_count,t.wal_pending_row_count,t.table_suspended,w.sequencerTxn,w.writerTxn,w.bufferedTxnSize,w.suspended "
                + "FROM tables() t JOIN wal_tables() w ON t.table_name=w.name "
                + "WHERE t.table_name IN ('l2_daily_features','mv_retail_sentiment_daily_v1') ORDER BY t.table_name LIMIT 3"));
        assertEquals(2, ((List<?>) result.get("physicalSourceAndMv")).size());
        return result;
    }

    private static HikariDataSource pool(String name, String host, int port, String user, String password) {
        var dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/qdb?sslmode=disable");
        dataSource.setUsername(user); dataSource.setPassword(password);
        dataSource.setPoolName(name); dataSource.setMaximumPoolSize(4); dataSource.setMinimumIdle(0);
        dataSource.setConnectionTimeout(5000); dataSource.setValidationTimeout(3000);
        return dataSource;
    }

    private static JdbcTemplate jdbc(HikariDataSource dataSource) {
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
