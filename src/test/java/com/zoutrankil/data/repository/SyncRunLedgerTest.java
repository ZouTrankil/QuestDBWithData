package com.zoutrankil.data.repository;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.SyncRunState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunLedgerTest {
    @TempDir Path root;
    private SyncRunLedger.Run run(String id) {
        return new SyncRunLedger.Run(id, null, "data.stock_basic", 1, "2026-09-29", "local-validation",
                "{\"definitionVersion\":1,\"mode\":\"SNAPSHOT\"}");
    }
    @Test void hierarchyAndConfigurationSurviveReopenWithSqlProjectionAgreement() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path);
        ledger.createRun(run("run-1"));
        ledger.transition("run-1", 0, SyncRunState.RUNNING, "{}");
        ledger.createChild("attempt-1", SyncRunLedger.Kind.ATTEMPT, "run-1", "run-1");
        ledger.createChild("slice-1", SyncRunLedger.Kind.SLICE, "run-1", "attempt-1");
        ledger.transition("slice-1", 0, SyncRunState.RUNNING, "{\"cursor\":\"bounded-1\"}");
        var reopened = new SyncRunLedger(path);
        assertEquals(run("run-1"), reopened.getRun("run-1"));
        assertEquals("attempt-1", reopened.get("slice-1").parentId());
        assertEquals("{\"cursor\":\"bounded-1\"}", reopened.get("slice-1").payloadJson());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement();
             var r = s.executeQuery("SELECT state,revision,payload_json FROM sync_events WHERE entry_id='slice-1' ORDER BY revision DESC LIMIT 1")) {
            assertTrue(r.next());
            var projection = reopened.get("slice-1");
            assertEquals(projection.state().name(), r.getString(1));
            assertEquals(projection.revision(), r.getLong(2));
            assertEquals(projection.payloadJson(), r.getString(3));
        }
    }
    @Test void staleOrInvalidTransitionAndInvalidHierarchyDoNotAlterCommittedState() throws Exception {
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        ledger.createRun(run("run-1")); ledger.createRun(run("run-2"));
        ledger.transition("run-1", 0, SyncRunState.RUNNING, "{}");
        assertThrows(IllegalStateException.class, () -> ledger.transition("run-1", 0, SyncRunState.FAILED, "{}"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transition("run-1", 1, SyncRunState.ACKNOWLEDGED, "{}"));
        assertThrows(IllegalArgumentException.class, () -> ledger.createChild("bad", SyncRunLedger.Kind.SLICE, "run-1", "run-1"));
        assertThrows(IllegalArgumentException.class, () -> ledger.createChild("bad", SyncRunLedger.Kind.ATTEMPT, "run-2", "run-1"));
        assertEquals(1, ledger.get("run-1").revision());
        assertEquals(SyncRunState.RUNNING, ledger.get("run-1").state());
        ledger.transition("run-1", 1, SyncRunState.IN_DOUBT, "{\"writerStopped\":false}");
        assertThrows(IllegalArgumentException.class, () -> ledger.transition("run-1", 2, SyncRunState.RUNNING, "{}"));
        assertThrows(IllegalArgumentException.class, () -> ledger.createChild("retry", SyncRunLedger.Kind.ATTEMPT, "run-1", "run-1"));
    }
    @Test void failedEventInsertRollsBackStateProjectionAtomically() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); ledger.createRun(run("run-1"));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + path); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER fail_event BEFORE INSERT ON sync_events BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        }
        assertThrows(java.sql.SQLException.class, () -> ledger.transition("run-1", 0, SyncRunState.RUNNING, "{}"));
        assertEquals(SyncRunState.PENDING, ledger.get("run-1").state());
        assertEquals(0, ledger.get("run-1").revision());
    }
    @Test void completionRequiresReadbackOrProvenEmptySource() throws Exception {
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        ledger.createRun(run("run-1")); ledger.transition("run-1", 0, SyncRunState.RUNNING, "{}");
        assertThrows(IllegalArgumentException.class, () -> ledger.transition("run-1", 1, SyncRunState.VERIFIED, "{}"));
        assertThrows(IllegalArgumentException.class, () -> ledger.transition("run-1", 1, SyncRunState.VERIFIED_EMPTY, "{}"));
        ledger.transition("run-1", 1, SyncRunState.VERIFIED_EMPTY,
                "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"response.json\"}");
        assertEquals(SyncRunState.VERIFIED_EMPTY, ledger.get("run-1").state());
    }
    @Test void statusReadsAreReadOnlyAndBoundedWithoutCreatingMissingDatabase() throws Exception {
        var absent = root.resolve("absent.sqlite3");
        assertThrows(java.io.IOException.class, () -> SyncRunLedger.openReadOnly(absent));
        assertFalse(java.nio.file.Files.exists(absent));
        Path path = root.resolve("ledger.sqlite3");
        var writer = new SyncRunLedger(path); writer.createRun(run("run-1"));
        writer.createChild("attempt-1", SyncRunLedger.Kind.ATTEMPT, "run-1", "run-1");
        var reader = SyncRunLedger.openReadOnly(path);
        var first = reader.entries("run-1", null, 1);
        assertEquals(1, first.size());
        assertEquals(1, reader.entries("run-1", first.getFirst().id(), 1).size());
        assertEquals(1, reader.events("run-1", -1, 10).size());
        assertTrue(reader.events("run-1", 0, 10).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> reader.entries("run-1", null, 1001));
        assertThrows(IllegalStateException.class, () -> reader.createRun(run("run-2")));
    }
    @Test void structuredFrozenDefinitionSurvivesDatabaseReopen() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var request = com.zoutrankil.data.service.StockBasicJobDefinition.DEFINITION.freeze(
                null, java.util.Map.of(), null, null, java.time.LocalDate.of(2026, 9, 29));
        var writer = new SyncRunLedger(path);
        writer.createRun("run-1", null, "isolated-questdb", request);
        var frozen = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                SyncRunLedger.openReadOnly(path).getRun("run-1").frozenJson());
        assertEquals(1, frozen.path("definition").path("version").asInt());
        assertEquals("stock_basic_snapshot", frozen.path("definition").path("datasetId").asText());
        assertEquals("SNAPSHOT", frozen.path("mode").asText());
        assertEquals("2026-09-29", frozen.path("logicalDate").asText());
        assertEquals("PT5M", frozen.path("definition").path("timeout").asText());
    }
    @Test void parentCannotCompleteWhileChildIsPending() throws Exception {
        var ledger = new SyncRunLedger(root.resolve("ledger.sqlite3"));
        ledger.createRun(run("run-1")); ledger.transition("run-1", 0, SyncRunState.RUNNING, "{}");
        ledger.createChild("attempt-1", SyncRunLedger.Kind.ATTEMPT, "run-1", "run-1");
        String proof = "{\"sourceComplete\":true,\"returnedRows\":0,\"submittedRows\":0,\"responseEvidence\":\"response.json\"}";
        assertThrows(IllegalStateException.class, () -> ledger.transition("run-1", 1, SyncRunState.VERIFIED_EMPTY, proof));
        ledger.transition("attempt-1", 0, SyncRunState.RUNNING, "{}");
        ledger.transition("attempt-1", 1, SyncRunState.VERIFIED_EMPTY, proof);
        ledger.transition("run-1", 1, SyncRunState.VERIFIED_EMPTY, proof);
        assertEquals(SyncRunState.VERIFIED_EMPTY, ledger.get("run-1").state());
    }
    @Test void actualStatusCommandReadsExistingLedger() throws Exception {
        Path path = root.resolve("ledger.sqlite3");
        var ledger = new SyncRunLedger(path); ledger.createRun(run("run-1"));
        var app = new org.springframework.boot.SpringApplication(
                QuestDataApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try (var context = app.run("show-sync-run", "--run", "run-1", "--ledger", path.toString(), "--limit", "1")) {
            assertTrue(context.isActive());
            assertEquals(0, ledger.get("run-1").revision());
        }
    }
    @Test void cancellationPersistsWithoutPretendingWriterStopped() throws Exception {
        Path path=root.resolve("ledger.sqlite3");
        var ledger=new SyncRunLedger(path); ledger.createRun(run("run-1"));
        ledger.transition("run-1",0,SyncRunState.RUNNING,"{}");
        ledger.transition("run-1",1,SyncRunState.IN_DOUBT,"{}");
        assertTrue(ledger.requestCancellation("run-1"));
        var reopened=SyncRunLedger.openReadOnly(path);
        assertTrue(reopened.cancellationRequested("run-1"));
        assertEquals(SyncRunState.IN_DOUBT,reopened.get("run-1").state());
        assertEquals(2,reopened.get("run-1").revision());
    }
}
