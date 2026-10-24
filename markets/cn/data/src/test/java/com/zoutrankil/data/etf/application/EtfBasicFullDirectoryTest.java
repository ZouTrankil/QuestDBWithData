package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicDataset;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.etf.mapper.EtfBasicMapper;
import com.zoutrankil.data.etf.port.EtfBasicDirectorySession;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.etf.storage.EtfBasicWritePort;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.data.service.SyncRunExecution;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

class EtfBasicFullDirectoryTest {
    @TempDir Path root;
    static final String ID = "static-v2-" + "a".repeat(64);

    static Map<String, JsonNode> raw(String code, String name) {
        var result = new LinkedHashMap<String, JsonNode>();
        var json = JobDefinitionJson.mapper();
        for (String field : EtfBasicMapper.SOURCE_FIELDS) result.put(field, json.nullNode());
        result.put("ts_code", json.valueToTree(code));
        result.put("market", json.valueToTree("E"));
        result.put("status", json.valueToTree("L"));
        result.put("name", json.valueToTree(name));
        return result;
    }

    static class Memory implements EtfBasicDirectorySession {
        final Map<EtfBasicKey, EtfBasic> rows = new HashMap<>();
        int sends;
        boolean extra, corrupt;

        public void preflight() {}

        public VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> codec() {
            return EtfBasicWritePort.CODEC;
        }

        public List<EtfBasic> readDirectory() {
            return List.copyOf(rows.values());
        }

        public void send(List<EtfBasic> values) {
            sends++;
            values.forEach(v -> rows.put(v.key(), v));
            if (extra) {
                var v =
                        new EtfBasicMapper()
                                .fromSource(
                                        raw("510999.SH", "并发新增"),
                                        Instant.now().truncatedTo(ChronoUnit.MICROS));
                rows.put(v.key(), v);
            }
            if (corrupt) {
                var v =
                        new EtfBasicMapper()
                                .fromSource(
                                        raw("510050.SH", "保留行被改"),
                                        Instant.now().truncatedTo(ChronoUnit.MICROS));
                rows.put(v.key(), v);
            }
        }

        public List<EtfBasic> readback(List<EtfBasicKey> keys) {
            return keys.stream().map(rows::get).filter(Objects::nonNull).toList();
        }

        public boolean walSettled() {
            return true;
        }
    }

