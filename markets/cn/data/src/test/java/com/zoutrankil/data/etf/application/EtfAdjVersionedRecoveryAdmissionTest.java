package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.repository.SyncRunLedger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

class EtfAdjVersionedRecoveryAdmissionTest {
    @TempDir Path root;

    @Test
    void originalV2DefinitionIsRecognizedButMissingOriginalLeaseStillBlocksRecovery()
            throws Exception {
        String id = "etf-adj-402336a7-7513-456f-a96d-335ff6d5cddc";
        String identity = "static-v2-" + "a".repeat(64);
        var day = LocalDate.of(2026, 9, 28);
        var request =
                EtfAdjSyncJobOwner.definition(2)
                        .freeze(
                                Mode.BACKFILL,
                                Map.of("targetId", identity, "trade_dates", "NONE"),
                                day,
                                day,
                                day);
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(id, null, identity, request);
        ledger.transition(id, 0, SyncRunState.RUNNING, "{}");
        ledger.transition(id, 1, SyncRunState.IN_DOUBT, "{}");
        String frozen = ledger.getRun(id).frozenJson();
        var target = mock(EtfAdjTarget.Versioned.class);
        when(target.tableName()).thenReturn("etf_adj");
        when(target.targetId()).thenReturn(identity);
        Path log =
                Files.writeString(
                        root.resolve("writer.log"),
                        "Starting QuestDataApplication using Java 24 with PID 999999999\n"
                                + "etf_adj sync incomplete: IN_DOUBT; run="
                                + id
                                + "\n{\"status\":\"FAILED\",\"exitCode\":3}\n");
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> EtfAdjRunRecovery.reconcile(path, id, log, target));
        assertEquals("Retained uncertain interval required", failure.getMessage());
        assertEquals(SyncRunState.IN_DOUBT, ledger.get(id).state());
        assertEquals(frozen, ledger.getRun(id).frozenJson());
        verify(target, never()).newWriter(any(), anyInt());
    }
}
