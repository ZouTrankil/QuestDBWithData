package com.zoutrankil.batch.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.LaunchService;
import com.zoutrankil.batch.job.MainStrategyDailyWork;
import com.zoutrankil.batch.storage.SqliteLedger;
import com.zoutrankil.data.config.MainStrategyBatchProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.quartz.Scheduler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

class MainStrategyCoordinatorProtocolTest {
    @TempDir Path root;
    private static final LocalDate DAY=LocalDate.parse("2026-09-28");
    private static final Instant AT=Instant.parse("2026-09-28T13:00:00Z");

    private SqliteLedger ledger() {
        var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("batch.sqlite3"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration/batch").load().migrate();
        var ledger=new SqliteLedger(source);
        ledger.installScheduleDefinition(MainStrategyDailyWork.JOB,"0 35 20 * * ?","Asia/Shanghai","exchange_calendar.SSE.v1",true);
        return ledger;
    }

    private void check(boolean legacy) throws Exception {
        var ledger=ledger();
        var work=mock(MainStrategyDailyWork.class);
        var launches=mock(LaunchService.class);
        when(work.definitionVersion()).thenReturn("4");
        when(work.resolveTarget(any(Clock.class),any())).thenReturn(DAY);
        String oldHash=RunRequest.hash("original-v3"), newHash=RunRequest.hash("new-v4-v2");
        when(work.inputFingerprint(DAY,"3")).thenReturn(oldHash);
        when(work.inputFingerprint(DAY,"4")).thenReturn(newHash);
        when(launches.launch(any())).thenReturn(Map.of("business_state","BLOCKED"));
        RunRequest old=null;
        if(legacy) {
            old=new RunRequest("original",MainStrategyDailyWork.JOB,DAY,DAY,DAY,"3","0",null,null,oldHash,"exchange_calendar.SSE.v1","Asia/Shanghai",AT.minusSeconds(1800),AT.minusSeconds(1799));
            ledger.register(old); ledger.state(old.instanceId(),BusinessState.BLOCKED,null,"单元样本未进入写入");
        }
        var properties=new MainStrategyBatchProperties(true,false,root.resolve("batch.sqlite3").toString(),null,null,null,null);
        try(var coordinator=new MainStrategyDailyCoordinator(ledger,launches,work,properties,mock(Scheduler.class),Clock.fixed(AT,ZoneOffset.UTC))) {
            coordinator.probe("manual:protocol",AT,AT,false);
        }
        var captured=ArgumentCaptor.forClass(RunRequest.class);
        verify(launches).launch(captured.capture());
        var request=captured.getValue();
        assertEquals(legacy?"3":"4",request.definitionVersion());
        assertEquals(legacy?oldHash:newHash,request.inputFingerprint());
        if(legacy) assertEquals(old.instanceId(),request.instanceId());
        assertEquals(request.instanceId(),ledger.latestCandidate(MainStrategyDailyWork.JOB,DAY).orElseThrow().request().instanceId());
        verify(work).inputFingerprint(DAY,legacy?"3":"4");
        verify(work,never()).inputFingerprint(DAY);
    }

    @Test void newRequestsPersistTheOwnersExplicitVersion() throws Exception { check(false); }
    @Test void oldBlockedRequestKeepsItsOriginalVersionAndIdentityOnRecovery() throws Exception { check(true); }
}
