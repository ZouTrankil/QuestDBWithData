package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockStDailySource;

import com.zoutrankil.data.etf.application.EtfPortfolioSource;

import com.zoutrankil.data.etf.application.EtfFactorSource;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mockStatic;

class SourceEvidenceCollisionTest {
    @TempDir Path temp;
    private record Writer(Class<?> source, String method, String collision, boolean causeExpected) {}
    private static final List<Writer> WRITERS = List.of(
            new Writer(DcIndexSource.class, "persist", "Conflicting deterministic dc_index evidence receipt", true),
            new Writer(EtfFactorSource.class, "persistDeterministically", "Canonical etf_factor evidence path collision", false),
            new Writer(EtfPortfolioSource.class, "writeImmutable", "Existing fund_portfolio evidence filename has different bytes", true),
            new Writer(IndexWeightSource.class, "writeImmutable", "D021 immutable evidence path conflicts with existing bytes", true),
            new Writer(MarginAllSource.class, "persist", "Conflicting immutable D028 receipt", true),
            new Writer(MarginDetailSource.class, "persist", "Conflicting immutable D029 source receipt", true),
            new Writer(MarginSecsSource.class, "persist", "Conflicting immutable D030 receipt", true),
            new Writer(MarginZrzSource.class, "persist", "Conflicting immutable D031 receipt", true),
            new Writer(MoneyflowDcSource.class, "persist", "Conflicting deterministic moneyflow_dc receipt", true),
            new Writer(MoneyflowHsgtSource.class, "persist", "Conflicting immutable D027 receipt", true),
            new Writer(MoneyflowSource.class, "persist", "Conflicting deterministic moneyflow receipt", true),
            new Writer(MoneyflowThsSource.class, "persist", "Conflicting immutable D025 receipt", true),
            new Writer(StockStDailySource.class, "writeDeterministic", "D012 deterministic evidence filename already contains different bytes", true));

    @Test void immutableWritersKeepExactBytesAndAcceptIdenticalRetriesIncludingEmptyBytes() throws Exception {
        for (var writer : WRITERS) {
            byte[][] payloads = {new byte[0], "{\"source\":\"中文\",\"rows\":[]}\r\n".getBytes(StandardCharsets.UTF_8)};
            for (int i = 0; i < payloads.length; i++) {
                Path path = temp.resolve(writer.source().getSimpleName() + "-" + i + ".json");
                write(writer, path, payloads[i]);
                assertArrayEquals(payloads[i], Files.readAllBytes(path));
                write(writer, path, payloads[i]);
                assertArrayEquals(payloads[i], Files.readAllBytes(path));
            }
        }
    }

    @Test void semanticJsonEqualityDoesNotReplaceTheOriginalByteCollisionPolicy() throws Exception {
        byte[] expected = "{\"a\":1,\"b\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] existing = "{\n  \"b\": 2, \"a\": 1\n}\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(JobDefinitionJson.mapper().readTree(expected), JobDefinitionJson.mapper().readTree(existing));
        for (var writer : WRITERS) assertCollision(writer, expected, existing, "semantic");
    }

    @Test void shorterEqualLengthAndOversizedCollisionsKeepTheirMessagesAndCauses() throws Exception {
        byte[] expected = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[][] conflicts = {"{}".getBytes(StandardCharsets.UTF_8),
                "{\"a\":2}".getBytes(StandardCharsets.UTF_8), new byte[4096]};
        for (var writer : WRITERS) {
            for (int i = 0; i < conflicts.length; i++) assertCollision(writer, expected, conflicts[i], "length-" + i);
        }
    }

    @Test void etfFactorConcurrentCollisionKeepsTheDistinctMessageAndOriginalCause() throws Exception {
        var writer = WRITERS.get(1);
        Path path = temp.resolve("raced.json");
        byte[] expected = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] conflict = "{\"different\":true}".getBytes(StandardCharsets.UTF_8);
        var raced = new FileAlreadyExistsException(path.toString());
        try (var store = mockStatic(FileEvidenceStore.class)) {
            store.when(() -> FileEvidenceStore.readBounded(any(Path.class), anyInt(), any())).thenCallRealMethod();
            store.when(() -> FileEvidenceStore.writeNew(path, expected)).thenAnswer(call -> {
                Files.write(path, conflict, StandardOpenOption.CREATE_NEW);
                throw raced;
            });
            var failure = assertThrowsExactly(IllegalStateException.class, () -> write(writer, path, expected));
            assertEquals("Canonical etf_factor evidence changed concurrently", failure.getMessage());
            assertSame(raced, failure.getCause());
            assertArrayEquals(conflict, Files.readAllBytes(path));
        }
    }

    private void assertCollision(Writer writer, byte[] expected, byte[] existing, String suffix) throws Exception {
        Path path = temp.resolve(writer.source().getSimpleName() + "-" + suffix + ".json");
        Files.write(path, existing, StandardOpenOption.CREATE_NEW);
        var failure = assertThrowsExactly(IllegalStateException.class, () -> write(writer, path, expected));
        assertEquals(writer.collision(), failure.getMessage());
        if (writer.causeExpected()) assertInstanceOf(FileAlreadyExistsException.class, failure.getCause());
        else assertNull(failure.getCause());
        assertArrayEquals(existing, Files.readAllBytes(path));
    }

    private static void write(Writer writer, Path path, byte[] bytes) throws Exception {
        var method = writer.source().getDeclaredMethod(writer.method(), Path.class, byte[].class);
        method.setAccessible(true);
        try { method.invoke(null, path, bytes); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception failure) throw failure;
            throw wrapped;
        }
    }
}
