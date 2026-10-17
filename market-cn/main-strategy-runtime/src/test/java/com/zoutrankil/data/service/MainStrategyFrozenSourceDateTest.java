package com.zoutrankil.data.service;

import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.MainStrategyDailyWork;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MainStrategyFrozenSourceDateTest {
    @TempDir Path root;
    private static final LocalDate TARGET=LocalDate.parse("2026-09-28");
    private long micros(LocalDate day) { return Math.multiplyExact(day.toEpochDay(),86_400_000_000L); }
    private RunRequest request() {
        return new RunRequest("frozen-date",MainStrategyDailyWork.JOB,TARGET,TARGET,TARGET,
                MainStrategyDailyWork.DEFINITION_VERSION,"0",null,null,"frozen-input","sse-fixture-v1","Asia/Shanghai",
                Instant.parse("2026-09-28T13:00:00Z"),Instant.parse("2026-09-28T13:00:01Z"));
    }
    private void field(Object target,String name,Object value) throws Exception {
        var field=MainStrategyDailyPipeline.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private MainStrategyDailyPipeline pipeline(JdbcTemplate jdbc) throws Exception {
        // 隔离旧构造器中的真实依赖，实际执行原来的计划读写与恢复方法，不启动外部服务。
        var value=mock(MainStrategyDailyPipeline.class,CALLS_REAL_METHODS);
        field(value,"root",root);field(value,"jdbc",jdbc);field(value,"json",JobDefinitionJson.mapper());
        doAnswer(call->{
            RowCallbackHandler rows=call.getArgument(1);
            long from=call.getArgument(2);long end=call.getArgument(3);
            for (long cursor=from;cursor<end;cursor+=86_400_000_000L) {
                var day=LocalDate.ofEpochDay(cursor/86_400_000_000L);var rs=mock(ResultSet.class);
                when(rs.getObject(1)).thenReturn(cursor);
                when(rs.getObject(2)).thenReturn(day.getDayOfWeek()==DayOfWeek.SATURDAY||day.getDayOfWeek()==DayOfWeek.SUNDAY?0:1);
                rows.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(),any(RowCallbackHandler.class),anyLong(),anyLong());
        return value;
    }
    private Object invoke(MainStrategyDailyPipeline pipeline,String name,Class<?>[] types,Object... args) throws Exception {
        var method=MainStrategyDailyPipeline.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(pipeline,args);
    }
    private Path save(MainStrategyDailyPipeline.PipelinePlan plan) throws Exception {
        Path file=root.resolve(plan.instance()).resolve("plan.json");Files.createDirectories(file.getParent());
        JobDefinitionJson.mapper().writeValue(file.toFile(),plan);return file;
    }
    @Test void newPlanUsesTheRequestedDateInsteadOfTheMachinesCurrentDate() throws Exception {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(micros(TARGET));
        var pipeline=pipeline(jdbc);var request=request();
        var plan=(MainStrategyDailyPipeline.PipelinePlan)invoke(pipeline,"plan",new Class<?>[]{RunRequest.class},request);
        assertEquals(TARGET,plan.sourceLogicalDate());
        assertEquals(List.of(TARGET),plan.tradeDates());
        assertTrue(Files.isRegularFile(root.resolve(request.instanceId()).resolve("plan.json")));
    }
    @Test void retryPassesTheSavedSourceDateToTheOwnerWithoutRewritingThePlan() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var pipeline=pipeline(jdbc);var request=request();
        var saved=new MainStrategyDailyPipeline.PipelinePlan(request.instanceId(),request.inputFingerprint(),TARGET,TARGET,TARGET.plusDays(1),List.of(TARGET));
        var file=save(saved);var bytes=Files.readAllBytes(file);
        var restored=(MainStrategyDailyPipeline.PipelinePlan)invoke(pipeline,"plan",new Class<?>[]{RunRequest.class},request);
        Class<?> plannerType=Class.forName(MainStrategyDailyPipeline.class.getName()+"$Planner");
        Class<?> ownerType=Class.forName(MainStrategyDailyPipeline.class.getName()+"$Owner");
        var observed=new AtomicReference<LocalDate>();
        Object planner=Proxy.newProxyInstance(plannerType.getClassLoader(),new Class<?>[]{plannerType},(proxy,method,args)->{
            observed.set((LocalDate)args[2]);throw new IllegalArgumentException("stop-before-source-write");
        });
        var ctor=ownerType.getDeclaredConstructors()[0];ctor.setAccessible(true);
        Object owner=ctor.newInstance("daily","trade_date",List.of("trade_date","ts_code"),false,false,(Supplier<String>)()->"daily",planner);
        field(pipeline,"owners",List.of(owner));
        assertThrows(InvocationTargetException.class,()->invoke(pipeline,"sources",new Class<?>[]{RunRequest.class,MainStrategyDailyPipeline.PipelinePlan.class},request,restored));
        assertEquals(saved.sourceLogicalDate(),observed.get());assertArrayEquals(bytes,Files.readAllBytes(file));
        assertFalse(Files.exists(file.getParent().resolve("sources/daily/"+TARGET+"_"+TARGET+"/started.json")));
    }
    @Test void missingFrozenSourceDateBlocksBeforeAnyDatabaseAccess() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var pipeline=pipeline(jdbc);var request=request();
        save(new MainStrategyDailyPipeline.PipelinePlan(request.instanceId(),request.inputFingerprint(),TARGET,TARGET,null,List.of(TARGET)));
        var failure=assertThrows(InvocationTargetException.class,()->invoke(pipeline,"plan",new Class<?>[]{RunRequest.class},request));
        assertTrue(failure.getCause().getMessage().contains("Frozen bounded pipeline plan changed"));
        verifyNoInteractions(jdbc);
    }
}
