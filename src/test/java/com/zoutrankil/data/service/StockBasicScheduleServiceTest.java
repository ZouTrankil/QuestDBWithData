package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncAdapter;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicScheduleServiceTest {
    @TempDir Path temp;
    private final StockBasicJobService job = mock(StockBasicJobService.class);
    private final StockBasicGroupService group = mock(StockBasicGroupService.class);
    private StockBasicScheduleService service() throws Exception {
        var definition = StockBasicSyncAdapter.definition(true);
        var registry = new SyncJobRegistry(List.of(definition),
                new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
                Map.of(definition.datasetId(), definition.supportedModes()),
                new SyncJobRegistry.Policies(Set.of(definition.ratePolicyRef()),
                        Set.of(definition.slicePolicyRef()), Set.of(definition.verificationPolicyRef())));
        when(group.definitions()).thenReturn(List.of(new SyncGroupDefinition("group.stock_basic_manual", 1,
                List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_basic", 2), List.of())),
                true, false)));
        return new StockBasicScheduleService(registry, job, group, temp.resolve("schedule.sqlite").toString(),
                java.time.Clock.fixed(java.time.Instant.parse("2026-09-29T01:30:00Z"), java.time.ZoneOffset.UTC), d -> true);
    }
    private Path request(String extra) throws Exception {
        return Files.writeString(temp.resolve("request.json"), """
                {"scheduleId":"test.schedule","target":"JOB","targetId":"data.stock_basic",
                 "targetVersion":2,"enabled":false,"zone":"Asia/Shanghai","kind":"DAILY",
                 "time":"09:30:00","days":[],"dayRule":"CALENDAR","misfire":"RUN_ONCE",
                 "maxLateness":"PT5M","parameters":{"codes":"000001.SZ"}%s}
                """.formatted(extra));
    }
    @Test void importedDisabledDefinitionPersistsWithoutRunningAndEnableIsExplicit() throws Exception {
        var service = service();
        service.put(request(""));
        assertFalse(service.status("test.schedule").definition().enabled());
        assertTrue(service.status("test.schedule").history().isEmpty());
        assertTrue(service.tick().isEmpty());
        verifyNoInteractions(job);
        var reopened = service();
        assertFalse(reopened.status("test.schedule").definition().enabled());
        reopened.setEnabled("test.schedule", true);
        assertTrue(reopened.status("test.schedule").definition().enabled());
        reopened.setEnabled("test.schedule", false);
        assertTrue(reopened.tick().isEmpty());
        verifyNoInteractions(job);
    }
    @Test void jobAndGroupSchedulesRouteToExistingServicesAndRetainRunIds() throws Exception {
        var service = service();
        service.put(request(""));
        service.setEnabled("test.schedule", true);
        var day = java.time.LocalDate.of(2026,9,29);
        when(job.run(List.of("000001.SZ"), day))
                .thenReturn(new SyncJobRunner.Result("job-run", SyncRunState.VERIFIED, 1, 1, null));
        assertEquals("job-run", service.tick().getFirst().runId());
        service.setEnabled("test.schedule", false);
        var path = request("");
        Files.writeString(path, Files.readString(path).replace("test.schedule", "test.group")
                .replace("\"target\":\"JOB\"", "\"target\":\"GROUP\"")
                .replace("data.stock_basic", "group.stock_basic_manual").replace("\"targetVersion\":2", "\"targetVersion\":1"));
        service.put(path);
        service.setEnabled("test.group", true);
        when(group.run(List.of("000001.SZ"), day, null))
                .thenReturn(new SyncGroupRunner.Result("group-run", SyncRunState.PARTIAL, List.of()));
        var result = service.tick().getFirst();
        assertEquals("group-run", result.runId());
        assertEquals(com.zoutrankil.data.service.StockBasicScheduleService.State.PARTIAL, result.state());
        service.tick();
        verify(job, times(1)).run(List.of("000001.SZ"), day);
        verify(group, times(1)).run(List.of("000001.SZ"), day, null);
    }
    @Test void publicScheduleResponsesSerializeExactlyLikeStoredHistory() throws Exception {
        var service = service();
        service.put(request(""));
        service.setEnabled("test.schedule", true);
        when(job.run(List.of("000001.SZ"), java.time.LocalDate.of(2026, 9, 29)))
                .thenReturn(new SyncJobRunner.Result("job-run", SyncRunState.IN_DOUBT, 0, 0, null));
        var tick = service.tick();
        var store = new com.zoutrankil.data.repository.SyncScheduleStore(temp.resolve("schedule.sqlite"));
        var stored = store.history("test.schedule", 100);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertEquals(json.writeValueAsString(stored), json.writeValueAsString(tick));
        var status = service.status("test.schedule");
        var oldStatus = new SyncScheduleManager.Status(status.definition(), status.next(), stored);
        assertEquals(json.writeValueAsString(oldStatus), json.writeValueAsString(status));
    }
    @Test void malformedOrUnsupportedConfigurationNeverCreatesSchedule() throws Exception {
        var service = service();
        assertThrows(Exception.class, () -> service.put(request(",\"enabled\":true")));
        assertThrows(Exception.class, () -> service.put(request(",\"unexpected\":1")));
        var path = request("");
        Files.writeString(path, Files.readString(path).replace("CALENDAR", "EXCHANGE_SESSION"));
        final Path calendar = path;
        assertThrows(IllegalArgumentException.class, () -> service.put(calendar));
        path = request("");
        Files.writeString(path, Files.readString(path).replace("000001.SZ", "000001.SZ,000001.SZ"));
        final Path duplicate = path;
        assertThrows(IllegalArgumentException.class, () -> service.put(duplicate));
        assertThrows(IllegalArgumentException.class, () -> service.status("test.schedule"));
        verifyNoInteractions(job);
    }
}
