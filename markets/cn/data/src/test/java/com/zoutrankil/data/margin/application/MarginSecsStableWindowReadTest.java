package com.zoutrankil.data.margin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.margin.storage.MarginSecsStorage;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

class MarginSecsStableWindowReadTest {
    @ParameterizedTest
    @ValueSource(strings = {"stable", "walChanged", "physicalChanged", "unsettled"})
    void formalWindowNeedsOneSettledPhysicalGeneration(String fault) {
        var source = new JdbcTemplate(mock(DataSource.class));
        String id = "static-v2-" + "a".repeat(64);
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var identity = mockStatic(StaticTargetIdentity.class);
                var clients =
                        mockConstruction(
                                JdbcTemplate.class,
                                (client, context) -> {
                                    when(client.queryForList(
                                                    "SELECT writerTxn FROM wal_tables() WHERE name=?",
                                                    "margin_secs"))
                                            .thenReturn(
                                                    List.of(Map.of("writerTxn", 1L)),
                                                    List.of(
                                                            Map.of(
                                                                    "writerTxn",
                                                                    "walChanged".equals(fault)
                                                                            ? 2L
                                                                            : 1L)));
                                    when(client.queryForList(
                                                    "SELECT id,directoryName FROM tables() WHERE table_name=?",
                                                    "margin_secs"))
                                            .thenReturn(
                                                    List.of(
                                                            Map.of(
                                                                    "id",
                                                                    1L,
                                                                    "directoryName",
                                                                    "margin_secs~1")));
                                    when(client.query(
                                                    anyString(),
                                                    any(RowMapper.class),
                                                    any(Object[].class)))
                                            .thenReturn(List.of());
                                })) {
            checks.when(() -> QuestDbWriteChecks.walSettled(any(), eq("margin_secs")))
                    .thenReturn(!"unsettled".equals(fault));
            identity.when(
                            () ->
                                    StaticTargetIdentity.identify(
                                            any(), eq("margin_secs"), eq(1L), eq("margin_secs~1")))
                    .thenReturn(
                            id,
                            "physicalChanged".equals(fault) ? "static-v2-" + "b".repeat(64) : id);
            var storage = new MarginSecsStorage(source, "margin_secs");
            var day = LocalDate.of(2026, 9, 18);
            if ("stable".equals(fault)) assertEquals(List.of(), storage.readRange(day, day, 6000));
            else assertThrows(IllegalStateException.class, () -> storage.readRange(day, day, 6000));
            if ("unsettled".equals(fault))
                verify(clients.constructed().getFirst(), never())
                        .query(anyString(), any(RowMapper.class), any(Object[].class));
        }
    }
}
