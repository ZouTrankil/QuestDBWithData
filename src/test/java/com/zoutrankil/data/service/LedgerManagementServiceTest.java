package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LedgerManagementServiceTest {
    @TempDir Path temp;

    private SyncRunLedger ledger(Path path, String target) throws Exception {
        var ledger = new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("shared-run", null, "data.stock_basic", 2,
                "2026-09-29", target, "{}"));
        return ledger;
    }

    @Test void statusProjectsTheRemoteResponseWithoutMutatingTheLedger() throws Exception {
        Path path = temp.resolve("status.sqlite");
        var ledger = ledger(path, "target");
        var before = ledger.get("shared-run");
        var service = new LedgerManagementService(path.toString());
        var result = service.status("shared-run");
        assertEquals(LedgerReadModels.entry(before), result.status());
        assertEquals(ledger.entries("shared-run", null, 100).stream().map(LedgerReadModels::entry).toList(), result.entries());
        assertEquals(before, ledger.get("shared-run"));
        assertThrows(UnsupportedOperationException.class, () -> result.entries().clear());
    }

    @Test void springConfiguredPathIsUsedUnlessAnExplicitLedgerOverridesIt() throws Exception {
        Path configured = temp.resolve("configured.sqlite"), explicit = temp.resolve("explicit.sqlite");
        var configuredLedger = ledger(configured, "configured-target");
        var explicitLedger = ledger(explicit, "explicit-target");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ledger-test",
                    Map.of("app.sync.ledger-path", configured.toString())));
            context.register(LedgerManagementService.class);
            context.refresh();
            var service = context.getBean(LedgerManagementService.class);
            assertEquals("configured-target", service.history(null, null, null, 100).getFirst().targetId());
            assertEquals("configured-target", service.run(null, "shared-run", null, 100).run().targetId());
            assertEquals("explicit-target", service.history(explicit.toString(), null, null, 100).getFirst().targetId());
            assertEquals("explicit-target", service.run(explicit.toString(), "shared-run", null, 100).run().targetId());
            assertTrue(service.cancel(explicit.toString(), "shared-run").cancellationRequested());
            assertTrue(explicitLedger.cancellationRequested("shared-run"));
            assertFalse(configuredLedger.cancellationRequested("shared-run"));
            assertTrue(service.cancel(null, "shared-run").cancellationRequested());
            assertTrue(configuredLedger.cancellationRequested("shared-run"));
        }
    }

    @Test void absentConfigurationResolvesTheDefaultWithoutOpeningALedger() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.register(LedgerManagementService.class);
            context.refresh();
            assertEquals(Path.of("var/sync-ledger.sqlite3").toAbsolutePath().normalize(),
                    context.getBean(LedgerManagementService.class).resolvePath(null));
        }
    }

    @Test void missingLedgersKeepTheirErrorTypesAndNeverCreateDirectories() {
        Path configured = temp.resolve("missing-configured/ledger.sqlite");
        Path explicit = temp.resolve("missing-explicit/ledger.sqlite");
        var service = new LedgerManagementService(configured.toString());
        for (String override : new String[] {null, explicit.toString()}) {
            assertThrows(IOException.class, () -> service.history(override, null, null, 100));
            assertThrows(IOException.class, () -> service.run(override, "missing-run", null, 100));
            assertThrows(IllegalArgumentException.class, () -> service.cancel(override, "missing-run"));
        }
        assertFalse(Files.exists(configured.getParent()));
        assertFalse(Files.exists(explicit.getParent()));
    }

    @Test void cancellationRecordsIntentWithoutChangingDeliveryStateOrRevision() throws Exception {
        Path path = temp.resolve("cancel.sqlite");
        var ledger = ledger(path, "target");
        ledger.transition("shared-run", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("shared-run", 1, SyncRunState.IN_DOUBT, "{}");
        var before = ledger.get("shared-run");
        var result = new LedgerManagementService(path.toString()).cancel(null, "shared-run");
        assertEquals("shared-run", result.runId());
        assertTrue(result.cancellationRequested());
        assertEquals(SyncRunState.IN_DOUBT, result.state());
        assertEquals(before, SyncRunLedger.openReadOnly(path).get("shared-run"));
        assertTrue(SyncRunLedger.openReadOnly(path).cancellationRequested("shared-run"));
    }

    @Test void historyAndStatusRetainBoundedAscendingPagination() throws Exception {
        Path path = temp.resolve("browse.sqlite");
        var ledger = ledger(path, "target");
        for (String id : List.of("run-c", "run-a", "run-b"))
            ledger.createRun(new SyncRunLedger.Run(id, null, "data.history", 1, "2026-09-29", "target", "{}"));
        ledger.createChild("attempt-b", SyncRunLedger.Kind.ATTEMPT, "run-a", "run-a");
        ledger.createChild("attempt-a", SyncRunLedger.Kind.ATTEMPT, "run-a", "run-a");
        var service = new LedgerManagementService(path.toString());
        assertEquals(List.of("run-b"), service.history(null, "data.history", "run-a", 1).stream()
                .map(LedgerReadModels.RunSummary::id).toList());
        var details = service.run(null, "run-a", "attempt-a", 1);
        assertEquals("run-a", details.run().id());
        assertEquals(List.of("attempt-b"), details.entries().stream().map(LedgerReadModels.Entry::id).toList());
        assertThrows(IllegalArgumentException.class, () -> service.history(null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> service.run(null, "run-a", null, 1001));
    }
}
