package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.sql.DataSource;

class BacktestDailyWindowVerifierTest {
    private static final BacktestDailyScope SCOPE = BacktestDailyScope.LEGACY;
    private static final LocalDate FROM = LocalDate.of(2026, 10, 14), TO = FROM.plusDays(1);
    private List<Map<String, Object>> expected = List.of(row(FROM)), actual = List.of(row(FROM));
    private BacktestDailyMaterializedViewPort.State state = state(1);
    private final List<List<Object>> queries = new ArrayList<>();
    private boolean changeDuringRead, dateFromParameters;

    private static BacktestDailyMaterializedViewPort.State state(long transaction) {
        return new BacktestDailyMaterializedViewPort.State(
                Map.of("finished", transaction),
                Map.of("source", Map.of("id", 1, "txn", transaction)));
    }

    private static Map<String, Object> row(LocalDate date) {
        var row = new LinkedHashMap<String, Object>();
        row.put("trade_date", date.toEpochDay() * 86_400_000_000L);
        row.put("ts_code", "000001.SZ");
        for (var field :
                List.of(
                        "open",
                        "high",
                        "low",
                        "close",
                        "vol",
                        "amount",
                        "adj_factor",
                        "up_limit",
                        "down_limit")) row.put(field, 10.0);
        row.put("is_suspended", 0L);
        row.put("is_st", 0);
        return row;
    }

