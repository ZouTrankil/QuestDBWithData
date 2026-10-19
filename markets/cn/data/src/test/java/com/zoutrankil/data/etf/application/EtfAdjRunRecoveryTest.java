package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfAdjTarget;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EtfAdjRunRecoveryTest {
    @TempDir Path root;
    private static final String RUN="etf-adj-402336a7-7513-456f-a96d-335ff6d5cddc";
    private final EtfAdjTarget target=new EtfAdjTarget() {
        public String tableName(){return "etf_adj";}
        public String targetId(){throw new AssertionError("Must not inspect the target before stopped-writer proof");}
        public EtfTargetRange range(){throw new AssertionError("Unexpected range read");}
        public EtfAdjWriteSession newWriter(String identity){throw new AssertionError("Must not create a writer");}
    };
    private Path log(String body)throws Exception {
        return Files.writeString(root.resolve("original-writer.log"),body);
    }
    @Test void nativeFailureReceiptCannotAuthorizeRecoveryWhileOriginalProcessLives()throws Exception {
        var original=log("Starting QuestDataApplication using Java 24 with PID "+ProcessHandle.current().pid()
                +"\netf_adj sync incomplete: IN_DOUBT; run="+RUN+"\n{\"status\":\"FAILED\",\"exitCode\":3}\n");
        var error=assertThrows(IllegalStateException.class,()->EtfAdjRunRecovery.reconcile(root.resolve("missing-ledger.sqlite"),RUN,original,target));
        assertEquals("Original writer still present",error.getMessage());
        assertFalse(Files.exists(root.resolve("missing-ledger.sqlite")));
    }
    @Test void unrelatedFailureLogCannotOpenAnAuthorityOrWriter()throws Exception {
        var original=log("Starting QuestDataApplication using Java 24 with PID 999999999\n"
                +"etf_adj sync incomplete: IN_DOUBT; run=another-run\n{\"status\":\"FAILED\",\"exitCode\":3}\n");
        assertThrows(IllegalArgumentException.class,()->EtfAdjRunRecovery.reconcile(root.resolve("missing-ledger.sqlite"),RUN,original,target));
        assertFalse(Files.exists(root.resolve("missing-ledger.sqlite")));
    }
    @Test void anObservationLogWithoutTerminalReceiptCannotAuthorizeRecovery()throws Exception {
        var original=log("Starting QuestDataApplication using Java 24 with PID 999999999\n"
                +"etf_adj sync incomplete: IN_DOUBT; run="+RUN+"\n");
        assertThrows(IllegalArgumentException.class,()->EtfAdjRunRecovery.reconcile(root.resolve("missing-ledger.sqlite"),RUN,original,target));
    }
}
