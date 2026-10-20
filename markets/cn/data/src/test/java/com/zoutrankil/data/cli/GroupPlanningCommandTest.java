package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncGroupDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.StockBasicGroupService;
import com.zoutrankil.data.service.StockBasicScheduleService;
import com.zoutrankil.data.service.StockBasicWriteGroupService;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;
import com.zoutrankil.data.stock.application.StockBasicSyncService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GroupPlanningCommandTest {
    @Test
    void planOutputsFrozenChildButNeverCallsRunner() throws Exception {
        var job = StockBasicSyncAdapter.definition(true);
        var registry =
                new SyncJobRegistry(
                        List.of(job),
                        new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                        Map.of(job.datasetId(), job.supportedModes()),
                        new SyncJobRegistry.Policies(
                                Set.of(job.ratePolicyRef()),
                                Set.of(job.slicePolicyRef()),
                                Set.of(job.verificationPolicyRef())));
        var group = mock(StockBasicGroupService.class);
        when(group.definitions())
                .thenReturn(
                        List.of(
                                new SyncGroupDefinition(
                                        "group.stock_basic_manual",
                                        1,
                                        List.of(
                                                new SyncGroupDefinition.Member(
                                                        new SyncJobDefinition.JobRef(
                                                                job.jobId(), job.version()),
                                                        List.of())),
                                        true,
                                        false)));
        var single = mock(StockBasicJobService.class);
        var cli =
                new CommandLineRunner(
                        mock(StockBasicSyncService.class),
                        mock(DatasetRegistry.class),
                        registry,
                        single,
                        group,
                        mock(ReadGroupReader.class),
                        mock(StockBasicWriteGroupService.class),
                        mock(StockBasicScheduleService.class));
        var output = new ByteArrayOutputStream();
        var original = System.out;
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            cli.run(
                    new DefaultApplicationArguments(
                            "plan-sync-group",
                            "--group",
                            "group.stock_basic_manual",
                            "--version",
                            "1",
                            "--logical-date",
                            "2026-09-29",
                            "--parameters",
                            "{\"codes\":[\"000001.SZ\"]}"));
        } finally {
            System.setOut(original);
        }
        var json = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
        assertEquals("PLANNED", json.required("status").asText());
        assertFalse(json.required("executed").asBoolean());
        assertFalse(json.required("dataVerified").asBoolean());
        assertEquals(
                "data.stock_basic",
                json.required("requests").get(0).required("definition").required("jobId").asText());
        verifyNoInteractions(single);
        verify(group, never()).run(anyList(), any(), any());
    }
}
