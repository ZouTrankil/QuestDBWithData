package com.zoutrankil.data.repository;

import com.zoutrankil.data.derived.storage.MarketBreadthDailyV1MaterializationPort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.data.domain.*;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

/** A refreshed MV may have new physical values while its base checkpoint is unchanged. */
class QuestDbMaterializedViewReadGuardTest {
    private static final DatasetDefinition DEFINITION = MarketBreadthDailyV1Dataset.DEFINITION;

    private record State(String status, long baseId, long baseSeqTxn, long refreshTxn, long viewId,
                         long viewSeqTxn, long basePhysicalTxn, long viewPhysicalTxn) {
        static State current(long baseId, long baseSeqTxn, long viewId, long viewTxn) {
            return new State("valid", baseId, baseSeqTxn, baseSeqTxn, viewId, viewTxn, baseSeqTxn, viewTxn);
        }

        State physical(long baseTxn, long viewTxn) {
            return new State(status, baseId, baseSeqTxn, refreshTxn, viewId, viewSeqTxn, baseTxn, viewTxn);
        }

        Map<String, Object> columns() {
            return Map.ofEntries(Map.entry("view_status", status), Map.entry("base_table_id", baseId),
                    Map.entry("base_table_name", MarketBreadthDailyV1MaterializationPort.SOURCE),
                    Map.entry("view_sql", MarketBreadthDailyV1MaterializationPort.DEFINITION_SQL),
                    Map.entry("base_table_txn", baseSeqTxn), Map.entry("refresh_base_table_txn", refreshTxn),
                    Map.entry("base_physical_txn", basePhysicalTxn), Map.entry("base_writer_txn", baseSeqTxn),
                    Map.entry("base_seq_txn", baseSeqTxn), Map.entry("base_buffered_txns", 0L),
                    Map.entry("view_table_id", viewId), Map.entry("view_physical_txn", viewPhysicalTxn),
                    Map.entry("view_writer_txn", viewSeqTxn), Map.entry("view_seq_txn", viewSeqTxn),
                    Map.entry("view_buffered_txns", 0L), Map.entry("base_suspended", false),
                    Map.entry("view_suspended", false), Map.entry("is_materialized_view", true));
        }
    }

    private static final State CURRENT = State.current(1722, 14, 1812, 2);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final AtomicInteger rowReads = new AtomicInteger();
        final QuestDbBoundedReader reader = new QuestDbBoundedReader(jdbc);

        Fixture(List<Map<String, Object>> states) throws Exception {
            var reads = new AtomicInteger();
            when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(call -> {
                var state = states.get(Math.min(reads.getAndIncrement(), states.size() - 1));
                var rs = mock(ResultSet.class);
                var wasNull = new AtomicBoolean();
                when(rs.next()).thenReturn(true, false);
                when(rs.getString(anyString())).thenAnswer(get -> (String) state.get(get.getArgument(0)));
                when(rs.getBoolean(anyString())).thenAnswer(get -> Boolean.TRUE.equals(state.get(get.getArgument(0))));
                when(rs.getLong(anyString())).thenAnswer(get -> {
                    Object value = state.get(get.getArgument(0));
                    wasNull.set(value == null);
                    return value == null ? 0L : ((Number) value).longValue();
                });
                when(rs.wasNull()).thenAnswer(get -> wasNull.get());
                return ((ResultSetExtractor<?>) call.getArgument(1)).extractData(rs);
            });
            var schema = new LinkedHashMap<String, String>();
            DEFINITION.columns().forEach(c -> schema.put(c.storageName(), c.storageType().name()));
            when(jdbc.query(any(PreparedStatementCreator.class),
                    org.mockito.ArgumentMatchers.<ResultSetExtractor<Map<String, String>>>any()))
                    .thenReturn(schema);
            when(jdbc.query(any(PreparedStatementCreator.class),
                    org.mockito.ArgumentMatchers.<RowMapper<DatasetValues>>any())).thenAnswer(call -> {
                rowReads.incrementAndGet();
                return List.of(row(LocalDate.of(2026, 9, 17)), row(LocalDate.of(2026, 9, 18)));
            });
        }

        Fixture(State... states) throws Exception { this(Arrays.stream(states).map(State::columns).toList()); }

