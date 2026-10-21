package com.zoutrankil.market.cn.chips;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.cn.chips.mapper.CyqPerfDefinition;
import com.zoutrankil.market.cn.chips.storage.QuestDbCyqPerfTarget;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class CyqPerfTargetTest {
    @Test
    void everyNumericFieldIsSentWithoutScalingAndBadDateStopsBeforeSender() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var questdb = mock(QuestDB.class);
        var sender = mock(Sender.class);
        when(questdb.borrowSender()).thenReturn(sender);
        when(sender.table(anyString())).thenReturn(sender);
        when(sender.flushAndGetSequence()).thenReturn(1L);
        when(sender.awaitAckedFsn(1L, 10000)).thenReturn(true);
        try (var constructed = mockConstruction(JdbcTemplate.class)) {
            var target = new QuestDbCyqPerfTarget(jdbc, questdb);
            var row = physical();
            target.send(List.of(row));
            verify(sender).symbol("ts_code", "000001.SZ");
            verify(sender).at(Instant.parse("2026-10-16T00:00:00Z"));
            for (var c : CyqPerfDefinition.DEFINITION.columns())
                if (c.storageType().name().equals("DOUBLE"))
                    verify(sender).doubleColumn(c.storageName(), (Double) row.get(c.storageName()));
            var wrong = new TreeMap<>(row);
            wrong.put("trade_date", "2026-10-15T16:00:00Z");
            assertThrows(IllegalArgumentException.class, () -> target.send(List.of(wrong)));
            verify(questdb, times(1)).borrowSender();
            var nonfinite = new TreeMap<>(row);
            nonfinite.put("winner_rate", Double.POSITIVE_INFINITY);
            assertThrows(IllegalArgumentException.class, () -> target.send(List.of(nonfinite)));
            verify(questdb, times(1)).borrowSender();
        }
    }

    @Test
    void readbackUsesPairedKeysMicrosecondDatesAndEveryValueWithoutJdbcTimezoneConversion()
            throws Exception {
        var rs = mock(ResultSet.class);
        var expected = physical();
        when(rs.getString("ts_code")).thenReturn("000001.SZ");
        when(rs.getLong("trade_date"))
                .thenReturn(Instant.parse("2026-10-16T00:00:00Z").getEpochSecond() * 1_000_000);
        when(rs.getObject(anyString(), eq(Double.class)))
                .thenAnswer(call -> expected.get(call.getArgument(0)));
        var sql = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        try (var constructed =
                mockConstruction(
                        JdbcTemplate.class,
                        (mock, context) ->
                                doAnswer(
                                                call -> {
                                                    sql.add(call.getArgument(0));
                                                    parameters.addAll(
                                                            Arrays.asList(
                                                                    (Object[])
                                                                            call.getRawArguments()[
                                                                                    2]));
                                                    RowMapper<Map<String, Object>> mapper =
                                                            call.getArgument(1);
                                                    return List.of(mapper.mapRow(rs, 0));
                                                })
                                        .when(mock)
                                        .query(
                                                anyString(),
                                                any(RowMapper.class),
                                                any(Object[].class)))) {
            var target = new QuestDbCyqPerfTarget(mock(JdbcTemplate.class), null);
            var codec = VerifiedMarketDataStore.codec(CyqPerfDefinition.DEFINITION);
            assertEquals(List.of(expected), target.readback(List.of(codec.key(expected))));
            assertTrue(sql.getFirst().contains("cast(trade_date AS long) AS trade_date"));
            assertTrue(sql.getFirst().contains("ts_code=? AND trade_date=cast(? AS TIMESTAMP)"));
            assertEquals("000001.SZ", parameters.getFirst());
            assertEquals(
                    Instant.parse("2026-10-16T00:00:00Z").getEpochSecond() * 1_000_000,
                    parameters.get(1));
            verify(rs, never()).getTimestamp(anyString());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> target.readback(List.of("[\"2026-10-16T00:00:00Z\"]")));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> target.readback(List.of(codec.key(expected), codec.key(expected))));
        }
    }

    static Map<String, Object> physical() {
        var row = new TreeMap<String, Object>();
        double n = 1;
        for (var c : CyqPerfDefinition.DEFINITION.columns())
            row.put(
                    c.storageName(),
                    switch (c.storageType()) {
                        case SYMBOL -> "000001.SZ";
                        case TIMESTAMP -> "2026-10-16T00:00:00Z";
                        default -> n++;
                    });
        return Collections.unmodifiableMap(row);
    }
}
