package com.zoutrankil.batch;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LaunchServiceRecoveryTriggerTest {
    @TempDir Path temp;

    @Test void preservesEachNewTriggerIdWhileKeepingTheCanonicalBusinessInput() throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("metadata.sqlite"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/batch").load().migrate();
        var ledger=new SqliteLedger(dataSource);var job=mock(Job.class);when(job.getName()).thenReturn("post_close");
        var fanout=mock(CoreSourceFanout.class);
        when(fanout.execute(any(),any())).thenAnswer(call->{
            RunRequest current=call.getArgument(0);
            ledger.stage(current,"DataReady",BusinessState.BLOCKED,null,"fixture-source-blocked");
            ledger.state(current.instanceId(),BusinessState.BLOCKED,null,"fixture-source-blocked");
            return new CoreSourceFanout.Result(false,BusinessState.BLOCKED,"fixture-source-blocked");
        });
        var launches=new LaunchService(ledger,mock(JobOperator.class),List.of(job),null,fanout);
        Instant at=Instant.parse("2026-09-29T10:30:00Z");
        String plan=RunRequest.hash("frozen-plan");
        var first=new RunRequest("original-trigger","post_close",LocalDate.of(2026,9,29),LocalDate.of(2026,9,29),LocalDate.of(2026,9,29),
                "post-close-v1","0",null,null,plan,"calendar-v1","Asia/Shanghai",at,at,plan);
        launches.launch(first);
        var retry=new RunRequest("recovery-trigger-2",first.job(),first.logicalDate(),first.rangeStart(),first.rangeEnd(),
                first.definitionVersion(),first.revision(),first.supersedes(),first.revisionReason(),first.inputFingerprint(),
                first.calendarVersion(),first.zone(),at.plusSeconds(900),at.plusSeconds(900),first.scopeIdentity());
        launches.launch(retry);
        assertEquals(first.instanceId(),retry.instanceId());
        var captor=org.mockito.ArgumentCaptor.forClass(RunRequest.class);
        verify(fanout,times(2)).execute(captor.capture(),any());
        assertEquals(List.of(first.requestId(),retry.requestId()),captor.getAllValues().stream().map(RunRequest::requestId).toList());
        assertEquals(first.inputIdentity(),captor.getAllValues().getLast().inputIdentity());
        assertEquals(first.requestId(),ledger.request(first.instanceId()).requestId());
        assertEquals(2,ledger.detail(first.instanceId()).get("triggers") instanceof List<?> triggers?triggers.size():-1);
    }
}
