package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SyncRunPartialRecoveryTest {
    @TempDir Path root;
    @Test void existingOperationalOpenDoesNotContendForSchemaWriteLock() throws Exception {
        Path path=root.resolve("existing.sqlite3");var ledger=new SyncRunLedger(path);
        ledger.createRun(new SyncRunLedger.Run("run",null,"data.fixture",1,"2026-10-11","target","{}"));
        try(var writer=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var statement=writer.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            assertEquals(SyncRunState.PENDING,SyncRunLedger.openExisting(path).get("run").state());
            statement.execute("ROLLBACK");
        }
        var existing=SyncRunLedger.openExisting(path);
        assertTrue(existing.requestCancellation("run"));
        assertTrue(existing.requestCancellation("run"));
        assertTrue(existing.cancellationRequested("run"));
        ledger.transition("run",0,SyncRunState.CANCELLED,"{}");
        assertFalse(existing.requestCancellation("run"));
        assertThrows(IllegalStateException.class,()->SyncRunLedger.openReadOnly(path).requestCancellation("run"));
        assertThrows(java.io.IOException.class,()->SyncRunLedger.openExisting(root.resolve("missing.sqlite3")));
    }
    @Test void reconciliationRequiresDurableVerifiedChildrenAndStoppedWriterProof() throws Exception {
        var ledger=new SyncRunLedger(root.resolve("ledger.sqlite3"));
        ledger.createRun(new SyncRunLedger.Run("run",null,"data.fixture",1,"2026-10-11","target","{}"));
        ledger.transition("run",0,SyncRunState.RUNNING,"{}");
        ledger.createChild("attempt",SyncRunLedger.Kind.ATTEMPT,"run","run");
        ledger.transition("attempt",0,SyncRunState.RUNNING,"{}");
        ledger.createChild("slice",SyncRunLedger.Kind.SLICE,"run","attempt");
        long revision=0;
        for(var state:new SyncRunState[]{SyncRunState.RUNNING,SyncRunState.FETCHED,SyncRunState.VALIDATED,SyncRunState.SUBMITTED,SyncRunState.IN_DOUBT})
            ledger.transition("slice",revision++,state,"{}");
        ledger.transition("attempt",1,SyncRunState.IN_DOUBT,"{}");
        ledger.transition("run",1,SyncRunState.IN_DOUBT,"{}");
        var json=JobDefinitionJson.mapper();
        var verification=Map.of("passed",true,"writerStopped",true,"expectedRows",250,"matchedRows",250,"actualRows",250,
                "missingKeys",0,"duplicateKeys",0,"mismatchedRows",0,"sourceFingerprint","page-sha","readbackEvidence","proof.json");
        var proof=json.valueToTree(Map.of("submittedRows",250,"sourceComplete",false,"allSubmittedSlicesVerified",true,"verification",verification));
        assertThrows(IllegalArgumentException.class,()->ledger.transition("run",2,SyncRunState.PARTIAL,"{}"));
        assertThrows(IllegalStateException.class,()->ledger.transition("run",2,SyncRunState.PARTIAL,proof.toString()));
        assertThrows(IllegalArgumentException.class,()->ledger.transition("slice",5,SyncRunState.PARTIAL,proof.toString()));
        ledger.transition("slice",5,SyncRunState.VERIFIED,json.writeValueAsString(Map.of("verification",verification)));
        for(String field:new String[]{"writerStopped","passed","sourceFingerprint","readbackEvidence","matchedRows","missingKeys"}) {
            var invalid=proof.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid.path("verification")).remove(field);
            assertThrows(IllegalArgumentException.class,()->ledger.transition("attempt",2,SyncRunState.PARTIAL,invalid.toString()),field);
        }
        ledger.transition("attempt",2,SyncRunState.PARTIAL,proof.toString());
        ledger.transition("run",2,SyncRunState.PARTIAL,proof.toString());
        assertEquals(SyncRunState.PARTIAL,SyncRunLedger.openReadOnly(ledger.path()).get("run").state());
        assertEquals(SyncRunState.VERIFIED,ledger.get("slice").state());
    }
}
