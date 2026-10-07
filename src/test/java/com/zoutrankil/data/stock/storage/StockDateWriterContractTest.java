package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.mapper.StockFactorMapper;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import io.questdb.client.Sender;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Preserves each writer's identity, exact-key query and failure/close contract. */
class StockDateWriterContractTest {
    private static final String ID = "static-v2-" + "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2020, 1, 4);
    enum Family { FACTOR, LIMIT, ST }

    @ParameterizedTest @EnumSource(Family.class)
    void schemaPreflightFailureNeverBorrowsOrWrites(Family family) throws Exception {
        try (var h = new Harness(family)) {
            var failure = new IllegalStateException("schema changed");
            h.checks.when(() -> QuestDbWriteChecks.preflight(eq(h.jdbc), eq(h.table), any(DatasetDefinition.class))).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, h::send));
            verifyNoInteractions(h.qdb, h.sender); assertFalse(h.writer.uncertainSenderStopped());
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedGenerationStopsSendReadbackAndWalBeforeAnyBackendWrite(Family family) throws Exception {
        try (var h = new Harness(family)) {
            h.identities.when(() -> StaticTargetIdentity.identify(h.jdbc, h.table, 7L, "generation")).thenReturn("static-v2-other");
            assertThrows(IllegalStateException.class, h::send);
            assertThrows(IllegalStateException.class, h::readback);
            assertThrows(IllegalStateException.class, h.writer::walSettled);
            verifyNoInteractions(h.qdb, h.sender);
            assertTrue(mockingDetails(h.jdbc).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("query")));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void acknowledgementAndCloseFailureKeepTheOriginalFamilyExceptionPolicy(Family family) throws Exception {
        try (var h = new Harness(family)) {
            var flushFailure = new IllegalStateException("flush failed"); var closeFailure = new IllegalStateException("close failed");
            when(h.sender.flushAndGetSequence()).thenThrow(flushFailure); doThrow(closeFailure).when(h.sender).close();
            Exception actual = assertThrows(IllegalStateException.class, h::send);
            if (family == Family.FACTOR) {
                assertSame(closeFailure, actual); assertEquals(0, actual.getSuppressed().length);
                assertFalse(h.writer.uncertainSenderStopped());
            } else {
                assertSame(flushFailure, actual); assertArrayEquals(new Throwable[]{closeFailure}, actual.getSuppressed());
                assertTrue(h.writer.uncertainSenderStopped());
            }
            verify(h.sender).close(); verify(h.sender, never()).awaitAckedFsn(anyLong(), anyLong());
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void successfulAcknowledgementRetainsTenSecondBoundAndClosesTheSender(Family family) throws Exception {
        try (var h = new Harness(family)) {
            when(h.sender.flushAndGetSequence()).thenReturn(19L); when(h.sender.awaitAckedFsn(19L, 10_000L)).thenReturn(true);
            h.send(); assertTrue(h.writer.uncertainSenderStopped());
            var order = inOrder(h.sender); order.verify(h.sender).flushAndGetSequence();
            order.verify(h.sender).awaitAckedFsn(19L, 10_000L); order.verify(h.sender).close();
            verify(h.sender).table(h.table);
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void mixedDateReadbackKeepsExactSqlSortedCodeFilterAndOriginalPairOrder(Family family) throws Exception {
        try (var h = new Harness(family)) {
            h.readback();
            var query = mockingDetails(h.jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().equals("query")).findFirst().orElseThrow();
            String pairs; String expected;
            if (family == Family.FACTOR) {
                String projection = String.join(",", StockFactorDataset.STORAGE_COLUMNS.stream().map(c -> c.equals("trade_date")
                        ? "cast(\"trade_date\" as long) AS trade_micros" : "\"" + c + "\"").toList());
                pairs = "(ts_code=? AND trade_date=cast(? AS TIMESTAMP)) OR (ts_code=? AND trade_date=cast(? AS TIMESTAMP))";
                expected = "SELECT " + projection + " FROM \"stk_factor\" WHERE trade_date>=cast(? AS TIMESTAMP)"
                        + " AND trade_date<cast(? AS TIMESTAMP) AND ts_code IN (?,?) AND (" + pairs + ") ORDER BY ts_code,trade_date LIMIT 3";
            } else if (family == Family.LIMIT) {
                pairs = "(ts_code = ? AND trade_date = cast(? as TIMESTAMP)) OR (ts_code = ? AND trade_date = cast(? as TIMESTAMP))";
                expected = "SELECT ts_code, cast(trade_date as long) AS trade_date_micros, up_limit, down_limit FROM \"stk_limit\""
                        + " WHERE trade_date >= cast(? AS TIMESTAMP) AND trade_date < cast(? AS TIMESTAMP)"
                        + " AND ts_code IN (?,?) AND (" + pairs + ") ORDER BY trade_date, ts_code LIMIT 3";
            } else {
                expected = "SELECT ts_code, is_st, cast(timestamp AS long) AS timestamp_micros FROM \"stk_st_daily\""
                        + " WHERE (ts_code=? AND timestamp=cast(? AS TIMESTAMP)) OR (ts_code=? AND timestamp=cast(? AS TIMESTAMP))"
                        + " ORDER BY timestamp,ts_code LIMIT 3";
            }
            assertEquals(expected, query.getArgument(0));
            Object[] pairsInOrder = {"600000.SH", micros(DAY.plusDays(1)), "000001.SZ", micros(DAY)};
            Object[] expectedParams = family == Family.ST ? pairsInOrder
                    : new Object[]{micros(DAY), micros(DAY.plusDays(2)), "000001.SZ", "600000.SH",
                            "600000.SH", micros(DAY.plusDays(1)), "000001.SZ", micros(DAY)};
            assertArrayEquals(expectedParams, (Object[]) query.getRawArguments()[2]);
            verifyNoInteractions(h.qdb, h.sender);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void factorPhysicalReadbackNormalizesNonFiniteNumbersToNullAndRejectsStrings() throws Exception {
        try (var h = new Harness(Family.FACTOR)) {
            var rs = mock(ResultSet.class); when(rs.getObject("trade_micros")).thenReturn(micros(DAY));
            when(rs.getString("ts_code")).thenReturn("000001.SZ");
            when(rs.getObject("close")).thenReturn(Double.NaN); when(rs.getObject("open")).thenReturn(Double.POSITIVE_INFINITY);
            when(rs.getObject("high")).thenReturn(3); when(rs.getObject("amount")).thenReturn(new java.math.BigDecimal("0.1"));
            when(h.jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(rs, 0)));
            var row = ((StockFactorWritePort) h.writer).readback(List.of(new StockFactorKey("000001.SZ", DAY))).getFirst();
            assertNull(row.fields().close()); assertNull(row.fields().open()); assertEquals(3D, row.fields().high());
            assertEquals(0.1D, row.fields().amount());
            when(rs.getObject("close")).thenReturn("3");
            assertThrows(SQLException.class, () -> ((StockFactorWritePort) h.writer).readback(List.of(new StockFactorKey("000001.SZ", DAY))));
        }
    }

    @Test @SuppressWarnings("unchecked")
    void limitPhysicalReadbackKeepsNullsButRejectsNonFiniteNumbers() throws Exception {
        try (var h = new Harness(Family.LIMIT)) {
            var rs = mock(ResultSet.class); when(rs.getObject("trade_date_micros")).thenReturn(micros(DAY));
            when(rs.getString("ts_code")).thenReturn("000001.SZ"); when(rs.getObject("up_limit")).thenReturn(3);
            when(h.jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(rs, 0)));
            var row = ((StockLimitWritePort) h.writer).readDate(DAY).getFirst();
            assertEquals(3D, row.upLimit()); assertNull(row.downLimit()); assertEquals(DAY, row.tradeDate());
            when(rs.getObject("down_limit")).thenReturn(Double.NaN);
            assertThrows(SQLException.class, () -> ((StockLimitWritePort) h.writer).readDate(DAY));
        }
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"LIMIT", "ST"})
    @SuppressWarnings("unchecked")
    void dateAndInventoryReadbackRejectTheExtraSentinelRow(Family family) throws Exception {
        try (var h = new Harness(family)) {
            int bound = family == Family.LIMIT ? 5800 : 10000;
            when(h.jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(Collections.nCopies(bound + 1, h.row()));
            assertThrows(IllegalStateException.class, () -> h.readDate(DAY));
            var query = mockingDetails(h.jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().equals("query")).findFirst().orElseThrow();
            assertTrue(((String) query.getArgument(0)).endsWith("LIMIT " + (bound + 1)));
            assertArrayEquals(new Object[]{micros(DAY)}, (Object[]) query.getRawArguments()[2]);
            when(h.jdbc.query(anyString(), any(RowMapper.class))).thenReturn(Collections.nCopies(10001, DAY));
            assertThrows(IllegalStateException.class, h::readExistingDates);
        }
    }

    @Test void emptyReadbackAndDuplicateValidationKeepDifferentFamilyOrder() throws Exception {
        try (var h = new Harness(Family.FACTOR)) {
            assertEquals(List.of(), ((StockFactorWritePort) h.writer).readback(List.of()));
            clearInvocations(h.jdbc);
            var key = new StockFactorKey("000001.SZ", DAY);
            assertThrows(IllegalArgumentException.class, () -> ((StockFactorWritePort) h.writer).readback(List.of(key, key)));
            verify(h.jdbc).queryForList(anyString(), eq("stk_factor"));
        }
        for (Family family : List.of(Family.LIMIT, Family.ST)) {
            try (var h = new Harness(family)) {
                clearInvocations(h.jdbc);
                assertThrows(IllegalArgumentException.class, () -> {
                    if (family == Family.LIMIT) ((StockLimitWritePort) h.writer).readback(List.of());
                    else ((StockStDailyWritePort) h.writer).readback(List.of());
                });
                verifyNoInteractions(h.jdbc);
            }
        }
    }

    private static long micros(LocalDate day) { return day.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1_000_000L; }
    private static final class Harness implements AutoCloseable {
        final Family family; final String table; final QuestDB qdb = mock(QuestDB.class);
        final Sender sender = mock(Sender.class, RETURNS_SELF);
        final MockedStatic<QuestDbWriteChecks> checks = mockStatic(QuestDbWriteChecks.class);
        final MockedStatic<StaticTargetIdentity> identities = mockStatic(StaticTargetIdentity.class);
        final MockedConstruction<JdbcTemplate> copies;
        final JdbcTemplate jdbc; final VerifiedWriteSession<?, ?> writer;
        Harness(Family family) {
            this.family = family; table = switch (family) { case FACTOR -> "stk_factor"; case LIMIT -> "stk_limit"; case ST -> "stk_st_daily"; };
            var original = mock(JdbcTemplate.class); when(original.getDataSource()).thenReturn(mock(DataSource.class));
            copies = mockConstruction(JdbcTemplate.class);
            writer = switch (family) {
                case FACTOR -> new StockFactorWritePort(table, original, qdb, 1024 * 1024, Duration.ofSeconds(10), ID);
                case LIMIT -> new StockLimitWritePort(table, ID, original, qdb);
                case ST -> new StockStDailyWritePort(table, ID, original, qdb);
            };
            jdbc = copies.constructed().getFirst();
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("id", 7L, "directoryName", "generation")));
            identities.when(() -> StaticTargetIdentity.identify(jdbc, table, 7L, "generation")).thenReturn(ID);
            when(qdb.borrowSender()).thenReturn(sender);
        }
        Object row() {
            return switch (family) {
                case FACTOR -> {
                    var values = new LinkedHashMap<String,Object>(); StockFactorDataset.STORAGE_COLUMNS.forEach(c -> values.put(c, null));
                    values.put("ts_code", "000001.SZ"); values.put("trade_date", DAY);
                    yield new StockFactorMapper().fromValues(new DatasetValues(values));
                }
                case LIMIT -> new StockLimit("000001.SZ", DAY, 3D, 1D);
                case ST -> new StockStDaily("000001.SZ", DAY, 1);
            };
        }
        void send() throws Exception {
            switch (family) {
                case FACTOR -> ((StockFactorWritePort) writer).send(List.of((StockFactor) row()));
                case LIMIT -> ((StockLimitWritePort) writer).send(List.of((StockLimit) row()));
                case ST -> ((StockStDailyWritePort) writer).send(List.of((StockStDaily) row()));
            }
        }
        void readback() {
            switch (family) {
                case FACTOR -> ((StockFactorWritePort) writer).readback(List.of(new StockFactorKey("600000.SH", DAY.plusDays(1)), new StockFactorKey("000001.SZ", DAY)));
                case LIMIT -> ((StockLimitWritePort) writer).readback(List.of(new StockLimitKey("600000.SH", DAY.plusDays(1)), new StockLimitKey("000001.SZ", DAY)));
                case ST -> ((StockStDailyWritePort) writer).readback(List.of(new StockStDailyKey("600000.SH", DAY.plusDays(1)), new StockStDailyKey("000001.SZ", DAY)));
            }
        }
        void readDate(LocalDate date) { if (family == Family.LIMIT) ((StockLimitWritePort) writer).readDate(date); else ((StockStDailyWritePort) writer).readDate(date); }
        void readExistingDates() { if (family == Family.LIMIT) ((StockLimitWritePort) writer).readExistingDates(); else ((StockStDailyWritePort) writer).readExistingDates(); }
        @Override public void close() { copies.close(); identities.close(); checks.close(); }
    }
}
