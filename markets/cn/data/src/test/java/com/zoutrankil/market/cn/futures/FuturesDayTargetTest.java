package com.zoutrankil.market.cn.futures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.cn.futures.mapper.FuturesDayDefinition;
import com.zoutrankil.market.cn.futures.storage.QuestDbFuturesDayTarget;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class FuturesDayTargetTest {
    static Stream<String> datasets() {
        return FuturesDayDefinition.DATASETS.stream().sorted();
    }

    static Map<String, Object> row(String dataset) {
        var raw =
                SourceContract.load(dataset)
                        .normalize(
                                FuturesDaySyncTest.raw(dataset, FuturesDaySyncTest.FROM, 100),
                                FuturesDaySyncTest.FROM,
                                Set.of(FuturesDaySyncTest.code(dataset)));
        return FuturesDayDefinition.physical(
                dataset, raw, Instant.parse("2026-10-20T02:03:04.123456Z"));
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void senderPreservesEveryTypeAndWritesSymbolsBeforeScalarColumns(String dataset)
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var q = mock(QuestDB.class);
        var sender = mock(Sender.class);
        when(q.borrowSender()).thenReturn(sender);
        when(sender.flushAndGetSequence()).thenReturn(1L);
        when(sender.awaitAckedFsn(1L, 10000)).thenReturn(true);
        try (var constructed = mockConstruction(JdbcTemplate.class)) {
            var target = new QuestDbFuturesDayTarget(dataset, jdbc, q);
            var expected = row(dataset);
            target.send(List.of(expected));
            var order = inOrder(sender);
            order.verify(sender).table(dataset);
            for (var c : FuturesDayDefinition.from(dataset).columns())
                if (c.storageType() == StorageType.SYMBOL)
                    order.verify(sender)
                            .symbol(c.storageName(), expected.get(c.storageName()).toString());
            for (var c : FuturesDayDefinition.from(dataset).columns()) {
                if (c.storageType() == StorageType.STRING)
                    verify(sender)
                            .stringColumn(
                                    c.storageName(), expected.get(c.storageName()).toString());
                if (c.storageType() == StorageType.DOUBLE)
                    verify(sender)
                            .doubleColumn(c.storageName(), (Double) expected.get(c.storageName()));
            }
            order.verify(sender)
                    .timestampColumn(
                            "update_time", Instant.parse(expected.get("update_time").toString()));
            verify(sender).at(Instant.parse("2026-10-16T00:00:00Z"));
            var bad = new TreeMap<>(expected);
            bad.put("trade_date", "2026-10-15T16:00:00Z");
            assertThrows(IllegalArgumentException.class, () -> target.send(List.of(bad)));
            bad.put("trade_date", expected.get("trade_date"));
            bad.put("update_time", "2026-10-20T02:03:04.123456789Z");
            assertThrows(IllegalArgumentException.class, () -> target.send(List.of(bad)));
            verify(q, times(1)).borrowSender();
        }
    }

    @ParameterizedTest
    @MethodSource("datasets")
    void readbackChecksFullKeyBothTimestampsAndUnchangedWalVersion(String dataset)
            throws Exception {
        var expected = row(dataset);
        var rs = mock(ResultSet.class);
        var sql = new ArrayList<String>();
        var params = new ArrayList<Object>();
        when(rs.getString(anyString())).thenAnswer(c -> expected.get(c.getArgument(0)));
        when(rs.getObject(anyString(), eq(Double.class)))
                .thenAnswer(c -> expected.get(c.getArgument(0)));
        when(rs.getLong(anyString()))
                .thenAnswer(
                        c -> {
                            Instant t = Instant.parse(expected.get(c.getArgument(0)).toString());
                            return t.getEpochSecond() * 1_000_000L + t.getNano() / 1000;
                        });
        var changed = new AtomicBoolean();
        var counter = new AtomicLong();
        var jdbc = mock(JdbcTemplate.class);
        try (var constructed =
                mockConstruction(
                        JdbcTemplate.class,
                        (mock, context) -> {
                            when(mock.queryForList(anyString(), any(Object[].class)))
                                    .thenAnswer(
                                            c -> {
                                                long version =
                                                        changed.get()
                                                                ? counter.incrementAndGet()
                                                                : 1;
                                                return List.of(
                                                        Map.of(
                                                                "writerTxn",
                                                                version,
                                                                "sequencerTxn",
                                                                version,
                                                                "bufferedTxnSize",
                                                                0L,
                                                                "suspended",
                                                                false));
                                            });
                            doAnswer(
                                            c -> {
                                                sql.add(c.getArgument(0));
                                                params.addAll(
                                                        Arrays.asList(
                                                                (Object[]) c.getRawArguments()[2]));
                                                RowMapper<Map<String, Object>> mapper =
                                                        c.getArgument(1);
                                                return List.of(mapper.mapRow(rs, 0));
                                            })
                                    .when(mock)
                                    .query(anyString(), any(RowMapper.class), any(Object[].class));
                        })) {
            var target = new QuestDbFuturesDayTarget(dataset, jdbc, null);
            var key =
                    VerifiedMarketDataStore.codec(FuturesDayDefinition.from(dataset)).key(expected);
            assertEquals(List.of(expected), target.readback(List.of(key)));
            assertTrue(sql.getFirst().contains("cast(\"trade_date\" AS long)"));
            assertTrue(sql.getFirst().contains("cast(\"update_time\" AS long)"));
            assertEquals(FuturesDaySyncTest.code(dataset), params.getFirst());
            verify(rs, never()).getTimestamp(anyString());
            changed.set(true);
            assertThrows(IllegalStateException.class, () -> target.readback(List.of(key)));
            assertThrows(IllegalArgumentException.class, () -> target.readback(List.of(key, key)));
        }
    }
}
