package com.zoutrankil.data.repository;

import com.zoutrankil.data.derived.storage.RetailSentimentDailyV1MaterializationPort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

import com.zoutrankil.data.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

/** A valid ordinary alias cannot hide an invalid, changing or recreated native MV dependency. */
class QuestDbRetailSentimentViewDependencyReadGuardTest {
    private static final String BASE = "mv_retail_sentiment_daily_v1";
    private static final String SQL = "SELECT * FROM " + BASE;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final DatasetDefinition DEFINITION = definition(1, List.of(BASE));

    private static DatasetDefinition definition(int version, List<String> dependencies) {
        var base = RetailSentimentDailyV1Dataset.DEFINITION;
        return new DatasetDefinition("v_retail_sentiment_daily", version, base.provider(), base.owner(),
                "v_retail_sentiment_daily", ObjectKind.VIEW, base.columns(), base.businessKey(), List.of(),
                null, Partition.NONE, false, Set.of(Capability.READ), dependencies,
                "Exact SELECT * alias of the explicit native materialized dependency");
    }

    private static Map<String, Object> alias() {
        var result = new LinkedHashMap<String, Object>();
        result.put("view_sql", SQL);
        result.put("view_table_dir_name", "v_retail_sentiment_daily~241");
        result.put("view_status", "valid");
        result.put("invalidation_reason", null);
        result.put("view_status_update_time", "2026-09-30T01:27:52.505775Z");
        return result;
    }

