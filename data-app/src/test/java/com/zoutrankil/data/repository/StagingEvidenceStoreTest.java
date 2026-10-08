package com.zoutrankil.data.repository;

import com.zoutrankil.data.flow.application.MoneyflowHsgtStaging;
import com.zoutrankil.data.margin.application.MarginAllStaging;
import com.zoutrankil.data.margin.application.MarginZrzStaging;

import com.zoutrankil.data.index.storage.DcIndexStaging;
import com.zoutrankil.data.index.application.IndexMonthlyStaging;

import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class StagingEvidenceStoreTest {
    @TempDir Path root;

    private record DurableStage(Class<?> type, String dataset, String writeMethod, String atomicFailure) {}

    private static final List<DurableStage> DURABLE_STAGES = List.of(
            new DurableStage(IndexMonthlyStaging.class, "index_monthly", "writeNewDurable",
                    "D022 stage READY intent requires an atomic durable replace"),
            new DurableStage(MarginAllStaging.class, "margin_all", "writeNew",
                    "D028 READY intent requires atomic durable replace"),
            new DurableStage(MarginZrzStaging.class, "margin_zrz", "writeNew",
                    "D031 READY intent requires atomic durable replace"),
            new DurableStage(MoneyflowHsgtStaging.class, "moneyflow_hsgt", "writeNew",
                    "D027 READY intent requires atomic durable replace"));

    @Test void dcIndexReceiptKeepsSemanticIdempotenceAndOriginalBytes() throws Exception {
        Path receipt = root.resolve("dc-receipt.json");
        byte[] expected = "{\"z\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8);
        invoke(DcIndexStaging.class, "persist", receipt, expected);
        assertArrayEquals(expected, Files.readAllBytes(receipt));

        byte[] spaced = "{\n  \"a\": 2,\n  \"z\": 1\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(receipt, spaced);
        invoke(DcIndexStaging.class, "persist", receipt, expected);
        assertArrayEquals(spaced, Files.readAllBytes(receipt));

        var failure = assertThrowsExactly(IllegalStateException.class, () -> invoke(DcIndexStaging.class,
                "persist", receipt, "{\"z\":9,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Conflicting deterministic D023 stage receipt", failure.getMessage());
        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        assertArrayEquals(spaced, Files.readAllBytes(receipt));
    }

    @Test void hsgtReceiptKeepsCanonicalNewBytesAndSemanticCollisionPolicy() throws Exception {
        Path receipt = root.resolve("hsgt-receipt.json");
        var body = body("moneyflow_hsgt", "READY");
        invoke(MoneyflowHsgtStaging.class, "writeNew", receipt, body);
        assertArrayEquals(JobDefinitionJson.canonicalMapper().writeValueAsBytes(body), Files.readAllBytes(receipt));

        byte[] spaced = JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        Files.write(receipt, spaced);
        invoke(MoneyflowHsgtStaging.class, "writeNew", receipt, body);
        assertArrayEquals(spaced, Files.readAllBytes(receipt));

        var failure = assertThrowsExactly(IOException.class, () -> invoke(MoneyflowHsgtStaging.class,
                "writeNew", receipt, body("moneyflow_hsgt", "DISCARDED")));
        assertEquals("D027 deterministic stage proof conflicts with existing receipt", failure.getMessage());
        assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        assertArrayEquals(spaced, Files.readAllBytes(receipt));
    }

    @Test void monthlyAndMarginReceiptsRemainStrictlyImmutable() throws Exception {
        for (var stage : DURABLE_STAGES.subList(0, 3)) {
            Path receipt = root.resolve(stage.dataset() + "-receipt.json");
            var body = body(stage.dataset(), "READY");
            invoke(stage.type(), stage.writeMethod(), receipt, body);
            byte[] written = serialized(stage, body);
            assertArrayEquals(written, Files.readAllBytes(receipt), stage.dataset());
            assertThrowsExactly(FileAlreadyExistsException.class,
                    () -> invoke(stage.type(), stage.writeMethod(), receipt, body), stage.dataset());
            assertArrayEquals(written, Files.readAllBytes(receipt), stage.dataset());
        }
    }

    @Test void durableReplacementKeepsBytesAndExistingDiscardedRecoveryRules() throws Exception {
        for (var stage : DURABLE_STAGES) {
            Path run = root.resolve(stage.dataset());
            Path folder = Files.createDirectories(run.resolve("stage"));
            Path intent = folder.resolve("test-intent.json");
            invoke(stage.type(), stage.writeMethod(), intent, body(stage.dataset(), "PREPARING"));
            assertTrue(hasStageIntent(stage, run));
            for (String phase : List.of("READY", "DISCARDED")) {
                var body = body(stage.dataset(), phase);
                invoke(stage.type(), "replaceDurable", intent, body);
                assertArrayEquals(serialized(stage, body), Files.readAllBytes(intent), stage.dataset());
                assertEquals(!phase.equals("DISCARDED") || stage.type() == IndexMonthlyStaging.class,
                        hasStageIntent(stage, run), stage.dataset());
                try (var entries = Files.list(folder)) {
                    assertEquals(List.of(intent), entries.toList(), stage.dataset());
                }
            }
        }
    }

    @Test void unsupportedAtomicReplacementKeepsCallerFailureAndExistingArtifact() throws Exception {
        for (var stage : DURABLE_STAGES) {
            Path intent = root.resolve(stage.dataset() + "-intent.json");
            byte[] original = serialized(stage, body(stage.dataset(), "PREPARING"));
            Files.write(intent, original);
            var unsupported = new AtomicMoveNotSupportedException("temporary", intent.toString(), "unsupported");
            try (var store = mockStatic(FileEvidenceStore.class)) {
                store.when(() -> FileEvidenceStore.replaceDurable(any(Path.class), any(byte[].class)))
                        .thenThrow(unsupported);
                var failure = assertThrowsExactly(IOException.class,
                        () -> invoke(stage.type(), "replaceDurable", intent, body(stage.dataset(), "READY")));
                assertEquals(stage.atomicFailure(), failure.getMessage());
                assertSame(unsupported, failure.getCause());
            }
            assertArrayEquals(original, Files.readAllBytes(intent), stage.dataset());
        }
    }

    @Test void intentDiscoveryKeepsMissingDirectoryAndPerDatasetBoundFailures() throws Exception {
        Path missing = root.resolve("missing-run");
        for (var stage : DURABLE_STAGES) assertFalse(hasStageIntent(stage, missing));
        assertFalse(Files.exists(missing));

        Path folder = Files.createDirectories(root.resolve("run").resolve("stage"));
        Files.write(folder.resolve("test-intent.json"), new byte[4 * 1024 * 1024 + 1]);
        Path run = folder.getParent();
        var messages = List.of("D022 stage intent is symlinked or outside its bounded file size",
                "D028 stage intent invalid", "D031 stage intent invalid", "D027 stage intent invalid");
        for (int i = 0; i < DURABLE_STAGES.size(); i++) {
            var stage = DURABLE_STAGES.get(i);
            assertEquals(messages.get(i), assertThrowsExactly(IOException.class,
                    () -> hasStageIntent(stage, run)).getMessage());
        }
    }

    private static Map<String, Object> body(String dataset, String phase) {
        var body = new LinkedHashMap<String, Object>();
        body.put("phase", phase);
        body.put("observedDate", LocalDate.of(2026, 10, 7));
        body.put("dataset", dataset);
        return body;
    }

    private static byte[] serialized(DurableStage stage, Object body) throws Exception {
        return (stage.type() == IndexMonthlyStaging.class ? JobDefinitionJson.mapper()
                : JobDefinitionJson.canonicalMapper()).writeValueAsBytes(body);
    }

    private static boolean hasStageIntent(DurableStage stage, Path run) throws Exception {
        if (stage.type() == IndexMonthlyStaging.class) return IndexMonthlyStaging.hasStageIntent(run);
        if (stage.type() == MarginAllStaging.class) return MarginAllStaging.hasStageIntent(run);
        if (stage.type() == MarginZrzStaging.class) return MarginZrzStaging.hasStageIntent(run);
        return MoneyflowHsgtStaging.hasStageIntent(run);
    }

    // Exercise the existing receipt policies without a QuestDB connection or widening their visibility.
    private static void invoke(Class<?> owner, String name, Path path, Object body) throws Exception {
        var method = owner.getDeclaredMethod(name, Path.class, body instanceof byte[] ? byte[].class : Object.class);
        method.setAccessible(true);
        try {
            method.invoke(null, path, body);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
}
