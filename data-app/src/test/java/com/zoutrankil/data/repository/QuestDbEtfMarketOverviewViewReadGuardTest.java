package com.zoutrankil.data.repository;

import static com.zoutrankil.data.domain.DatasetDefinition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

/** D102 direct JOIN VIEW: ordinary view validity alone cannot prove either physical source snapshot. */
class QuestDbEtfMarketOverviewViewReadGuardTest {
    private static final String VIEW = "v_etf_market_overview_daily";
    private static final List<String> SOURCES = List.of("etf_share", "etf_daily");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 17);
    private static final String SQL = """
            SELECT
                s.timestamp AS trade_date,
                count_distinct(s.ts_code) AS etf_count,
                sum(s.fd_share) AS total_share,
                sum(s.fd_share * d.close) / 10000.0 AS total_size_yi
            FROM etf_share s
            JOIN etf_daily d ON s.ts_code = d.ts_code AND s.timestamp = d.timestamp
            SAMPLE BY 1d ALIGN TO CALENDAR
            """.trim();
    private static final DatasetDefinition DEFINITION = definition(1, VIEW, ObjectKind.VIEW, SOURCES);

    private static DatasetDefinition definition(int version, String object, ObjectKind kind, List<String> dependencies) {
        var date = new TemporalContract(TemporalKind.BUSINESS_DATE, "BASIC", "calendar", "DAY", "UTC-midnight daily bucket");
        return new DatasetDefinition(VIEW, version, "questdb.view", "python.market_barometer_views.install_views", object,
                kind, List.of(new Column("s.timestamp", "trade_date", "trade_date", StorageType.TIMESTAMP, false, "Daily bucket", date),
                new Column("count_distinct(s.ts_code)", "etf_count", "etf_count", StorageType.LONG, false, "Distinct inner-joined funds", null),
                new Column("sum(s.fd_share)", "total_share", "total_share", StorageType.DOUBLE, true, "Ten-thousand shares", null),
                new Column("sum(s.fd_share*d.close)/10000.0", "total_size_yi", "total_size_yi", StorageType.DOUBLE, true, "Yi yuan", null)),
                List.of("trade_date"), List.of(), null, Partition.NONE, false, Set.of(Capability.READ), dependencies,
                "Exact existing aggregate of original two WAL sources; no direct writer, cache, basic or native MV dependency");
    }

    private static Map<String, Object> view() {
        var values = new LinkedHashMap<String, Object>();
        values.put("view_sql", SQL);
        values.put("view_table_dir_name", VIEW + "~239");
        values.put("view_status", "valid");
        values.put("invalidation_reason", null);
        values.put("view_status_update_time", "2026-09-30T01:27:52.510561Z");
        return values;
    }

    private static Map<String, Object> source(String table) {
        boolean share = table.equals("etf_share");
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("table_id", share ? 9L : 10L),
                Map.entry("table_directory", table + (share ? "~9" : "~10")),
                Map.entry("table_physical_txn", share ? 42L : 55L),
                Map.entry("table_seq_txn", share ? 6L : 14L), Map.entry("table_writer_txn", share ? 6L : 14L),
                Map.entry("table_pending_rows", 0L), Map.entry("table_buffered_txns", 0L),
                Map.entry("table_wal_enabled", true), Map.entry("table_suspended", false),
                Map.entry("table_wal_suspended", false), Map.entry("table_partition", "YEAR"),
                Map.entry("table_timestamp", "timestamp"), Map.entry("table_dedup", true),
                Map.entry("table_is_materialized", false)));
    }

    private static List<Map<String, Object>> schema(String table) {
        var definition = table.equals("etf_share") ? EtfShareDataset.DEFINITION : EtfDailyDataset.DEFINITION;
        var result = new ArrayList<Map<String, Object>>();
        for (var column : definition.columns()) result.add(new LinkedHashMap<>(Map.of(
                "column", column.storageName(), "type", column.storageType().name(),
                "designated", column.storageName().equals("timestamp"),
                "upsertKey", Set.of("ts_code", "timestamp").contains(column.storageName()))));
        return result;
    }

    private static DatasetValues row(LocalDate date) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", date);
        values.put("etf_count", 765L);
        values.put("total_share", -0.0);
        values.put("total_size_yi", null);
        return new DatasetValues(values);
    }

    private static DatasetReadQuery query(DatasetReadCursor cursor) {
        return new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM, FROM.plusDays(5), 1, cursor);
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) throws Exception {
        var rs = mock(ResultSet.class);
        var index = new AtomicInteger(-1);
        var wasNull = new AtomicBoolean();
        when(rs.next()).thenAnswer(call -> index.incrementAndGet() < rows.size());
        when(rs.getString(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null); return (String) value;
        });
        when(rs.getBoolean(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null); return Boolean.TRUE.equals(value);
        });
        when(rs.getLong(anyString())).thenAnswer(call -> {
            Object value = rows.get(index.get()).get(call.getArgument(0));
            wasNull.set(value == null); return value == null ? 0L : ((Number) value).longValue();
        });
        when(rs.wasNull()).thenAnswer(call -> wasNull.get());
        return rs;
    }

    private record MetadataStatement(String sql, PreparedStatement statement, int rowLimit) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final QuestDbBoundedReader reader = new QuestDbBoundedReader(jdbc);
        final Map<String, Object> view = view();
        final Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> schemas = new LinkedHashMap<>();
        final Set<String> absentSources = new HashSet<>();
        final Set<String> duplicateSources = new HashSet<>();
        final AtomicInteger viewReads = new AtomicInteger();
        final AtomicInteger sourceReads = new AtomicInteger();
        final AtomicInteger schemaReads = new AtomicInteger();
        final AtomicInteger rowReads = new AtomicInteger();
        final List<MetadataStatement> metadataStatements = new ArrayList<>();
        boolean viewPresent = true;
        boolean duplicateView;
        Runnable duringRead = () -> {};
        Consumer<String> duringSourceMetadata = table -> {};
        List<DatasetValues> rows = List.of(row(FROM), row(FROM.plusDays(1)));

        Fixture() throws Exception { this(DEFINITION); }
        Fixture(DatasetDefinition definition) throws Exception {
            for (String table : SOURCES) { sources.put(table, QuestDbEtfMarketOverviewViewReadGuardTest.source(table)); schemas.put(table, schema(table)); }
            var outputTypes = new LinkedHashMap<String, String>();
            definition.columns().forEach(c -> outputTypes.put(c.storageName(), c.storageType().name()));
            when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(call -> {
                throw new AssertionError("D102 metadata must use a prepared statement with an explicit deadline and row cap");
            });
            when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                var connection = mock(Connection.class);
                var statement = mock(PreparedStatement.class);
                var capturedSql = new String[1];
                when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                    capturedSql[0] = prepare.getArgument(0); return statement;
                });
                assertSame(statement, ((PreparedStatementCreator) call.getArgument(0)).createPreparedStatement(connection));
                String sql = capturedSql[0];
                assertNotNull(sql);
                if (sql.endsWith(" LIMIT 4097")) {
                    verify(statement).setQueryTimeout(20); verify(statement).setMaxRows(4097);
                    return outputTypes; // Existing declared output-schema verification keeps its original finite contract.
                }
                assertFalse(sql.contains("materialized_views()"), "This view has no native MV dependency");
                assertFalse(sql.contains("etf_basic"), "The original direct JOIN never uses the cache's basic dependency");
                assertFalse(sql.contains("coverage"));
                assertFalse(sql.contains("daily_cache"));
                List<Map<String, Object>> metadata;
                String sourceTable = null;
                int limit;
                if (sql.contains("FROM views()")) {
                    assertTrue(sql.contains("WHERE view_name='" + VIEW + "'"));
                    limit = 2;
                    viewReads.incrementAndGet();
                    metadata = !viewPresent ? List.of() : duplicateView ? List.of(view, view) : List.of(view);
                } else if (sql.contains("FROM table_columns(")) {
                    assertTrue(sql.startsWith("SELECT \"column\",\"type\",designated,upsertKey"));
                    assertTrue(sql.endsWith(" LIMIT 18"));
                    limit = 18;
                    schemaReads.incrementAndGet();
                    sourceTable = SOURCES.stream().filter(t -> sql.contains("table_columns('" + t + "')")).findFirst().orElseThrow();
                    metadata = schemas.get(sourceTable);
                } else {
                    assertTrue(sql.contains("JOIN wal_tables()"));
                    limit = 2;
                    sourceReads.incrementAndGet();
                    sourceTable = SOURCES.stream().filter(t -> sql.contains("WHERE t.table_name='" + t + "'")).findFirst().orElseThrow();
                    metadata = absentSources.contains(sourceTable) ? List.of()
                            : duplicateSources.contains(sourceTable) ? List.of(sources.get(sourceTable), sources.get(sourceTable))
                            : List.of(sources.get(sourceTable));
                }
                verify(statement).setQueryTimeout(20); verify(statement).setMaxRows(limit); verify(statement).setFetchSize(limit);
                metadataStatements.add(new MetadataStatement(sql, statement, limit));
                Object result = ((ResultSetExtractor<?>) call.getArgument(1)).extractData(resultSet(metadata));
                if (sourceTable != null) duringSourceMetadata.accept(sourceTable);
                return result;
            });
            when(jdbc.query(any(PreparedStatementCreator.class),
                    org.mockito.ArgumentMatchers.<RowMapper<DatasetValues>>any())).thenAnswer(call -> {
                rowReads.incrementAndGet(); duringRead.run(); return rows;
            });
        }

        DatasetReadPage<DatasetValues> read(DatasetReadCursor cursor) { return read(DEFINITION, query(cursor)); }
        DatasetReadPage<DatasetValues> read(DatasetDefinition definition, DatasetReadQuery query) {
            return reader.read(definition, query, "caller-version-is-not-a-source-snapshot", value -> value);
        }
        Map<String, Object> source(String table) { return sources.get(table); }
        Map<String, Object> column(String table, String name) {
            return schemas.get(table).stream().filter(c -> name.equals(c.get("column"))).findFirst().orElseThrow();
        }
    }

    @Test void stableDirectViewBindsItsIdentityAndIndependentPhysicalVersionsOfBothSources() throws Exception {
        var f = new Fixture(); var page = f.read(null);
        String sqlSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(SQL.getBytes(StandardCharsets.UTF_8)));
        assertEquals("view-dir:" + VIEW + "~239:view-sql:" + sqlSha
                + ":view-status-updated:2026-09-30T01:27:52.510561Z"
                + ":source:etf_share:id:9:directory:etf_share~9:txn:42:wal-seq:6"
                + ":source:etf_daily:id:10:directory:etf_daily~10:txn:55:wal-seq:14", page.sourceVersion());
        assertEquals(page.sourceVersion(), page.nextCursor().sourceVersion());
        assertEquals(List.of(FROM), page.nextCursor().keyValues());
        assertEquals(765L, page.rows().getFirst().get("etf_count", Long.class).longValue());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(page.rows().getFirst().get("total_share", Double.class)));
        assertNull(page.rows().getFirst().get("total_size_yi", Double.class));
        assertEquals(4, f.viewReads.get()); assertEquals(4, f.sourceReads.get()); assertEquals(4, f.schemaReads.get());
        assertEquals(1, f.rowReads.get()); verify(f.jdbc, never()).execute(anyString());
    }

    @Test void everyD102MetadataStatementSetsItsOwnDeadlineAndFiniteRowBudget() throws Exception {
        var f = new Fixture(); f.read(null);
        assertEquals(12, f.metadataStatements.size(), "Both before/after guards each verify view twice and both source states/schemas");
        assertEquals(8, f.metadataStatements.stream().filter(s -> s.rowLimit() == 2).count());
        assertEquals(4, f.metadataStatements.stream().filter(s -> s.rowLimit() == 18).count());
        for (var metadata : f.metadataStatements) {
            verify(metadata.statement()).setQueryTimeout(20);
            verify(metadata.statement()).setMaxRows(metadata.rowLimit());
            verify(metadata.statement()).setFetchSize(metadata.rowLimit());
        }
        verify(f.jdbc, never()).query(anyString(), any(ResultSetExtractor.class));
    }

    @Test void ordinaryViewAcceptsOnlyCaseWhitespaceAndKnownIdentifierQuoteVariation() throws Exception {
        for (String variant : List.of(SQL, SQL.toLowerCase(Locale.ROOT).replace("s.timestamp", "\"s\" . \"timestamp\"")
                .replace("s.ts_code", "\"s\" . \"ts_code\"").replace("etf_share", "\"etf_share\"")
                .replace("etf_daily", "\"etf_daily\"").replace(" * ", "*").replace(" / ", "/"))) {
            var f = new Fixture(); f.view.put("view_sql", variant);
            assertEquals(1, f.read(null).rows().size()); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void changedJoinKeysFormulasSourcesSamplingOrExtraFiltersFailOnTheFirstPage() throws Exception {
        for (String variant : List.of(SQL.replace("AND s.timestamp = d.timestamp", ""),
                SQL.replace("s.ts_code = d.ts_code", "s.ts_code != d.ts_code"),
                SQL.replace("sum(s.fd_share)", "avg(s.fd_share)"), SQL.replace("10000.0", "100000000.0"),
                SQL.replace("etf_daily", "etf_basic"), SQL.replace("SAMPLE BY 1d", "SAMPLE BY 1h"),
                SQL.replace("SAMPLE BY", "WHERE d.close > 0 SAMPLE BY"), SQL + " LIMIT 1",
                SQL + "; SELECT * FROM etf_basic", SQL + " -- retained comment", "(" + SQL + ")",
                SQL.replace("FROM etf_share s", "FROM (etf_share) s"), SQL.replace("SELECT", "\"SELECT\""))) {
            var f = new Fixture(); f.view.put("view_sql", variant);
            assertThrows(IllegalStateException.class, () -> f.read(null));
            assertEquals(0, f.rowReads.get()); assertEquals(0, f.sourceReads.get());
        }
    }

    @Test void viewMustBePresentUniqueValidWithDirectoryStatusHistoryAndNoInvalidation() throws Exception {
        for (String field : List.of("view_sql", "view_table_dir_name", "view_status", "view_status_update_time")) {
            var f = new Fixture(); f.view.remove(field); assertThrows(IllegalStateException.class, () -> f.read(null));
            assertEquals(0, f.rowReads.get());
        }
        for (var change : List.of(Map.entry("view_status", "invalid"), Map.entry("view_table_dir_name", ""),
                Map.entry("view_status_update_time", ""), Map.entry("invalidation_reason", "source missing"))) {
            var f = new Fixture(); f.view.put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get());
        }
        var absent = new Fixture(); absent.viewPresent = false;
        assertThrows(IllegalStateException.class, () -> absent.read(null)); assertEquals(0, absent.rowReads.get());
        var duplicate = new Fixture(); duplicate.duplicateView = true;
        assertThrows(IllegalStateException.class, () -> duplicate.read(null)); assertEquals(0, duplicate.rowReads.get());
    }

    @Test void registeredViewRequiresExactlyItsOrderedTwoDependenciesBeforeAnyJdbcCall() throws Exception {
        for (var dependencies : List.of(List.<String>of(), List.of("etf_share"), List.of("etf_daily", "etf_share"),
                List.of("etf_share", "etf_basic"), List.of("etf_share", "etf_daily", "etf_basic"))) {
            var f = new Fixture(); var wrong = definition(1, VIEW, ObjectKind.VIEW, dependencies);
            assertThrows(IllegalStateException.class, () -> f.read(wrong, query(null))); verifyNoInteractions(f.jdbc);
        }
    }

    @Test void registeredNameCannotBypassViewGuardsByClaimingATableOrMaterializedView() throws Exception {
        for (ObjectKind kind : List.of(ObjectKind.TABLE, ObjectKind.MATERIALIZED_VIEW)) {
            var f = new Fixture(); var wrong = definition(1, VIEW, kind, SOURCES);
            assertThrows(IllegalStateException.class, () -> f.read(wrong, query(null))); verifyNoInteractions(f.jdbc);
        }
    }

    @Test void eitherSourceUnsettledSuspendedOrWrongPhysicalContractFailsBeforeRowSelect() throws Exception {
        for (String table : SOURCES) for (var change : List.of(Map.entry("table_writer_txn", (Object) 1L),
                Map.entry("table_pending_rows", (Object) 1L), Map.entry("table_buffered_txns", (Object) 1L),
                Map.entry("table_suspended", (Object) true), Map.entry("table_wal_suspended", (Object) true),
                Map.entry("table_wal_enabled", (Object) false), Map.entry("table_dedup", (Object) false),
                Map.entry("table_is_materialized", (Object) true), Map.entry("table_partition", (Object) "DAY"),
                Map.entry("table_timestamp", (Object) "update_time"))) {
            var f = new Fixture(); f.source(table).put(change.getKey(), change.getValue());
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get(), table + change);
        }
    }

    @Test void bothSourceMetadataRowsAndAllCountersAndBooleanStatusesAreRequired() throws Exception {
        for (String table : SOURCES) {
            var absent = new Fixture(); absent.absentSources.add(table);
            assertThrows(IllegalStateException.class, () -> absent.read(null)); assertEquals(0, absent.rowReads.get());
            var duplicate = new Fixture(); duplicate.duplicateSources.add(table);
            assertThrows(IllegalStateException.class, () -> duplicate.read(null)); assertEquals(0, duplicate.rowReads.get());
            for (String field : List.of("table_id", "table_directory", "table_physical_txn", "table_seq_txn", "table_writer_txn",
                    "table_pending_rows", "table_buffered_txns", "table_wal_enabled", "table_suspended", "table_wal_suspended",
                    "table_dedup", "table_is_materialized", "table_partition", "table_timestamp")) {
                var f = new Fixture(); f.source(table).remove(field);
                assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get(), table + field);
            }
            var negative = new Fixture(); negative.source(table).put("table_physical_txn", -1L);
            assertThrows(IllegalStateException.class, () -> negative.read(null)); assertEquals(0, negative.rowReads.get());
        }
    }

    @Test void exactFullSourceSchemaAndOriginalMicroNanoTimestampUnitsAreRequired() throws Exception {
        for (String table : SOURCES) {
            var f = new Fixture(); f.column(table, "timestamp").put("type", table.equals("etf_share") ? "TIMESTAMP_NS" : "TIMESTAMP");
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get());
            var wrongType = new Fixture(); wrongType.column(table, table.equals("etf_share") ? "fd_share" : "close").put("type", "FLOAT");
            assertThrows(IllegalStateException.class, () -> wrongType.read(null)); assertEquals(0, wrongType.rowReads.get());
            var missing = new Fixture(); missing.schemas.get(table).removeLast();
            assertThrows(IllegalStateException.class, () -> missing.read(null)); assertEquals(0, missing.rowReads.get());
            var extra = new Fixture(); extra.schemas.get(table).add(new LinkedHashMap<>(Map.of("column", "extra", "type", "DOUBLE", "designated", false, "upsertKey", false)));
            assertThrows(IllegalStateException.class, () -> extra.read(null)); assertEquals(0, extra.rowReads.get());
            var duplicate = new Fixture(); duplicate.schemas.get(table).add(duplicate.schemas.get(table).getFirst());
            assertThrows(IllegalStateException.class, () -> duplicate.read(null)); assertEquals(0, duplicate.rowReads.get());
            var reordered = new Fixture(); Collections.swap(reordered.schemas.get(table), 0, 1);
            assertThrows(IllegalStateException.class, () -> reordered.read(null)); assertEquals(0, reordered.rowReads.get());
        }
    }

    @Test void sourceDesignatedTimestampAndBothPhysicalDedupKeysCannotBeOmittedOrInvented() throws Exception {
        for (String table : SOURCES) for (var change : List.of(Map.entry("timestamp:designated", (Object) false),
                Map.entry("ts_code:upsertKey", (Object) false), Map.entry("timestamp:upsertKey", (Object) false),
                Map.entry("ts_code:designated", (Object) true), Map.entry("timestamp:designated", (Object) "missing"))) {
            var f = new Fixture(); var key = change.getKey().split(":");
            if (change.getValue().equals("missing")) f.column(table, key[0]).remove(key[1]);
            else f.column(table, key[0]).put(key[1], change.getValue());
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get());
        }
    }

    @Test void anySourcePhysicalCommitOrWalRevisionRejectsOldCursorBeforeAnotherRowQuery() throws Exception {
        for (String table : SOURCES) for (String field : List.of("table_physical_txn", "table_seq_txn")) {
            var f = new Fixture(); var cursor = f.read(null).nextCursor();
            long next = ((Number) f.source(table).get(field)).longValue() + 1; f.source(table).put(field, next);
            if (field.equals("table_seq_txn")) f.source(table).put("table_writer_txn", next);
            assertThrows(IllegalArgumentException.class, () -> f.read(cursor)); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void recreatedSourceIdOrDirectoryCannotReuseEqualTransactions() throws Exception {
        for (String table : SOURCES) for (var change : List.of(Map.entry("table_id", (Object) 200L), Map.entry("table_directory", (Object) (table + "~200")))) {
            var f = new Fixture(); var cursor = f.read(null).nextCursor(); f.source(table).put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> f.read(cursor)); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void changedViewIdentityDefinitionHashOrStatusHistoryRejectsOldCursor() throws Exception {
        for (var change : List.of(Map.entry("view_table_dir_name", VIEW + "~240"),
                Map.entry("view_sql", SQL.toLowerCase(Locale.ROOT)), Map.entry("view_status_update_time", "2026-10-06T12:00:00Z"))) {
            var f = new Fixture(); var cursor = f.read(null).nextCursor(); f.view.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> f.read(cursor)); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void viewDefinitionChangeDuringSourceMetadataFailsBeforeRowSelect() throws Exception {
        var f = new Fixture(); f.duringSourceMetadata = table -> f.view.put("view_table_dir_name", VIEW + "~240");
        assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(0, f.rowReads.get());
    }

    @Test void sourceRevisionDuringBoundedSelectCannotReturnAMixedPage() throws Exception {
        for (String table : SOURCES) {
            var f = new Fixture(); f.duringRead = () -> f.source(table).put("table_physical_txn", 999L);
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void viewOrSourceThatBecomesInvalidAfterSelectCannotReturnAPage() throws Exception {
        var view = new Fixture(); view.duringRead = () -> view.view.put("view_status", "invalid");
        assertThrows(IllegalStateException.class, () -> view.read(null)); assertEquals(1, view.rowReads.get());
        for (String table : SOURCES) {
            var f = new Fixture(); f.duringRead = () -> f.source(table).put("table_buffered_txns", 1L);
            assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(1, f.rowReads.get());
        }
    }

    @Test void fullSourceSchemaIsRecheckedAfterBoundedSelect() throws Exception {
        var f = new Fixture(); f.duringRead = () -> f.column("etf_daily", "close").put("type", "FLOAT");
        assertThrows(IllegalStateException.class, () -> f.read(null)); assertEquals(1, f.rowReads.get());
    }

    @Test void datasetSchemaChangeRejectsCursorBeforeFetchingAnotherPage() throws Exception {
        var f = new Fixture(); var cursor = f.read(null).nextCursor();
        assertThrows(IllegalArgumentException.class, () -> f.read(definition(2, VIEW, ObjectKind.VIEW, SOURCES), query(cursor)));
        assertEquals(1, f.rowReads.get());
    }

    @Test void exactDateEqualityIsBoundAndUsesTheOriginalTimestampOutputUnit() throws Exception {
        var f = new Fixture(); var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("trade_date", FROM), null, null, null, 1, null);
        var prepared = f.reader.prepare(DEFINITION, query, "snapshot");
        assertTrue(prepared.sql().contains("\"trade_date\" = cast(? as TIMESTAMP)"));
        assertEquals(FROM.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).getEpochSecond() * 1_000_000L, prepared.parameters().getFirst().storageValue());
        assertEquals(1, f.read(DEFINITION, query).rows().size());
    }

    @Test void rangeOfExactly31DaysAnd31BucketsIsPermittedWithFiniteOutputLimit() throws Exception {
        var f = new Fixture(); var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM, FROM.plusDays(31), 31, null);
        var prepared = f.reader.prepare(DEFINITION, query, "snapshot");
        assertTrue(prepared.sql().contains("\"trade_date\" >= cast(? as TIMESTAMP)"));
        assertTrue(prepared.sql().contains("\"trade_date\" < cast(? as TIMESTAMP)"));
        assertTrue(prepared.sql().endsWith(" LIMIT 32"));
        f.read(DEFINITION, query); assertEquals(1, f.rowReads.get());
    }

    @Test void unboundedOversizedWrongDateTypesAndPartialProjectionFailBeforeAnyJdbcCall() throws Exception {
        var invalid = new ArrayList<DatasetReadQuery>();
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), null, null, null, 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("etf_count", 765L), null, null, null, 1, null));
        for (Object value : List.of("2026-09-17", Instant.parse("2026-09-17T00:00:00Z"), 20260917L))
            invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("trade_date", value), null, null, null, 1, null));
        var nullDate = new LinkedHashMap<String, Object>(); nullDate.put("trade_date", null);
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), nullDate, null, null, null, 1, null));
        for (int size : List.of(32, 10000)) invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("trade_date", FROM), null, null, null, size, null));
        invalid.add(new DatasetReadQuery(List.of("trade_date", "etf_count"), Map.of("trade_date", FROM), null, null, null, 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "total_share", FROM, FROM.plusDays(1), 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM, FROM, 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM.plusDays(1), FROM, 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", FROM, FROM.plusDays(32), 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", "2026-09-17", "2026-09-18", 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date", Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-18T00:00:00Z"), 1, null));
        invalid.add(new DatasetReadQuery(DEFINITION.storageColumns(), Map.of("trade_date", FROM), "total_share", FROM, FROM.plusDays(1), 1, null));
        for (var query : invalid) {
            var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.prepare(DEFINITION, query, null));
            assertThrows(IllegalArgumentException.class, () -> f.read(DEFINITION, query)); verifyNoInteractions(f.jdbc);
        }
    }

    @Test void unregisteredViewsKeepCallerVersionWithoutEtfOrMvMetadata() throws Exception {
        var definition = definition(1, "another_view", ObjectKind.VIEW, SOURCES); var f = new Fixture(definition);
        var page = f.read(definition, new DatasetReadQuery(definition.storageColumns(), Map.of(), null, null, null, 1, null));
        assertEquals("caller-version-is-not-a-source-snapshot", page.sourceVersion());
        assertEquals(0, f.viewReads.get()); assertEquals(0, f.sourceReads.get()); assertEquals(0, f.schemaReads.get());
        assertEquals(1, f.rowReads.get());
    }

    @Test void existingStockBasicLatestWithTableDependencyRemainsUnchanged() throws Exception {
        var definition = StockBasicDataset.LATEST; var f = new Fixture(definition);
        f.rows = List.of(new DatasetValues(Map.of("ts_code", "000001.SZ")));
        var page = f.reader.read(definition, new DatasetReadQuery(definition.storageColumns(), Map.of(), null, null, null, 1, null),
                "caller-version", values -> values.get("ts_code", String.class));
        assertEquals(List.of("000001.SZ"), page.rows()); assertEquals("caller-version", page.sourceVersion());
        assertEquals(0, f.viewReads.get()); assertEquals(0, f.sourceReads.get()); assertEquals(0, f.schemaReads.get());
        verify(f.jdbc, never()).execute(anyString());
    }
}