    private static Map<String, Object> materialized() {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("view_status", "valid"), Map.entry("base_table_id", 9L),
                Map.entry("base_table_name", RetailSentimentDailyV1MaterializationPort.SOURCE),
                Map.entry("view_sql", RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL),
                Map.entry("base_table_txn", 36L), Map.entry("refresh_base_table_txn", 36L),
                Map.entry("base_physical_txn", 36L), Map.entry("base_writer_txn", 36L),
                Map.entry("base_seq_txn", 36L), Map.entry("base_buffered_txns", 0L),
                Map.entry("view_table_id", 11L), Map.entry("view_physical_txn", 11L),
                Map.entry("view_writer_txn", 11L), Map.entry("view_seq_txn", 11L),
                Map.entry("view_buffered_txns", 0L), Map.entry("base_suspended", false),
                Map.entry("view_suspended", false), Map.entry("is_materialized_view", true)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final QuestDbBoundedReader reader = new QuestDbBoundedReader(jdbc);
        final AtomicInteger rowReads = new AtomicInteger();
        final AtomicInteger aliasReads = new AtomicInteger();
        final AtomicInteger materializedReads = new AtomicInteger();
        final Map<String, Object> alias = alias();
        final Map<String, Object> materialized = materialized();
        boolean aliasPresent = true;
        boolean materializedPresent = true;
        Runnable duringRead = () -> {};
        Runnable duringMaterializedMetadata = () -> {};

        List<DatasetValues> returnedRows = List.of(new DatasetValues(Map.of("trade_date", FROM)),
                new DatasetValues(Map.of("trade_date", FROM.plusDays(1))));

        Fixture() throws Exception { this(DEFINITION); }
        Fixture(DatasetDefinition schemaDefinition) throws Exception {
            when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                boolean isAlias = sql.contains("FROM views()");
                Map<String, Object> state = isAlias ? alias : materialized;
                if (isAlias) aliasReads.incrementAndGet();
                else {
                    assertTrue(sql.contains("WHERE m.view_name='" + BASE + "'"),
                            "Guard must check the explicitly declared MV, rather than the ordinary alias");
                    materializedReads.incrementAndGet();
                }
                var rs = mock(ResultSet.class);
                var wasNull = new AtomicBoolean();
                when(rs.next()).thenReturn(isAlias ? aliasPresent : materializedPresent, false);
                when(rs.getString(anyString())).thenAnswer(get -> (String) state.get(get.getArgument(0)));
                when(rs.getBoolean(anyString())).thenAnswer(get -> Boolean.TRUE.equals(state.get(get.getArgument(0))));
                when(rs.getLong(anyString())).thenAnswer(get -> {
                    Object value = state.get(get.getArgument(0));
                    wasNull.set(value == null);
                    return value == null ? 0L : ((Number) value).longValue();
                });
                when(rs.wasNull()).thenAnswer(get -> wasNull.get());
                Object result = ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs);
                if (!isAlias) duringMaterializedMetadata.run();
                return result;
            });
            var schema = new LinkedHashMap<String, String>();
            schemaDefinition.columns().forEach(c -> schema.put(c.storageName(), c.storageType().name()));
            when(jdbc.query(any(PreparedStatementCreator.class),
                    org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, String>>>any()))
                    .thenReturn(schema);
            when(jdbc.query(any(PreparedStatementCreator.class),
                    org.mockito.ArgumentMatchers.<RowMapper<DatasetValues>>any())).thenAnswer(call -> {
                rowReads.incrementAndGet();
                duringRead.run();
                return returnedRows;
            });
        }

        DatasetReadPage<LocalDate> read(DatasetReadCursor cursor) { return read(DEFINITION, cursor); }
        DatasetReadPage<LocalDate> read(DatasetDefinition definition, DatasetReadCursor cursor) {
            return reader.read(definition, new DatasetReadQuery(definition.storageColumns(), Map.of(),
                    "trade_date", FROM, FROM.plusDays(2), 1, cursor), "caller-version",
                    row -> row.get("trade_date", LocalDate.class));
        }
    }

    @Test void stableAliasBindsDirectorySqlAndCompleteNativeMvVersionToItsCursor() throws Exception {
        var fixture = new Fixture();
        var page = fixture.read(null);
        assertEquals(List.of(FROM), page.rows());
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SQL.getBytes(StandardCharsets.UTF_8)));
        assertEquals("view-dir:v_retail_sentiment_daily~241:view-sql:" + sha
                + ":view-status-updated:2026-09-30T01:27:52.505775Z:dependency:" + BASE
                + ":base-id:9:base-txn:36:base-seq-txn:36:refresh-txn:36:mv-id:11:mv-txn:11:mv-seq-txn:11",
                page.sourceVersion());
        assertEquals(page.sourceVersion(), page.nextCursor().sourceVersion());
        assertEquals(4, fixture.aliasReads.get(), "Alias is rechecked around both native MV metadata snapshots");
        assertEquals(2, fixture.materializedReads.get());
    }

    @Test void validAliasRejectsInvalidLaggingUnsettledOrSuspendedMvBeforeFetchingRows() throws Exception {
        for (var change : List.of(Map.entry("view_status", (Object) "invalid"),
                Map.entry("refresh_base_table_txn", (Object) 13L), Map.entry("base_seq_txn", (Object) 15L),
                Map.entry("base_writer_txn", (Object) 13L), Map.entry("base_buffered_txns", (Object) 1L),
                Map.entry("view_writer_txn", (Object) 1L), Map.entry("view_buffered_txns", (Object) 1L),
                Map.entry("base_suspended", (Object) true), Map.entry("view_suspended", (Object) true))) {
            var fixture = new Fixture();
            fixture.materialized.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get(), change.getKey());
        }
    }

    @Test void validAliasRejectsSameNameFreshMvWithWrongBaseOrAggregationOnItsFirstPage() throws Exception {
        for (var change : List.of(Map.entry("base_table_name", "another_l2_source"),
                Map.entry("view_sql", RetailSentimentDailyV1MaterializationPort.DEFINITION_SQL
                        .replace("avg(gmm_retail_ratio)", "sum(gmm_retail_ratio)")))) {
            var fixture = new Fixture();
            fixture.materialized.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get(), "A valid alias cannot certify different native MV semantics");
        }
    }

    @Test void aliasCannotFallbackToAnOrdinaryTableOrAnAbsentMv() throws Exception {
        var table = new Fixture();
        table.materialized.put("is_materialized_view", false);
        assertThrows(IllegalStateException.class, () -> table.read(null));
        assertEquals(0, table.rowReads.get());
        var absent = new Fixture();
        absent.materializedPresent = false;
        assertThrows(IllegalStateException.class, () -> absent.read(null));
        assertEquals(0, absent.rowReads.get());
    }

    @Test void aliasCursorRejectsRecreatedBaseOrMvWithMatchingTransactionCounters() throws Exception {
        for (String id : List.of("base_table_id", "view_table_id")) {
            var fixture = new Fixture();
            var cursor = fixture.read(null).nextCursor();
            fixture.materialized.put(id, ((Number) fixture.materialized.get(id)).longValue() + 1);
            assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
            assertEquals(1, fixture.rowReads.get(), "Old physical identities cannot fetch another page");
        }
    }

    @Test void aliasCursorRejectsChangedMvOrBasePhysicalVersionWithUnchangedCheckpoint() throws Exception {
        for (String version : List.of("base_physical_txn", "view_physical_txn")) {
            var fixture = new Fixture();
            var cursor = fixture.read(null).nextCursor();
            fixture.materialized.put(version, ((Number) fixture.materialized.get(version)).longValue() + 1);
            assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
            assertEquals(1, fixture.rowReads.get());
        }
    }

    @Test void mvRefreshDuringAliasReadRejectsTheFetchedPage() throws Exception {
        var fixture = new Fixture();
        fixture.duringRead = () -> fixture.materialized.put("view_physical_txn", 12L);
        assertThrows(IllegalStateException.class, () -> fixture.read(null));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void aliasStatusDirectoryAndExactDependencySqlAreRequiredBeforeReadingRows() throws Exception {
        for (String sql : List.of("SELECT * FROM l2_daily_features", "SELECT trade_date FROM " + BASE,
                SQL + " WHERE avg_retail_ratio > 0", SQL + " JOIN l2_daily_features ON true", "SELECT * FROM (" + BASE + ")")) {
            var fixture = new Fixture();
            fixture.alias.put("view_sql", sql);
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get());
            assertEquals(0, fixture.materializedReads.get());
        }
        for (var change : List.of(Map.entry("view_status", "invalid"),
                Map.entry("view_table_dir_name", ""), Map.entry("invalidation_reason", "base table unavailable"))) {
            var fixture = new Fixture();
            fixture.alias.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get());
        }
        var absent = new Fixture();
        absent.aliasPresent = false;
        assertThrows(IllegalStateException.class, () -> absent.read(null));
        assertEquals(0, absent.rowReads.get());
    }

    @Test void recreatedAliasSqlHashOrStatusHistoryCannotReuseAnOldCursor() throws Exception {
        for (var change : List.of(Map.entry("view_table_dir_name", "v_retail_sentiment_daily~242"),
                Map.entry("view_sql", "select * from \"" + BASE + "\""),
                Map.entry("view_status_update_time", "2026-10-06T01:00:00Z"))) {
            var fixture = new Fixture();
            var cursor = fixture.read(null).nextCursor();
            fixture.alias.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
            assertEquals(1, fixture.rowReads.get(), change.getKey());
        }
    }

    @Test void aliasChangesDuringDependencySnapshotOrRowReadRejectThePage() throws Exception {
        var snapshot = new Fixture();
        snapshot.duringMaterializedMetadata = () -> snapshot.alias.put("view_table_dir_name", "v_retail_sentiment_daily~242");
        assertThrows(IllegalStateException.class, () -> snapshot.read(null));
        assertEquals(0, snapshot.rowReads.get());
        var page = new Fixture();
        page.duringRead = () -> page.alias.put("view_sql", "select * from \"" + BASE + "\"");
        assertThrows(IllegalStateException.class, () -> page.read(null));
        assertEquals(1, page.rowReads.get());
    }

    @Test void datasetDefinitionChangeRejectsAliasCursorBeforeFetchingAnotherPage() throws Exception {
        var fixture = new Fixture();
        var cursor = fixture.read(null).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> fixture.read(definition(2, List.of(BASE)), cursor));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void existingStockBasicLatestWithTableDependencyKeepsCallerVersionAndSkipsMvAliasMetadata() throws Exception {
        var definition = StockBasicDataset.LATEST;
        assertEquals(List.of("stock_basic_snapshot"), definition.dependencies());
        var fixture = new Fixture(definition);
        fixture.aliasPresent = false;
        fixture.materializedPresent = false;
        fixture.returnedRows = List.of(new DatasetValues(Map.of("ts_code", "000001.SZ")));
        var page = fixture.reader.read(definition, new DatasetReadQuery(definition.storageColumns(), Map.of(),
                null, null, null, 1, null), "caller-version", row -> row.get("ts_code", String.class));
        assertEquals(List.of("000001.SZ"), page.rows());
        assertEquals("caller-version", page.sourceVersion());
        assertEquals(0, fixture.aliasReads.get());
        assertEquals(0, fixture.materializedReads.get());
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void registeredAliasRejectsMissingOrWrongDependencyBeforeReadingMetadataOrRows() throws Exception {
        for (var dependencies : List.of(List.<String>of(), List.of("l2_daily_features"))) {
            var fixture = new Fixture();
            assertThrows(IllegalStateException.class, () -> fixture.read(definition(1, dependencies), null));
            assertEquals(0, fixture.aliasReads.get());
            assertEquals(0, fixture.materializedReads.get());
            assertEquals(0, fixture.rowReads.get());
        }
    }

    @Test void registeredAliasRejectsMultipleDependenciesBeforeReadingMetadataOrRows() throws Exception {
        var fixture = new Fixture();
        assertThrows(IllegalStateException.class,
                () -> fixture.read(definition(1, List.of(BASE, "another_mv")), null));
        assertEquals(0, fixture.aliasReads.get());
        assertEquals(0, fixture.rowReads.get());
    }
}
