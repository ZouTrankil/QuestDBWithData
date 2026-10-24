package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;
import com.zoutrankil.market.cn.observation.storage.ContractKeyRepairPlan;
import com.zoutrankil.market.cn.observation.storage.QuestDbContractTableTarget;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ContractKeyRepairPlanTest {
    @Test
    void completeLegacyPlanNeverExecutesDdlAndRejectsUnknownSchema() {
        for (String dataset : List.of("broker_recommend", "stk_alert", "sge_daily")) check(dataset);
    }

    private void check(String dataset) {
        var collision = new AtomicBoolean();
        var invalid = new AtomicBoolean();
        var changed = new AtomicBoolean();
        var unknown = new AtomicBoolean();
        var corrected = new AtomicBoolean();
        var reads = new AtomicInteger();
        var definition = ContractTableDefinition.from(dataset);
        Set<String> legacy =
                dataset.equals("sge_daily")
                        ? Set.of()
                        : dataset.equals("broker_recommend")
                                ? Set.of("month", "ts_code")
                                : Set.of("ts_code", "start_date", "end_date", "type");
        try (var checks = mockStatic(QuestDbWriteChecks.class);
                var targets =
                        mockConstruction(
                                QuestDbContractTableTarget.class,
                                (m, c) -> when(m.targetId()).thenReturn("original-target"));
                var connections =
                        mockConstruction(
                                JdbcTemplate.class,
                                (m, c) -> {
                                    when(m.queryForList(anyString()))
                                            .thenAnswer(
                                                    call -> {
                                                        String sql = call.getArgument(0);
                                                        if (sql.contains("table_columns"))
                                                            return definition.columns().stream()
                                                                    .map(
                                                                            col ->
                                                                                    Map
                                                                                            .<String,
                                                                                                    Object>
                                                                                                    of(
                                                                                                            "column",
                                                                                                            col
                                                                                                                    .storageName(),
                                                                                                            "type",
                                                                                                            unknown
                                                                                                                            .get()
                                                                                                                    ? "UNKNOWN"
                                                                                                                    : col.storageType()
                                                                                                                            .sqlType(),
                                                                                                            "upsertKey",
                                                                                                            (corrected
                                                                                                                                    .get()
                                                                                                                            ? Set
                                                                                                                                    .copyOf(
                                                                                                                                            definition
                                                                                                                                                    .dedupKey())
                                                                                                                            : legacy)
                                                                                                                    .contains(
                                                                                                                            col
                                                                                                                                    .storageName())))
                                                                    .toList();
                                                        if (sql.contains("copies>1"))
                                                            return collision.get()
                                                                    ? List.of(Map.of("copies", 2L))
                                                                    : List.of();
                                                        if (sql.startsWith("SELECT DISTINCT"))
                                                            return List.of(
                                                                    Map.of(
                                                                            "scope",
                                                                            "original-scope"));
                                                        throw new AssertionError(sql);
                                                    });
                                    when(m.queryForList(anyString(), any(Object[].class)))
                                            .thenAnswer(
                                                    call -> {
                                                        String sql = call.getArgument(0);
                                                        if (sql.contains("partitionBy"))
                                                            return List.of(
                                                                    Map.of(
                                                                            "designatedTimestamp",
                                                                            definition
                                                                                    .designatedTimestamp(),
                                                                            "partitionBy",
                                                                            definition
                                                                                    .partition()
                                                                                    .name()));
                                                        if (sql.contains("wal_tables"))
                                                            return List.of(
                                                                    Map.of(
                                                                            "writerTxn",
                                                                            changed.get()
                                                                                    ? reads
                                                                                            .incrementAndGet()
                                                                                    : 1L));
                                                        throw new AssertionError(sql);
                                                    });
                                    when(m.queryForObject(anyString(), eq(Long.class)))
                                            .thenAnswer(c2 -> invalid.get() ? 1L : 0L);
                                })) {
            checks.when(() -> QuestDbWriteChecks.walSettled(any(), eq(dataset))).thenReturn(true);
            var planner = new ContractKeyRepairPlan(mock(JdbcTemplate.class));
            var plan = planner.inspect(dataset);
            assertEquals("KEY_CHANGE_AND_BACKFILL_REQUIRED", plan.status());
            assertEquals(definition.dedupKey(), plan.requiredKeys());
            assertTrue(plan.candidateSql().startsWith("ALTER TABLE " + dataset));
            assertEquals(1, plan.sourceScopes().size());
            assertFalse(plan.productionWritten());
            collision.set(true);
            assertEquals("REBUILD_REQUIRED", planner.inspect(dataset).status());
            assertNull(planner.inspect(dataset).candidateSql());
            collision.set(false);
            invalid.set(true);
            assertNull(planner.inspect(dataset).candidateSql());
            invalid.set(false);
            corrected.set(true);
            assertEquals("KEYS_MATCH_BACKFILL_REQUIRED", planner.inspect(dataset).status());
            assertNull(planner.inspect(dataset).candidateSql());
            changed.set(true);
            assertThrows(IllegalStateException.class, () -> planner.inspect(dataset));
            changed.set(false);
            unknown.set(true);
            assertThrows(IllegalStateException.class, () -> planner.inspect(dataset));
            assertThrows(IllegalArgumentException.class, () -> planner.inspect("daily"));
            for (var jdbc : connections.constructed()) {
                verify(jdbc, never()).execute(anyString());
                verify(jdbc, never()).update(anyString());
            }
        }
    }
}
