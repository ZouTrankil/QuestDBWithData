package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.derived.application.MarketSentimentDailyJobService;
import com.zoutrankil.data.derived.application.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.margin.application.MarginDetailJobService;
import com.zoutrankil.data.service.LedgerManagementService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RemoteCommandMergeTest {
    @Test
    void marginResumeRejectsUnfrozenOptionsBeforeOwnerIo() {
        var owner = mock(MarginDetailJobService.class);
        var family = new FlowCommands(null, null, null, owner);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        family.execute(
                                "run-margin-detail-job",
                                Map.of("--resume-from", "run", "--from", "2026-01-01")));
        assertThrows(
                IllegalArgumentException.class,
                () -> family.execute("plan-margin-detail-job", Map.of("--resume-from", "run")));
        verifyNoInteractions(owner);
    }

    @Test
    void bothMaterializationFamiliesRequireExplicitWriterStoppedConfirmation() {
        var market = mock(MarketSentimentDailyJobService.class);
        var regime = mock(RegimeFeaturesMonitorDailyJobService.class);
        var family = new MaterializationCommands(null, null, null, null, market, regime);
        for (String command :
                List.of(
                        "finish-market-sentiment-publication",
                        "finish-regime-monitor-publication")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> family.execute(command, Map.of("--run", "run")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            family.execute(
                                    command, Map.of("--run", "run", "--writer-stopped", "false")));
        }
        verifyNoInteractions(market, regime);
    }

    @Test
    void statusAcceptsExactlyTheRunFlagBeforeReadingStorage() {
        var service = mock(LedgerManagementService.class);
        var family = new LedgerCommands(service);
        assertThrows(
                IllegalArgumentException.class, () -> family.execute("sync-run-status", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        family.execute(
                                "sync-run-status",
                                Map.of("--run", "run", "--ledger", "somewhere")));
        verifyNoInteractions(service);
    }
}