    SyncJobRunner.Result execute(Memory memory, List<Map<String, JsonNode>> rows, Instant observed)
            throws Exception {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals(Map.of("market", "E"), params);
                            return new PageExecutor.Page(rows, null, false, null);
                        });
        var request =
                EtfBasicSyncJobOwner.DEFINITION.freeze(
                        Mode.SNAPSHOT,
                        Map.of("targetId", ID, "observedAt", observed.toString()),
                        null,
                        null,
                        observed.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
        String id = "etf-basic-" + UUID.randomUUID();
        Path folder = root.resolve("sync-evidence").resolve(id);
        return SyncRunExecution.execute(
                root.resolve("sync.sqlite"),
                id,
                null,
                ID,
                request,
                "取消状态不可读",
                () -> new EtfBasicSyncAdapter(pages, memory, folder, true));
    }

    @SuppressWarnings("unchecked")
    private EtfBasicJobService service(Memory memory, TusharePageService source) {
        var target = (EtfWriteTarget<EtfBasic, EtfBasicKey>) mock(EtfWriteTarget.class);
        when(target.tableName()).thenReturn("etf_basic");
        when(target.targetId()).thenReturn(ID);
        when(target.newWriter(ID)).thenReturn(memory);
        return new EtfBasicJobService(
                mock(SyncJobRegistry.class), source, target, root.resolve("sync.sqlite"));
    }

    @Test
    void explicitRevalidationReadsOriginalFullDirectoryWithoutFetchingOrSending() throws Exception {
        var memory = new Memory();
        var old =
                new EtfBasicMapper()
                        .fromSource(raw("510050.SH", "保留行"), Instant.parse("2026-01-01T00:00:00Z"));
        memory.rows.put(old.key(), old);
        var result =
                execute(
                        memory,
                        List.of(raw("510300.SH", "当前")),
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
        var source = mock(TusharePageService.class);
        var service = service(memory, source);
        int sent = memory.sends;
        assertTrue(Files.isRegularFile(Path.of(service.revalidate(result.runId()))));
        assertEquals(sent, memory.sends);
        verifyNoInteractions(source);
        var changed =
                new EtfBasicMapper()
                        .fromSource(
                                raw("510050.SH", "保留行被改"), Instant.parse("2026-01-01T00:00:00Z"));
        memory.rows.put(changed.key(), changed);
        assertThrows(IllegalStateException.class, () -> service.revalidate(result.runId()));
        assertEquals(sent, memory.sends);
    }

    @Test
    void corruptedOriginalSourceCannotCertifyReferencePreparation() throws Exception {
        var memory = new Memory();
        var result =
                execute(
                        memory,
                        List.of(raw("510300.SH", "当前")),
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
        var source = mock(TusharePageService.class);
        var service = service(memory, source);
        Path receipt = Path.of(service.revalidate(result.runId()));
        var proof = JobDefinitionJson.mapper().readTree(receipt.toFile());
        Files.writeString(Path.of(proof.path("sourceReceipt").asText()), "{}");
        assertThrows(IllegalStateException.class, () -> service.revalidate(result.runId()));
        verifyNoInteractions(source);
    }

    @Test
    void finalSnapshotIncludesAllCurrentValuesAndUntouchedRetainedRows() throws Exception {
        var memory = new Memory();
        var old =
                new EtfBasicMapper()
                        .fromSource(
                                raw("510050.SH", "历史保留"), Instant.parse("2026-01-01T00:00:00Z"));
        memory.rows.put(old.key(), old);
        var first =
                execute(
                        memory,
                        List.of(raw("510300.SH", "当前一"), raw("510500.SH", "当前二")),
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertEquals(SyncRunState.VERIFIED, first.state());
        assertEquals(3, memory.rows.size());
        assertEquals(old, memory.rows.get(old.key()));
        var revision =
                execute(
                        memory,
                        List.of(raw("510300.SH", "修订")),
                        Instant.now().truncatedTo(ChronoUnit.MICROS));
        assertEquals(SyncRunState.VERIFIED, revision.state());
        assertEquals(3, memory.rows.size());
        assertEquals(
                "修订",
                memory.rows.values().stream()
                        .filter(r -> r.tsCode().equals("510300.SH"))
                        .findFirst()
                        .orElseThrow()
                        .name());
        try (var stream = Files.list(root.resolve("sync-evidence").resolve(revision.runId()))) {
            var complete =
                    stream.filter(p -> p.getFileName().toString().startsWith("complete-"))
                            .findFirst()
                            .orElseThrow();
            var proof = JobDefinitionJson.mapper().readTree(complete.toFile());
            assertTrue(proof.get("fullDirectoryVerified").asBoolean());
            assertEquals(1, proof.get("sourceCurrentKeys").size());
            assertEquals(2, proof.get("retainedLegacyKeys").size());
            assertEquals(3, proof.get("expectedFullDirectory").size());
        }
    }

    @Test
    void scopeMismatchAfterVerifiedBatchesRetainsOriginalLease() throws Exception {
        for (boolean extra : List.of(false, true)) {
            var memory = new Memory();
            memory.extra = extra;
            memory.corrupt = !extra;
            var old =
                    new EtfBasicMapper()
                            .fromSource(
                                    raw("510050.SH", "历史保留"),
                                    Instant.parse("2026-01-01T00:00:00Z"));
            memory.rows.put(old.key(), old);
            var result =
                    execute(
                            memory,
                            List.of(raw("510300.SH", "当前")),
                            Instant.now().truncatedTo(ChronoUnit.MICROS));
            assertEquals(SyncRunState.IN_DOUBT, result.state());
            assertNotNull(
                    new DatasetIntervalLock(root.resolve("sync.sqlite"))
                            .findOwned(
                                    result.runId(),
                                    DatasetIntervalLock.Scope.allDates("etf_basic")));
            assertEquals(1, memory.sends);
            Files.move(root.resolve("sync.sqlite"), root.resolve("saved-" + extra + ".sqlite"));
        }
    }

    @Test
    void formalSessionNeedsFullReadAndCannotRegrabAcrossObservationDays() throws Exception {
        var pages = mock(TusharePageService.class);
        var generic = mock(VerifiedWriteSession.class);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var today = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var request =
                EtfBasicSyncJobOwner.DEFINITION.freeze(
                        Mode.SNAPSHOT,
                        Map.of("targetId", ID, "observedAt", now.toString()),
                        null,
                        null,
                        today);
        assertThrows(
                IllegalArgumentException.class,
                () -> new EtfBasicSyncAdapter(pages, generic, root, true).preflight(request));
        var yesterday = now.minus(1, ChronoUnit.DAYS);
        var old =
                EtfBasicSyncJobOwner.DEFINITION.freeze(
                        Mode.SNAPSHOT,
                        Map.of("targetId", ID, "observedAt", yesterday.toString()),
                        null,
                        null,
                        yesterday.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
        assertThrows(
                IllegalArgumentException.class,
                () -> new EtfBasicSyncAdapter(pages, new Memory(), root, true).preflight(old));
        verifyNoInteractions(pages);
        assertThrows(
                IllegalStateException.class,
                () -> EtfBasicDataset.requireIsolatedTable("etf_basic"));
    }
}
