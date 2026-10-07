package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

import com.zoutrankil.data.domain.*;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

/** DEDUP revisions of the same business cache version must not produce mixed physical snapshots. */
class QuestDbEtfMarketOverviewCacheTableReadGuardTest {
    private static final String CACHE = "etf_market_overview_daily_cache";
    private static final String BUSINESS_VERSION = "a".repeat(64);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final DatasetDefinition DEFINITION = cacheDefinition();

    private static DatasetDefinition cacheDefinition() {
        var date = new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY",
                "Daily ETF overview calendar bucket carried by exact UTC midnight");
        var columns = List.of(
                new Column("trade_date", "trade_date", "trade_date", StorageType.TIMESTAMP, false,
                        "Daily aggregate business date", date),
                new Column("etf_count", "etf_count", "etf_count", StorageType.LONG, false,
                        "Required exact count of distinct matched source instruments", null),
                new Column("total_share", "total_share", "total_share", StorageType.DOUBLE, true,
                        "Nullable summed shares in ten-thousand-share units", null),
                new Column("total_size_yi", "total_size_yi", "total_size_yi", StorageType.DOUBLE, true,
                        "Nullable market size in yi yuan after original SQL conversion", null),
                new Column("source_version", "source_version", "source_version", StorageType.SYMBOL, false,
                        "Complete original Python cache generation", null));
        return new DatasetDefinition(CACHE, 1, "python.cache", "python.publisher", CACHE, ObjectKind.TABLE,
                columns, List.of("trade_date", "source_version"), List.of("trade_date", "source_version"),
                "trade_date", Partition.MONTH, true, Set.of(Capability.READ), List.of(),
                "Original owner publishes DEDUP revisions; historical reads bind actual physical and WAL versions");
    }

    private static Map<String, Object> metadata() {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("table_id", 247L),
                Map.entry("table_directory", CACHE + "~247"), Map.entry("table_physical_txn", 42L),
                Map.entry("table_seq_txn", 7L), Map.entry("table_writer_txn", 7L),
                Map.entry("table_pending_rows", 0L), Map.entry("table_buffered_txns", 0L),
                Map.entry("table_wal_enabled", true), Map.entry("table_suspended", false),
                Map.entry("table_wal_suspended", false)));
    }

    private static DatasetValues row(LocalDate date, long count) { return row(date, BUSINESS_VERSION, count); }
    private static DatasetValues row(LocalDate date, String generation, long count) {
        var values = new LinkedHashMap<String, Object>();
        for (var column : DEFINITION.columns()) {
            switch (column.storageType()) {
                case TIMESTAMP -> values.put(column.storageName(), date);
                case SYMBOL -> values.put(column.storageName(), generation);
                case LONG -> values.put(column.storageName(), count);
                case DOUBLE -> values.put(column.storageName(), (double) count / 10.0);
                default -> throw new IllegalStateException("Unexpected ETF cache field type");
            }
        }
        return new DatasetValues(values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final QuestDbBoundedReader reader = new QuestDbBoundedReader(jdbc);
        final AtomicInteger rowReads = new AtomicInteger();
        final AtomicInteger metadataReads = new AtomicInteger();
        final Map<String, Object> metadata = metadata();
        boolean present = true;
        List<DatasetValues> returnedRows = List.of(row(FROM, 1L), row(FROM.plusDays(1), 2L));
        Runnable duringRead = () -> {};

        Fixture() throws Exception { this(DEFINITION); }
        Fixture(DatasetDefinition definition) throws Exception {
            when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(call -> {
                String sql = call.getArgument(0);
                assertTrue(sql.startsWith("SELECT t.id AS table_id"));
                assertTrue(sql.contains("JOIN wal_tables()"));
                assertTrue(sql.contains("WHERE t.table_name='" + CACHE + "'"));
                assertFalse(sql.contains("source_version"), "Business version is not physical snapshot evidence");
                metadataReads.incrementAndGet();
                var rs = mock(ResultSet.class);
                var wasNull = new AtomicBoolean();
                when(rs.next()).thenReturn(present, false);
                when(rs.getString(anyString())).thenAnswer(get -> (String) metadata.get(get.getArgument(0)));
                when(rs.getBoolean(anyString())).thenAnswer(get -> {
                    Object value = metadata.get(get.getArgument(0));
                    wasNull.set(value == null);
                    return Boolean.TRUE.equals(value);
                });
                when(rs.getLong(anyString())).thenAnswer(get -> {
                    Object value = metadata.get(get.getArgument(0));
                    wasNull.set(value == null);
                    return value == null ? 0L : ((Number) value).longValue();
                });
                when(rs.wasNull()).thenAnswer(get -> wasNull.get());
                return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs);
            });
            var schema = new LinkedHashMap<String, String>();
            definition.columns().forEach(c -> schema.put(c.storageName(), c.storageType().name()));
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

        DatasetReadPage<DatasetValues> read(DatasetReadCursor cursor) { return read(BUSINESS_VERSION, cursor); }
        DatasetReadPage<DatasetValues> read(String version, DatasetReadCursor cursor) {
            return reader.read(DEFINITION, new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("source_version", version),
                    "trade_date", FROM, FROM.plusDays(2), 1, cursor), version, value -> value);
        }
    }

    @Test void stableCacheBindsIndependentPhysicalAndWalIdentityRatherThanBusinessVersion() throws Exception {
        var fixture = new Fixture();
        var page = fixture.read(null);
        assertEquals("table:" + CACHE + ":id:247:directory:" + CACHE + "~247:txn:42:wal-seq:7", page.sourceVersion());
        assertEquals(page.sourceVersion(), page.nextCursor().sourceVersion());
        assertEquals(BUSINESS_VERSION, page.rows().getFirst().get("source_version", String.class));
        assertEquals(1L, page.rows().getFirst().get("etf_count", Long.class).longValue());
        assertEquals(5, page.rows().getFirst().columns().size());
        assertEquals(1, DEFINITION.columns().stream().filter(c -> c.storageType() == StorageType.LONG).count());
        assertEquals(2, DEFINITION.columns().stream().filter(c -> c.storageType() == StorageType.DOUBLE).count());
        assertEquals(List.of(FROM, BUSINESS_VERSION), page.nextCursor().keyValues());
        assertEquals(2, fixture.metadataReads.get());
        assertEquals(1, fixture.rowReads.get());
        verify(fixture.jdbc, never()).execute(anyString());
    }

    @Test void rangePaginationRetainsBothDateAndGenerationWhenTwoVersionsShareADate() throws Exception {
        var fixture = new Fixture();
        String newerGeneration = "b".repeat(64);
        fixture.returnedRows = List.of(row(FROM, BUSINESS_VERSION, 1L), row(FROM, newerGeneration, 2L));
        var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM, FROM.plusDays(2), 1, null);
        var first = fixture.reader.read(DEFINITION, query, BUSINESS_VERSION, value -> value);
        assertEquals(List.of(FROM, BUSINESS_VERSION), first.nextCursor().keyValues());
        var prepared = fixture.reader.prepare(DEFINITION, query.after(first.nextCursor()), first.sourceVersion());
        assertTrue(prepared.sql().contains("ORDER BY \"trade_date\" ASC, \"source_version\" ASC"));
        fixture.returnedRows = List.of(row(FROM, newerGeneration, 2L));
        var second = fixture.reader.read(DEFINITION, query.after(first.nextCursor()), BUSINESS_VERSION, value -> value);
        assertEquals(first.sourceVersion(), second.sourceVersion());
        assertEquals(newerGeneration, second.rows().getFirst().get("source_version", String.class));
        assertFalse(second.hasMore());
        assertEquals(2, fixture.rowReads.get());
    }

    @Test void sameBusinessVersionDedupRevisionBetweenPagesRejectsTheCursorBeforeAnotherSelect() throws Exception {
        var fixture = new Fixture();
        var cursor = fixture.read(null).nextCursor();
        fixture.metadata.put("table_physical_txn", 43L);
        fixture.metadata.put("table_seq_txn", 8L);
        fixture.metadata.put("table_writer_txn", 8L);
        fixture.returnedRows = List.of(row(FROM, 99L), row(FROM.plusDays(1), 2L));
        assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
        assertEquals(1, fixture.rowReads.get(), "Unchanged source_version must not reuse a changed physical cache");
    }

    @Test void physicalCommitOrWalFrontierChangeIndependentlyInvalidatesAnOldCursor() throws Exception {
        for (String version : List.of("table_physical_txn", "table_seq_txn")) {
            var fixture = new Fixture();
            var cursor = fixture.read(null).nextCursor();
            fixture.metadata.put(version, ((Number) fixture.metadata.get(version)).longValue() + 1);
            if (version.equals("table_seq_txn")) fixture.metadata.put("table_writer_txn", 8L);
            assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
            assertEquals(1, fixture.rowReads.get());
        }
    }

    @Test void tableIdentityOrDirectoryRecreationCannotReuseMatchingTransactionCounters() throws Exception {
        for (var change : List.of(Map.entry("table_id", (Object) 248L), Map.entry("table_directory", (Object) (CACHE + "~248")))) {
            var fixture = new Fixture();
            var cursor = fixture.read(null).nextCursor();
            fixture.metadata.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> fixture.read(cursor));
            assertEquals(1, fixture.rowReads.get());
        }
    }

    @Test void dedupRevisionDuringActualReadRejectsTheFetchedPage() throws Exception {
        var fixture = new Fixture();
        fixture.duringRead = () -> {
            fixture.metadata.put("table_physical_txn", 43L);
            fixture.metadata.put("table_seq_txn", 8L);
            fixture.metadata.put("table_writer_txn", 8L);
            fixture.returnedRows = List.of(row(FROM, 99L), row(FROM.plusDays(1), 2L));
        };
        assertThrows(IllegalStateException.class, () -> fixture.read(null));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void unsettledSuspendedOrNonWalCacheFailsBeforeFetchingRows() throws Exception {
        for (var change : List.of(Map.entry("table_writer_txn", (Object) 6L),
                Map.entry("table_pending_rows", (Object) 1L), Map.entry("table_buffered_txns", (Object) 1L),
                Map.entry("table_suspended", (Object) true), Map.entry("table_wal_suspended", (Object) true),
                Map.entry("table_wal_enabled", (Object) false))) {
            var fixture = new Fixture();
            fixture.metadata.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get(), change.getKey());
        }
    }

    @Test void absentOrIncompletePhysicalMetadataCannotBecomeAnEmptyOrReadableCache() throws Exception {
        var absent = new Fixture();
        absent.present = false;
        assertThrows(IllegalStateException.class, () -> absent.read(null));
        assertEquals(0, absent.rowReads.get());
        for (String field : List.of("table_id", "table_directory", "table_physical_txn", "table_seq_txn",
                "table_writer_txn", "table_pending_rows", "table_buffered_txns", "table_wal_enabled",
                "table_suspended", "table_wal_suspended")) {
            var fixture = new Fixture();
            fixture.metadata.remove(field);
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get(), field);
        }
    }

    @Test void changedBusinessVersionFilterCannotReuseCursorEvenWhenPhysicalVersionIsUnchanged() throws Exception {
        var fixture = new Fixture();
        var cursor = fixture.read(null).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> fixture.read("b".repeat(64), cursor));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void invalidBusinessVersionEqualityFailsBeforeAnyMetadataSchemaOrRowQuery() throws Exception {
        for (Object invalid : new Object[]{"bad-version", "A".repeat(64), "a".repeat(63), "g".repeat(64), "a".repeat(65), 42L, null}) {
            var fixture = new Fixture();
            var equalities = new LinkedHashMap<String,Object>();
            equalities.put("source_version", invalid);
            var query = new DatasetReadQuery(DEFINITION.storageColumns(), equalities,
                    "trade_date", FROM, FROM.plusDays(2), 1, null);
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.reader.read(DEFINITION, query, null, value -> value));
            assertEquals(0, fixture.metadataReads.get());
            assertEquals(0, fixture.rowReads.get());
            verifyNoInteractions(fixture.jdbc);
        }
    }

    @Test void malformedCursorGenerationOrIncompleteKeyFailsDuringSqlPreparationWithoutDatabaseReads() throws Exception {
        var fixture = new Fixture();
        var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("source_version", BUSINESS_VERSION),
                "trade_date", FROM, FROM.plusDays(2), 1, null);
        String physicalVersion = "cache-physical-snapshot";
        String fingerprint = fixture.reader.prepare(DEFINITION, query, physicalVersion).fingerprint();
        for (List<Object> invalid : List.of(List.<Object>of(), List.<Object>of(FROM),
                List.<Object>of(FROM, BUSINESS_VERSION, "extra"), List.<Object>of(FROM, "bad-version"),
                List.<Object>of(FROM, "A".repeat(64)), List.<Object>of(FROM, 42L))) {
            var cursor = new DatasetReadCursor(fingerprint, invalid, physicalVersion);
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.reader.prepare(DEFINITION, query.after(cursor), physicalVersion));
        }
        assertEquals(0, fixture.metadataReads.get());
        assertEquals(0, fixture.rowReads.get());
        verifyNoInteractions(fixture.jdbc);
    }

    @Test void cacheThatBecomesUnsettledDuringReadCannotReturnAStablePage() throws Exception {
        var fixture = new Fixture();
        fixture.duringRead = () -> fixture.metadata.put("table_buffered_txns", 1L);
        assertThrows(IllegalStateException.class, () -> fixture.read(null));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void existingUnregisteredStockBasicTableKeepsItsCallerVersionWithoutCacheMetadata() throws Exception {
        var definition = StockBasicDataset.DEFINITION;
        var fixture = new Fixture(definition);
        fixture.present = false;
        fixture.returnedRows = List.of(new DatasetValues(Map.of("snapshot_ts", Instant.parse("2026-09-17T00:00:00Z"),
                "ts_code", "000001.SZ")));
        var page = fixture.reader.read(definition, new DatasetReadQuery(definition.storageColumns(), Map.of(),
                null, null, null, 1, null), "caller-version", value -> value);
        assertEquals("caller-version", page.sourceVersion());
        assertEquals(1, page.rows().size());
        assertEquals(0, fixture.metadataReads.get());
        assertEquals(1, fixture.rowReads.get());
    }
}
