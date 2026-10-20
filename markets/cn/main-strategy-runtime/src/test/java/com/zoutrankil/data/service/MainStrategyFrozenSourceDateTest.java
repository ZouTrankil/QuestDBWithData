package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.MainStrategyDailyWork;
import com.zoutrankil.data.domain.JobDefinitionJson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class MainStrategyFrozenSourceDateTest {
    @TempDir Path root;
    private static final LocalDate TARGET = LocalDate.parse("2026-09-28");

    private long micros(LocalDate day) {
        return Math.multiplyExact(day.toEpochDay(), 86_400_000_000L);
    }

    private RunRequest request() {
        return new RunRequest(
                "frozen-date",
                MainStrategyDailyWork.JOB,
                TARGET,
                TARGET,
                TARGET,
                MainStrategyDailyWork.DEFINITION_VERSION,
                "0",
                null,
                null,
                "frozen-input",
                "sse-fixture-v1",
                "Asia/Shanghai",
                Instant.parse("2026-09-28T13:00:00Z"),
                Instant.parse("2026-09-28T13:00:01Z"));
    }

    private void field(Object target, String name, Object value) throws Exception {
        var field = MainStrategyDailyPipeline.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private MainStrategyDailyPipeline pipeline(JdbcTemplate jdbc) throws Exception {
        // 隔离旧构造器中的真实依赖，实际执行原来的计划读写与恢复方法，不启动外部服务。
        var value = mock(MainStrategyDailyPipeline.class, CALLS_REAL_METHODS);
        field(value, "root", root);
        field(value, "jdbc", jdbc);
        field(value, "json", JobDefinitionJson.mapper());
        when(jdbc.queryForList(anyString()))
                .thenReturn(
                        List.of(
                                Map.of("column", "trade_date", "type", "TIMESTAMP"),
                                Map.of("column", "timestamp", "type", "TIMESTAMP")));
        doAnswer(
                        call -> {
                            RowCallbackHandler rows = call.getArgument(1);
                            long from = call.getArgument(2);
                            long end = call.getArgument(3);
                            for (long cursor = from; cursor < end; cursor += 86_400_000_000L) {
                                var day = LocalDate.ofEpochDay(cursor / 86_400_000_000L);
                                var rs = mock(ResultSet.class);
                                when(rs.getObject(1)).thenReturn(cursor);
                                when(rs.getObject(2))
                                        .thenReturn(
                                                day.getDayOfWeek() == DayOfWeek.SATURDAY
                                                                || day.getDayOfWeek()
                                                                        == DayOfWeek.SUNDAY
                                                        ? 0
                                                        : 1);
                                String sql = call.getArgument(0);
                                if (sql.contains("exchange_calendar")
                                        || (day.getDayOfWeek() != DayOfWeek.SATURDAY
                                                && day.getDayOfWeek() != DayOfWeek.SUNDAY))
                                    rows.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), anyLong(), anyLong());
        return value;
    }

    private Object invoke(
            MainStrategyDailyPipeline pipeline, String name, Class<?>[] types, Object... args)
            throws Exception {
        var method = MainStrategyDailyPipeline.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(pipeline, args);
    }

    private Path save(MainStrategyDailyPipeline.PipelinePlan plan) throws Exception {
        Path file = root.resolve(plan.instance()).resolve("plan.json");
        Files.createDirectories(file.getParent());
        JobDefinitionJson.mapper().writeValue(file.toFile(), plan);
        return file;
    }

    @Test
    void newPlanUsesTheRequestedDateInsteadOfTheMachinesCurrentDate() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong()))
                .thenReturn(micros(TARGET));
        var pipeline = pipeline(jdbc);
        var request = request();
        var plan =
                (MainStrategyDailyPipeline.PipelinePlan)
                        invoke(pipeline, "plan", new Class<?>[] {RunRequest.class}, request);
        assertEquals(TARGET, plan.sourceLogicalDate());
        assertEquals(List.of(TARGET), plan.tradeDates());
        assertTrue(Files.isRegularFile(root.resolve(request.instanceId()).resolve("plan.json")));
    }

    @Test
    void etfLagAndInteriorHoleExtendThePlanEvenWhenStockMaximumIsCurrent() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var pipeline = pipeline(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong()))
                .thenReturn(micros(TARGET));
        doAnswer(
                        call -> {
                            RowCallbackHandler rows = call.getArgument(1);
                            long from = call.getArgument(2), end = call.getArgument(3);
                            String sql = call.getArgument(0);
                            for (long cursor = from; cursor < end; cursor += 86_400_000_000L) {
                                var day = LocalDate.ofEpochDay(cursor / 86_400_000_000L);
                                boolean open =
                                        day.getDayOfWeek() != DayOfWeek.SATURDAY
                                                && day.getDayOfWeek() != DayOfWeek.SUNDAY;
                                if (!sql.contains("exchange_calendar")
                                        && (!open
                                                || (sql.contains("\"etf_daily\"")
                                                        && day.equals(TARGET.minusDays(7)))
                                                || (sql.contains("\"moneyflow\"")
                                                        && day.equals(TARGET.minusDays(10)))))
                                    continue;
                                var rs = mock(ResultSet.class);
                                when(rs.getObject(1)).thenReturn(cursor);
                                when(rs.getObject(2)).thenReturn(open ? 1 : 0);
                                rows.processRow(rs);
                            }
                            return null;
                        })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), anyLong(), anyLong());
        var plan =
                (MainStrategyDailyPipeline.PipelinePlan)
                        invoke(pipeline, "plan", new Class<?>[] {RunRequest.class}, request());
        assertEquals(TARGET.minusDays(10), plan.from());
        assertEquals(TARGET, plan.to());
    }

    @Test
    void etfWithoutBaselineOrWithExcessiveLagBlocksBeforeSavingPlan() throws Exception {
        for (Long latest : Arrays.asList(null, micros(TARGET.minusDays(40)))) {
            var jdbc = mock(JdbcTemplate.class);
            var pipeline = pipeline(jdbc);
            when(jdbc.queryForObject(anyString(), eq(Long.class), anyLong()))
                    .thenReturn(micros(TARGET));
            when(jdbc.queryForObject(contains("FROM \"etf_daily\""), eq(Long.class), anyLong()))
                    .thenReturn(latest);
            var failure =
                    assertThrows(
                            InvocationTargetException.class,
                            () ->
                                    invoke(
                                            pipeline,
                                            "plan",
                                            new Class<?>[] {RunRequest.class},
                                            request()));
            assertTrue(failure.getCause().getMessage().contains("etf_daily"));
            assertFalse(Files.exists(root.resolve(request().instanceId()).resolve("plan.json")));
        }
    }

    @Test
    void retryPassesTheSavedSourceDateToTheOwnerWithoutRewritingThePlan() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var pipeline = pipeline(jdbc);
        var request = request();
        var saved =
                new MainStrategyDailyPipeline.PipelinePlan(
                        request.instanceId(),
                        request.inputFingerprint(),
                        TARGET,
                        TARGET,
                        TARGET.plusDays(1),
                        List.of(TARGET));
        var file = save(saved);
        var bytes = Files.readAllBytes(file);
        var restored =
                (MainStrategyDailyPipeline.PipelinePlan)
                        invoke(pipeline, "plan", new Class<?>[] {RunRequest.class}, request);
        Class<?> plannerType =
                Class.forName(MainStrategyDailyPipeline.class.getName() + "$Planner");
        Class<?> ownerType = Class.forName(MainStrategyDailyPipeline.class.getName() + "$Owner");
        var observed = new AtomicReference<LocalDate>();
        Object planner =
                Proxy.newProxyInstance(
                        plannerType.getClassLoader(),
                        new Class<?>[] {plannerType},
                        (proxy, method, args) -> {
                            observed.set((LocalDate) args[2]);
                            throw new IllegalArgumentException("stop-before-source-write");
                        });
        var ctor = ownerType.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object owner =
                ctor.newInstance(
                        "daily",
                        "trade_date",
                        List.of("trade_date", "ts_code"),
                        false,
                        false,
                        (Supplier<String>) () -> "daily",
                        planner);
        field(pipeline, "owners", List.of(owner));
        assertThrows(
                InvocationTargetException.class,
                () ->
                        invoke(
                                pipeline,
                                "sources",
                                new Class<?>[] {
                                    RunRequest.class, MainStrategyDailyPipeline.PipelinePlan.class
                                },
                                request,
                                restored));
        assertEquals(saved.sourceLogicalDate(), observed.get());
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertFalse(
                Files.exists(
                        file.getParent()
                                .resolve(
                                        "sources/daily/"
                                                + TARGET
                                                + "_"
                                                + TARGET
                                                + "/started.json")));
    }

    @Test
    void missingFrozenSourceDateBlocksBeforeAnyDatabaseAccess() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var pipeline = pipeline(jdbc);
        var request = request();
        save(
                new MainStrategyDailyPipeline.PipelinePlan(
                        request.instanceId(),
                        request.inputFingerprint(),
                        TARGET,
                        TARGET,
                        null,
                        List.of(TARGET)));
        var failure =
                assertThrows(
                        InvocationTargetException.class,
                        () -> invoke(pipeline, "plan", new Class<?>[] {RunRequest.class}, request));
        assertTrue(
                failure.getCause().getMessage().contains("Frozen bounded pipeline plan changed"));
        verifyNoInteractions(jdbc);
    }
}
