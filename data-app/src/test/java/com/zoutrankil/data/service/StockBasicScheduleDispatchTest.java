package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicScheduleDispatchTest {
    @TempDir Path temp;
    @Test void explicitTickCallsExistingJobAndGroupRunnersAndKeepsTheirOutcomes() throws Exception {
        var definition=StockBasicSyncAdapter.definition(true);
        var jobs=new SyncJobRegistry(List.of(definition),
                new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of(definition.datasetId(),definition.supportedModes()),
                new SyncJobRegistry.Policies(Set.of(definition.ratePolicyRef()),
                        Set.of(definition.slicePolicyRef()),Set.of(definition.verificationPolicyRef())));
        var job=mock(StockBasicJobService.class);
        var group=mock(StockBasicGroupService.class);
        when(group.definitions()).thenReturn(List.of(new SyncGroupDefinition("group.stock_basic_manual",1,
                List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_basic",2),List.of())),
                true,false)));
        var date=LocalDate.of(2026,9,29);
        when(job.run(List.of("000001.SZ"),date)).thenReturn(new SyncJobRunner.Result("job-run",SyncRunState.VERIFIED,1,1,null));
        when(group.run(List.of("600000.SH"),date,null)).thenReturn(new SyncGroupRunner.Result("group-run",
                SyncRunState.PARTIAL,List.of()));
        var service=new StockBasicScheduleService(jobs,job,group,temp.resolve("schedule.sqlite").toString(),
                Clock.fixed(Instant.parse("2026-09-29T01:30:00Z"),ZoneOffset.UTC),d -> false);
        var first=Files.writeString(temp.resolve("job.json"),"""
                {"scheduleId":"job.schedule","target":"JOB","targetId":"data.stock_basic","targetVersion":2,
                 "enabled":true,"zone":"Asia/Shanghai","kind":"DAILY","time":"09:30:00","days":[],
                 "dayRule":"WEEKDAY","misfire":"RUN_ONCE","maxLateness":"PT5M",
                 "parameters":{"codes":"000001.SZ"}}
                """);
        var second=Files.writeString(temp.resolve("group.json"),"""
                {"scheduleId":"group.schedule","target":"GROUP","targetId":"group.stock_basic_manual","targetVersion":1,
                 "enabled":true,"zone":"Asia/Shanghai","kind":"DAILY","time":"09:30:00","days":[],
                 "dayRule":"WEEKDAY","misfire":"RUN_ONCE","maxLateness":"PT5M",
                 "parameters":{"codes":"600000.SH"}}
                """);
        service.put(first); service.put(second);
        var histories=service.tick();
        assertEquals(2,histories.size());
        assertEquals("job-run",service.status("job.schedule").history().getFirst().runId());
        assertEquals("group-run",service.status("group.schedule").history().getFirst().runId());
        assertEquals(com.zoutrankil.data.service.StockBasicScheduleService.State.PARTIAL,
                service.status("group.schedule").history().getFirst().state());
        service.tick();
        verify(job,times(1)).run(List.of("000001.SZ"),date);
        verify(group,times(1)).run(List.of("600000.SH"),date,null);
    }
}
