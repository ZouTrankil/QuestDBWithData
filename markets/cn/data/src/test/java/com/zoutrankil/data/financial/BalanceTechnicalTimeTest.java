package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.financial.config.FinancialSyncConfiguration;
import com.zoutrankil.data.financial.domain.FinancialContract.Row;
import com.zoutrankil.data.financial.mapper.FinancialMapper;
import com.zoutrankil.data.financial.storage.FinancialWritePort;

import io.questdb.client.QuestDB;
import io.questdb.client.Sender;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

class BalanceTechnicalTimeTest {
    @Test
    void existingTechnicalInstantIsPreservedAtMicrosecondPrecisionAndNeverReducedToADate()
            throws Exception {
        var contract = FinancialSyncConfiguration.contract("balance");
        var source =
                new FinancialMapper(contract)
                        .normalize(
                                FinancialSyncTest.row(contract, "20260828", 1),
                                "600000.SH",
                                FinancialSyncTest.PERIOD,
                                FinancialSyncTest.DAY);
        var before = new TreeMap<>(source.values());
        Instant instant = Instant.parse("2026-09-01T12:34:56.123456Z");
        before.put("update_time", instant);
        var old = new Row(before);
        var rs = mock(ResultSet.class);
        when(rs.getString(anyString())).thenAnswer(c -> before.get(c.getArgument(0)));
        when(rs.getObject(anyString(), eq(Double.class)))
                .thenAnswer(c -> before.get(c.getArgument(0)));
        when(rs.getLong(anyString()))
                .thenAnswer(
                        c -> {
                            String name = c.getArgument(0);
                            Instant at =
                                    name.equals("ann_date")
                                            ? Instant.parse("2026-08-28T00:00:00Z")
                                            : instant;
                            return at.getEpochSecond() * 1_000_000L + at.getNano() / 1000;
                        });
        var sql = new ArrayList<String>();
        try (var constructed =
                mockConstruction(
                        JdbcTemplate.class,
                        (jdbc, context) -> {
                            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                                    .thenAnswer(
                                            c -> {
                                                sql.add(c.getArgument(0));
                                                RowMapper<Row> mapper = c.getArgument(1);
                                                return List.of(mapper.mapRow(rs, 0));
                                            });
                        })) {
            var questdb = mock(QuestDB.class);
            var sender = mock(Sender.class);
            when(questdb.borrowSender()).thenReturn(sender);
            when(sender.table("balance")).thenReturn(sender);
            when(sender.flushAndGetSequence()).thenReturn(1L);
            when(sender.awaitAckedFsn(1L, 10000)).thenReturn(true);
            var target = new FinancialWritePort(contract, mock(JdbcTemplate.class), questdb);
            var merged = target.preserveLocalColumns(List.of(source));
            assertEquals(List.of(old), merged);
            assertTrue(sql.getFirst().contains("cast(\"update_time\" AS long)"));
            assertTrue(sql.getFirst().contains("cast(\"ann_date\" AS long)"));
            verify(rs, never()).getTimestamp(anyString());
            target.send(merged);
            verify(sender).timestampColumn("update_time", instant);
            verify(sender).at(Instant.parse("2026-08-28T00:00:00Z"));
        }
    }
}