        DatasetReadPage<LocalDate> read(DatasetReadCursor cursor) {
            var query = new DatasetReadQuery(DEFINITION.storageColumns(), Map.of(), "trade_date",
                    LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 19), 1, cursor);
            return reader.read(DEFINITION, query, "caller-version", row -> row.get("trade_date", LocalDate.class));
        }
    }

    private static DatasetValues row(LocalDate date) {
        return new DatasetValues(Map.of("trade_date", date));
    }

    @Test void stableSnapshotBindsCursorToBaseAndPhysicalMvIdentityAndVersion() throws Exception {
        var fixture = new Fixture(CURRENT);
        var page = fixture.read(null);
        assertEquals(List.of(LocalDate.of(2026, 9, 17)), page.rows());
        assertEquals("base-id:1722:base-txn:14:base-seq-txn:14:refresh-txn:14:mv-id:1812:mv-txn:2:mv-seq-txn:2", page.sourceVersion());
        assertEquals(page.sourceVersion(), page.nextCursor().sourceVersion());
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void rangeRefreshBetweenPagesRejectsCursorEvenWithTheSameBaseCheckpoint() throws Exception {
        var fixture = new Fixture(CURRENT, CURRENT, CURRENT.physical(14, 3));
        var first = fixture.read(null);
        assertThrows(IllegalArgumentException.class, () -> fixture.read(first.nextCursor()));
        assertEquals(1, fixture.rowReads.get(), "No page may be fetched with a stale MV cursor");
    }

    @Test void fullRefreshDuringReadRejectsThePageEvenWithTheSameBaseCheckpoint() throws Exception {
        var fixture = new Fixture(CURRENT, CURRENT.physical(14, 4));
        assertThrows(IllegalStateException.class, () -> fixture.read(null));
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void recreatedViewOrBaseCannotReuseMatchingTransactionCounters() throws Exception {
        for (State replacement : List.of(State.current(1722, 14, 1912, 2),
                State.current(1723, 14, 1812, 2))) {
            var fixture = new Fixture(CURRENT, CURRENT, replacement);
            var first = fixture.read(null);
            assertThrows(IllegalArgumentException.class, () -> fixture.read(first.nextCursor()));
            assertEquals(1, fixture.rowReads.get());
        }
    }

    @Test void missingPhysicalVersionFailsBeforeFetchingRows() throws Exception {
        var incomplete = new LinkedHashMap<>(CURRENT.columns());
        incomplete.remove("view_physical_txn");
        var fixture = new Fixture(List.of(incomplete));
        assertThrows(IllegalStateException.class, () -> fixture.read(null));
        assertEquals(0, fixture.rowReads.get());
    }

    @Test void independentPhysicalAndWalCountersAreAcceptedWhenWalIsSettled() throws Exception {
        var fixture = new Fixture(CURRENT.physical(16, 5));
        var result = fixture.read(null);
        assertEquals("base-id:1722:base-txn:16:base-seq-txn:14:refresh-txn:14:mv-id:1812:mv-txn:5:mv-seq-txn:2",
                result.sourceVersion());
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void sameNameFreshMvWithWrongBaseOrAggregationFailsOnItsFirstPage() throws Exception {
        for (var change : List.of(Map.entry("base_table_name", "another_factor"),
                Map.entry("view_sql", MarketBreadthDailyV1MaterializationPort.DEFINITION_SQL
                        .replace("avg(pct_change)", "sum(pct_change)")))) {
            var state = new LinkedHashMap<>(CURRENT.columns());
            state.put(change.getKey(), change.getValue());
            var fixture = new Fixture(List.of(state));
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get(), "Matching IDs, counters and columns cannot certify a different definition");
        }
    }

    @Test void fixedNativeMvSqlAllowsOnlyWhitespaceAndCaseNormalization() throws Exception {
        var state = new LinkedHashMap<>(CURRENT.columns());
        state.put("view_sql", "  " + MarketBreadthDailyV1MaterializationPort.DEFINITION_SQL
                .toLowerCase(Locale.ROOT).replace(" ", "  ") + "  ");
        var fixture = new Fixture(List.of(state));
        assertEquals(1, fixture.read(null).rows().size());
        assertEquals(1, fixture.rowReads.get());
    }

    @Test void invalidLaggingOrUnappliedWalStatesFailBeforeFetchingRows() throws Exception {
        for (var change : List.of(Map.entry("view_status", (Object) "invalid"),
                Map.entry("refresh_base_table_txn", (Object) 13L), Map.entry("base_seq_txn", (Object) 15L),
                Map.entry("base_writer_txn", (Object) 13L), Map.entry("base_buffered_txns", (Object) 1L),
                Map.entry("view_writer_txn", (Object) 1L), Map.entry("view_buffered_txns", (Object) 1L),
                Map.entry("base_suspended", (Object) true), Map.entry("view_suspended", (Object) true))) {
            var state = new LinkedHashMap<>(CURRENT.columns());
            state.put(change.getKey(), change.getValue());
            var fixture = new Fixture(List.of(state));
            assertThrows(IllegalStateException.class, () -> fixture.read(null));
            assertEquals(0, fixture.rowReads.get());
        }
    }
}