    private void fixture(BiConsumer<BacktestDailyWindowVerifier, JdbcTemplate> test) {
        var source = mock(JdbcTemplate.class);
        var dataSource = mock(DataSource.class);
        when(source.getDataSource()).thenReturn(dataSource);
        try (var statements =
                        mockConstruction(
                                JdbcTemplate.class,
                                (jdbc, context) -> {
                                    when(jdbc.getDataSource()).thenReturn(dataSource);
                                    when(jdbc.queryForList(anyString(), anyLong(), anyLong()))
                                            .thenAnswer(
                                                    call -> {
                                                        String sql = call.getArgument(0);
                                                        long from = call.getArgument(1),
                                                                to = call.getArgument(2);
                                                        queries.add(List.of(sql, from, to));
                                                        if (changeDuringRead) state = state(2);
                                                        if (dateFromParameters)
                                                            return List.of(
                                                                    row(
                                                                            LocalDate.ofEpochDay(
                                                                                    from
                                                                                            / 86_400_000_000L)));
                                                        return sql.contains(" FROM (")
                                                                ? expected
                                                                : actual;
                                                    });
                                });
                var ports =
                        mockConstruction(
                                BacktestDailyMaterializedViewPort.class,
                                (port, context) -> {
                                    when(port.sourceState()).thenAnswer(call -> state);
                                    when(port.fingerprint(any()))
                                            .thenReturn("same-original-fingerprint");
                                })) {
            test.accept(
                    new BacktestDailyWindowVerifier(source), statements.constructed().getFirst());
            statements.constructed().forEach(jdbc -> verify(jdbc, never()).execute(anyString()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "open",
                "high",
                "low",
                "close",
                "vol",
                "amount",
                "adj_factor",
                "up_limit",
                "down_limit",
                "is_suspended",
                "is_st"
            })
    void anyChangedValueFailsEvenWhenNativeWatermarksAreUnchanged(String field) {
        var changed = row(FROM);
        changed.put(
                field,
                field.equals("is_suspended")
                        ? (Object) 1L
                        : field.equals("is_st") ? (Object) 1 : 20.0);
        expected = List.of(changed);
        fixture(
                (verifier, jdbc) -> {
                    var error =
                            assertThrows(
                                    BacktestDailyMaterializationReadGuard.NotReadyException.class,
                                    () -> verifier.verifyRange(SCOPE, FROM, TO));
                    assertEquals(
                            BacktestDailyMaterializationReadGuard.NotReadyException.Reason
                                    .VALUES_CHANGED,
                            error.reason());
                    actual = expected;
                    assertEquals(state, verifier.verifyRange(SCOPE, FROM, TO));
                    assertEquals(4, queries.size(), "失败不能缓存为已核验");
                    verifier.verifyRange(SCOPE, FROM, TO);
                    assertEquals(4, queries.size(), "同一数据源、窗口和完整版本可复用正值核验");
                });
    }

    @Test
    void missingExtraAndNullRowsCannotBeHiddenByIdenticalCounters() {
        fixture(
                (verifier, jdbc) -> {
                    actual = List.of();
                    assertThrows(
                            BacktestDailyMaterializationReadGuard.NotReadyException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                    expected = List.of();
                    actual = List.of(row(FROM));
                    assertThrows(
                            BacktestDailyMaterializationReadGuard.NotReadyException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                    expected = List.of(row(FROM));
                    var nullRow = row(FROM);
                    nullRow.put("close", null);
                    actual = List.of(nullRow);
                    assertThrows(
                            BacktestDailyMaterializationReadGuard.NotReadyException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                });
    }

    @Test
    void currentVersionMustRemainStableAndOldProofCannotCoverAnotherGeneration() {
        fixture(
                (verifier, jdbc) -> {
                    changeDuringRead = true;
                    assertThrows(
                            IllegalStateException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                    changeDuringRead = false;
                    verifier.verifyRange(SCOPE, FROM, TO);
                    assertEquals(4, queries.size());
                    state = state(3);
                    expected = List.of();
                    assertThrows(
                            BacktestDailyMaterializationReadGuard.NotReadyException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                    assertEquals(6, queries.size());
                });
    }

    @Test
    void monthlyBoundsKeepTheAsofHistoryAndTheOriginalThirteenColumns() {
        dateFromParameters = true;
        fixture(
                (verifier, jdbc) -> {
                    verifier.verifyRange(
                            SCOPE, LocalDate.of(2026, 1, 30), LocalDate.of(2026, 3, 2));
                    assertEquals(6, queries.size());
                    for (int i = 0; i < queries.size(); i += 2) {
                        String sql = queries.get(i).getFirst().toString();
                        assertTrue(sql.contains("ASOF JOIN stk_factor f"));
                        assertTrue(
                                sql.contains(
                                        "FROM stk_limit WHERE trade_date>=cast(? AS TIMESTAMP)"));
                        assertTrue(sql.contains("is_suspended,is_st"));
                        assertTrue(sql.endsWith("LIMIT 200001"));
                        assertTrue(
                                (long) queries.get(i).get(2) - (long) queries.get(i).get(1)
                                        <= 31L * 86_400_000_000L);
                    }
                });
    }

    @Test
    void rowBudgetDuplicateKeysAndNonMidnightOrOutOfWindowDatesFailClosed() {
        fixture(
                (verifier, jdbc) -> {
                    for (var bad :
                            List.of(
                                    List.of(row(FROM), row(FROM)),
                                    List.of(row(FROM.minusDays(1))),
                                    Collections.nCopies(200001, row(FROM)))) {
                        expected = bad;
                        assertThrows(
                                IllegalStateException.class,
                                () -> verifier.verifyRange(SCOPE, FROM, TO));
                    }
                    var intraday = row(FROM);
                    intraday.put("trade_date", FROM.toEpochDay() * 86_400_000_000L + 1);
                    expected = List.of(intraday);
                    assertThrows(
                            IllegalStateException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, TO));
                });
    }

    @Test
    void queryDatesAreRequiredAndTheVersionStringRetainsItsOriginalEncoding() {
        fixture(
                (verifier, jdbc) -> {
                    var columns = SCOPE.definition().storageColumns();
                    var valid =
                            new DatasetReadQuery(
                                    columns,
                                    Map.of("trade_date", FROM),
                                    null,
                                    null,
                                    null,
                                    10,
                                    null);
                    assertEquals("same-original-fingerprint", verifier.version(SCOPE, valid));
                    var invalid =
                            new DatasetReadQuery(columns, Map.of(), null, null, null, 10, null);
                    assertThrows(
                            IllegalArgumentException.class, () -> verifier.version(SCOPE, invalid));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> verifier.verifyRange(SCOPE, TO, FROM));
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> verifier.verifyRange(SCOPE, FROM, FROM.plusYears(101)));
                });
    }
}
