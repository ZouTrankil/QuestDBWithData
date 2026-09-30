package com.zoutrankil.data.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GroupPlanningCommandTest {
    @Test void planOutputsFrozenChildButNeverCallsRunner() throws Exception {
        var job=StockBasicSyncAdapter.definition(true);
        var registry=new SyncJobRegistry(List.of(job),new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of(job.datasetId(),job.supportedModes()),new SyncJobRegistry.Policies(
                Set.of(job.ratePolicyRef()),Set.of(job.slicePolicyRef()),Set.of(job.verificationPolicyRef())));
        var group=mock(StockBasicGroupService.class);
        when(group.definitions()).thenReturn(List.of(new SyncGroupDefinition("group.stock_basic_manual",1,
                List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef(job.jobId(),job.version()),
                        List.of())),true,false)));
        var single=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),registry,
                single,group,mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class),
                mock(StockBasicScheduleService.class));
        var output=new ByteArrayOutputStream();
        var original=System.out;
        try {
            System.setOut(new PrintStream(output,true,StandardCharsets.UTF_8));
            cli.run(new DefaultApplicationArguments("plan-sync-group","--group","group.stock_basic_manual",
                    "--version","1","--logical-date","2026-09-29","--parameters",
                    "{\"codes\":[\"000001.SZ\"]}"));
        } finally { System.setOut(original); }
        var json=new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
        assertEquals("PLANNED",json.required("status").asText());
        assertFalse(json.required("executed").asBoolean());
        assertFalse(json.required("dataVerified").asBoolean());
        assertEquals("data.stock_basic",json.required("requests").get(0).required("definition")
                .required("jobId").asText());
        verifyNoInteractions(single);
        verify(group,never()).run(anyList(),any(),any());
    }
}
