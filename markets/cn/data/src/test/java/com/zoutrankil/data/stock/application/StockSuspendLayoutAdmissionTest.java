package com.zoutrankil.data.stock.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.stock.port.StockSuspendTarget;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

class StockSuspendLayoutAdmissionTest {
    @TempDir Path root;

    @Test
    void recoveryProbeIsReadOnlyAndOwnedAppendOrStageIntentKeepsTheRunUnresolved()
            throws Exception {
        Path path = root.resolve("ledger.sqlite");
        new SyncRunLedger(path);
        var pages = mock(TusharePageService.class);
        var target = mock(StockSuspendTarget.class);
        var writer = mock(StockSuspendWriteSession.class);
        var adapter =
                new StockSuspendSyncAdapter(
                        pages,
                        writer,
                        root.resolve("evidence"),
                        path,
                        "java_d011_stk_suspend_layout",
                        "run",
                        "id",
                        "id",
                        target);
        byte[] before = Files.readAllBytes(path);
        assertFalse(adapter.recoveryRequired("run"));
        assertEquals(
                FileEvidenceStore.sha256(before),
                FileEvidenceStore.sha256(Files.readAllBytes(path)));
        assertFalse(SqliteLedgerSchema.tableNames(path).contains("reference_publications"));
        Files.createDirectories(root.resolve("evidence/publication"));
        Path stage =
                root.resolve(
                        "evidence/publication/java_stk_suspend_stage_0123456789abcdef0123456789abcdef-intent.json");
        Files.writeString(stage, "{}");
        assertTrue(adapter.recoveryRequired("run"));
        Files.delete(stage);
        Files.writeString(root.resolve("evidence/append-intent.json"), "{}");
        assertTrue(adapter.recoveryRequired("run"));
        assertThrows(IllegalArgumentException.class, () -> adapter.recoveryRequired("other"));
        verifyNoInteractions(pages, target, writer);
    }

    @Test
    void oldRequestRejectsNewPhysicalSessionBeforeAnyPreflightSourceOrPublication() {
        String id = "static-v2-" + "a".repeat(64);
        var writer = mock(StockSuspendWriteSession.Versioned.class);
        when(writer.datasetVersion()).thenReturn(2);
        var pages = mock(TusharePageService.class);
        var target = mock(StockSuspendTarget.class);
        var adapter =
                new StockSuspendSyncAdapter(
                        pages,
                        writer,
                        root.resolve("evidence"),
                        root.resolve("ledger.sqlite"),
                        "java_d011_stk_suspend_layout",
                        "run",
                        id,
                        id,
                        target);
        var day = LocalDate.of(2026, 9, 28);
        var request =
                StockSuspendSyncJobOwner.DEFINITION.freeze(
                        Mode.BACKFILL,
                        Map.of("targetId", id, "physicalTargetId", id),
                        day,
                        day,
                        day);
        assertThrows(IllegalArgumentException.class, () -> adapter.preflight(request));
        verify(writer, never()).preflight();
        verifyNoInteractions(pages, target);
    }
}
