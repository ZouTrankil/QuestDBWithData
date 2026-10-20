package com.zoutrankil.data.flow.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.flow.port.MoneyflowTarget;
import com.zoutrankil.data.flow.port.MoneyflowWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class MoneyflowRunRecoveryTest {
    @TempDir Path root;
    private static final String RUN = "moneyflow-402336a7-7513-456f-a96d-335ff6d5cddc";
    private final MoneyflowTarget target =
            new MoneyflowTarget() {
                public String tableName() {
                    return "moneyflow";
                }

                public String targetId() {
                    throw new AssertionError(
                            "Must not inspect the target before stopped-writer proof");
                }

                public MoneyflowWriteSession newWriter(String identity) {
                    throw new AssertionError("Must not create a writer");
                }
            };

    private Path log(String body) throws Exception {
        return Files.writeString(root.resolve("original-writer.log"), body);
    }

    @Test
    void nativeFailureReceiptCannotAuthorizeRecoveryWhileOriginalProcessLives() throws Exception {
        var original =
                log(
                        "Starting QuestDataApplication using Java 24 with PID "
                                + ProcessHandle.current().pid()
                                + "\nmoneyflow sync incomplete: IN_DOUBT; run="
                                + RUN
                                + "\n{\"status\":\"FAILED\",\"exitCode\":3}\n");
        var error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                MoneyflowRunRecovery.reconcile(
                                        root.resolve("missing-ledger.sqlite"),
                                        RUN,
                                        original,
                                        target));
        assertEquals("Original writer still present", error.getMessage());
        assertFalse(Files.exists(root.resolve("missing-ledger.sqlite")));
    }

    @Test
    void unrelatedFailureLogCannotOpenAnAuthorityOrWriter() throws Exception {
        var original =
                log(
                        "Starting QuestDataApplication using Java 24 with PID 999999999\n"
                                + "moneyflow sync incomplete: IN_DOUBT; run=another-run\n{\"status\":\"FAILED\",\"exitCode\":3}\n");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MoneyflowRunRecovery.reconcile(
                                root.resolve("missing-ledger.sqlite"), RUN, original, target));
        assertFalse(Files.exists(root.resolve("missing-ledger.sqlite")));
    }

    @Test
    void anObservationLogWithoutTerminalReceiptCannotAuthorizeRecovery() throws Exception {
        var original =
                log(
                        "Starting QuestDataApplication using Java 24 with PID 999999999\n"
                                + "moneyflow sync incomplete: IN_DOUBT; run="
                                + RUN
                                + "\n");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MoneyflowRunRecovery.reconcile(
                                root.resolve("missing-ledger.sqlite"), RUN, original, target));
    }
}
