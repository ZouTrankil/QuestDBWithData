package com.zoutrankil.data.stock.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.stock.market.StockSuspend;
import com.zoutrankil.data.domain.stock.market.StockSuspendKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.stock.domain.StockSuspendState.Identity;
import com.zoutrankil.data.stock.domain.StockSuspendState.Snapshot;
import com.zoutrankil.data.stock.mapper.StockSuspendLayoutMapper;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

class StockSuspendVersionedStagingTest {
    @TempDir Path root;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void ctasAndInsertUseOneFrozenPhysicalColumnAndPreserveOriginalLogicalRows(int version)
            throws Exception {
        var layout = CnTradeDateLayout.fromVersion(version);
        var input = mock(JdbcTemplate.class);
        when(input.getDataSource()).thenReturn(mock(DataSource.class));
        var before =
                snapshot(
                        1,
                        List.of(
                                row("000001.SZ", DAY.minusDays(1)),
                                row("000002.SZ", DAY),
                                row("000003.SZ", DAY.plusDays(1))));
        var copied = snapshot(2, List.of(before.rows().getFirst(), before.rows().getLast()));
        var source = List.of(row("000004.SZ", DAY));
        var expected =
                List.of(before.rows().getFirst(), source.getFirst(), before.rows().getLast());
        var after = snapshot(2, expected);
        var prepared = StockSuspendStaging.prepare(before, before, source, DAY, DAY);
        assertEquals(expected, prepared.expected());
        try (var templates = mockConstruction(JdbcTemplate.class);
                var stores =
                        mockConstruction(
                                StockSuspendStorage.class,
                                (store, context) -> {
                                    assertEquals(layout, context.arguments().get(2));
                                    when(store.snapshot()).thenReturn(copied, after);
                                });
                var checks = mockStatic(QuestDbWriteChecks.class)) {
            checks.when(() -> QuestDbWriteChecks.walSettled(any(JdbcTemplate.class), anyString()))
                    .thenReturn(true);
            var stage =
                    new StockSuspendStaging(input, "java_d011_stk_suspend_staging", layout)
                            .write(prepared, root, () -> false);
            assertEquals(after, stage.snapshot());
            assertEquals(1, stage.batches());
            var jdbc = templates.constructed().getFirst();
            var ddl = ArgumentCaptor.forClass(String.class);
            verify(jdbc).execute(ddl.capture());
            String column = version == 1 ? "timestamp" : "trade_date";
            assertTrue(
                    ddl.getValue()
                            .contains("WHERE " + column + " < cast('2026-09-28T00:00:00.000000Z'"));
            assertTrue(
                    ddl.getValue()
                            .contains("OR " + column + " >= cast('2026-09-29T00:00:00.000000Z'"));
            assertTrue(
                    ddl.getValue()
                            .endsWith(
                                    "TIMESTAMP("
                                            + column
                                            + ") PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts_code,"
                                            + column
                                            + ")"));
            var sql = ArgumentCaptor.forClass(String.class);
            var setter = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
            verify(jdbc).batchUpdate(sql.capture(), setter.capture());
            assertTrue(
                    sql.getValue()
                            .contains(
                                    "(ts_code,is_suspended,"
                                            + column
                                            + ") VALUES (?,?,cast(? AS TIMESTAMP))"));
            var statement = mock(PreparedStatement.class);
            setter.getValue().setValues(statement, 0);
            verify(statement).setString(1, "000004.SZ");
            verify(statement).setLong(2, 1L);
            verify(statement).setLong(3, DAY.toEpochDay() * 86_400_000_000L);
            var proof = JobDefinitionJson.mapper().readTree(Path.of(stage.receipt()).toFile());
            StockSuspendLayoutMapper.require(proof, layout);
            assertEquals(2, proof.path("preservedOutsideRows").asInt());
            if (version == 1) assertFalse(proof.has("protocolVersion"));
            else assertEquals("trade_date-v1", proof.path("rowEncoding").asText());
        }
    }

    private static StockSuspend row(String code, LocalDate date) {
        return new StockSuspend(new StockSuspendKey(code, date), 1L);
    }

    private static Snapshot snapshot(long id, List<StockSuspend> rows) throws Exception {
        byte[] bytes = StockSuspendStorage.canonical(rows);
        return new Snapshot(
                new Identity(id, "dir-" + id), rows, FileEvidenceStore.sha256(bytes), bytes.length);
    }
}
