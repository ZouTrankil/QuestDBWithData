package com.zoutrankil.batch;

import com.zoutrankil.data.stock.application.DailySource;
import com.zoutrankil.data.stock.application.DailySyncAdapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.DailyMapper;
import com.zoutrankil.data.stock.storage.DailyWritePort;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Goldens were captured from compiled pre-T12 classes; see the adjacent resource provenance. */
class DailySemanticsCompatibilityTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final Set<String> CODES = Set.of("000001.SZ");
    @TempDir Path temp;

    @Test void datasetDefinitionRetainsEveryFieldAndItsOriginalStoragePolicy() throws Exception {
        var json = JobDefinitionJson.mapper();
        ObjectNode tree = json.valueToTree(DailyDataset.DEFINITION);
        sortSet(tree, "capabilities");
        assertArrayEquals(golden("dataset-definition.json"), json.writeValueAsBytes(tree));
        assertEquals(DatasetDefinition.Partition.YEAR, DailyDataset.DEFINITION.partition());
        assertEquals(1, DailyDataset.DEFINITION.schemaVersion());
        assertEquals(DailySemantics.V1.fields(), DailyDataset.DEFINITION.columns());
        assertEquals(DailySemantics.V1.businessKey(), DailyDataset.DEFINITION.businessKey());
        assertEquals(List.of("ts_code", "trade_date"), DailyDataset.DEFINITION.dedupKey());
    }

    @Test void batchContractAndSourceRequestKeepTheirOriginalBytesAndVersionVocabulary() throws Exception {
        var contract = SourceContract.load("daily");
        assertArrayEquals(golden("source-contract.json"), Json.MAPPER.writeValueAsBytes(contract));
        assertEquals("DAY", contract.partitionBy());
        assertEquals("daily-v1", contract.version());
        assertArrayEquals(golden("source-request.json"), Json.MAPPER.writeValueAsBytes(request()));
        assertEquals(6000, contract.pageSize());
        assertEquals(20000, contract.maxRows());
        assertEquals(33554432, contract.maxBytes());
    }

    @Test void legacyPageContractKeepsItsExactCapAndAllowedParameters() throws Exception {
        var json = JobDefinitionJson.mapper();
        ObjectNode tree = json.valueToTree(DailySource.BY_DATE);
        sortSet(tree, "allowedParameters");
        assertArrayEquals(golden("source-page-contract.json"), json.writeValueAsBytes(tree));
        assertEquals(DailySemantics.V1.sourceFields(), DailySource.FIELDS);
        assertEquals(6001, DailySource.BY_DATE.sourceRowCap());
        assertEquals(1000, DailySource.CODE_GROUP_SIZE);
        assertEquals(PageContract.Paging.NONE, DailySource.BY_DATE.paging());
    }

    @Test void frozenSyncRequestAndItsRecoveryFingerprintRemainCompatible() throws Exception {
        var request = DailySyncAdapter.definition(true).freeze(SyncJobDefinition.Mode.BACKFILL,
                Map.of(), DAY.minusDays(4), DAY, DAY);
        var json = JobDefinitionJson.mapper();
        ObjectNode tree = json.valueToTree(request);
        sortSet((ObjectNode)tree.get("definition"), "supportedModes");
        assertArrayEquals(golden("sync-request.json"), json.writeValueAsBytes(tree));
        assertEquals(goldenText("sync-request.sha256"), SyncRequestIdentity.fingerprint(request, "daily"));
        assertEquals(goldenText("sync-request.sha256"),
                SyncRequestIdentity.fingerprint(goldenText("sync-request.json"), "daily"));
    }

    @Test void normalizationPreservesNullSignedZeroAndTheTwoDateRepresentations() throws Exception {
        var normalized = SourceContract.load("daily").normalize(raw(), DAY, CODES);
        assertArrayEquals(golden("normalized-row.json"), Json.MAPPER.writeValueAsBytes(normalized));
        assertEquals(DailySemantics.V1.storageFields(), new ArrayList<>(normalized.keySet()));
        assertInstanceOf(String.class, normalized.get("trade_date"));
        assertEquals("2026-09-28", normalized.get("trade_date"));
        assertNull(normalized.get("low"));
        assertNull(normalized.get("ah_vol"));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits((Double)normalized.get("open")));

        var legacy = Json.MAPPER.readValue(golden("daily-market-bar.json"), DailyMarketBar.class);
        var mapper = new DailyMapper();
        var logical = mapper.values(legacy);
        assertEquals(DAY, logical.get("trade_date", LocalDate.class));
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), mapper.toStorage(legacy).tradeDate());
        assertEquals(legacy, mapper.fromStorage(mapper.toStorage(legacy)));
        assertEquals(legacy, mapper.fromValues(logical));
        assertArrayEquals(golden("daily-codec.bin"), DailyWritePort.CODEC.canonicalBytes(legacy));
        var positiveZero = new DailyMarketBar(legacy.tsCode(), legacy.tradeDate(), 0.0, legacy.high(), legacy.low(),
                legacy.close(), legacy.preClose(), legacy.change(), legacy.pctChg(), legacy.vol(), legacy.amount(),
                legacy.ahVol(), legacy.ahAmount());
        assertFalse(Arrays.equals(DailyWritePort.CODEC.canonicalBytes(legacy), DailyWritePort.CODEC.canonicalBytes(positiveZero)));
    }

    @Test void collectorProducesIdenticalFrozenBytesAndContentFingerprint() throws Exception {
        var calls = new ArrayList<Map<String,Object>>();
        var result = new SourceCollector(temp).collect(request(), (contract, parameters) -> {
            assertEquals(DailySemantics.V1.sourceFields(), contract.fields());
            calls.add(new LinkedHashMap<>(parameters));
            return new PageExecutor.Page(List.of(raw()), null, false, null);
        });
        assertEquals(List.of(Map.of("trade_date", "20260928")), calls);
        byte[] bytes = Files.readAllBytes(Path.of(result.artifact()));
        assertArrayEquals(golden("frozen.json"), bytes);
        assertEquals(goldenText("frozen.sha256"), result.fingerprint());
        assertEquals(sha256(bytes), result.fingerprint());
        var reopened = Json.MAPPER.readValue(bytes, SourceCollector.Frozen.class);
        assertEquals("daily-v1", reopened.definitionVersion());
        assertEquals(DAY, reopened.logicalDate());
        assertEquals("2026-09-28", reopened.rows().getFirst().get("trade_date"));
        assertNull(reopened.rows().getFirst().get("low"));
        assertThrows(UnsupportedOperationException.class, () -> reopened.rows().getFirst().put("open", 3.0));
    }

    @Test void realDailySourceReceiptAndReopenRetainTheOriginalBytes() throws Exception {
        var calls = new ArrayList<Map<String,Object>>();
        var fake = new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract, Map<String,Object> parameters,
                    PageExecutor.Consumer consumer, PageExecutor.Validator validator, BooleanSupplier cancelled) throws Exception {
                assertSame(DailySource.BY_DATE, contract);
                calls.add(new LinkedHashMap<>(parameters));
                var row = raw();
                validator.validate(row);
                consumer.accept(new PageExecutor.Page(List.of(row), null, false, null),
                        new PageExecutor.Receipt(1, 0, null, 1, "test-page", null));
                return new PageExecutor.Completed(1, 1, null);
            }
        };
        var page = new DailySource(fake, temp).fetch(DAY, () -> fail("Uncapped source must not fetch an inventory"), () -> false);
        assertEquals(List.of(Map.of("trade_date", "20260928")), calls);
        assertArrayEquals(golden("source-receipt.json"), Files.readAllBytes(Path.of(page.responseEvidence())));
        assertEquals(goldenText("source-receipt.sha256"), page.sourceFingerprint());
        var reopened = DailySource.reopen(Path.of(page.responseEvidence()), page.sourceFingerprint(), DAY);
        assertEquals(page.rows(), reopened.rows());
        assertArrayEquals(golden("daily-market-bar.json"), JobDefinitionJson.mapper().writeValueAsBytes(reopened.rows().getFirst()));
        assertArrayEquals(golden("daily-codec.bin"), DailyWritePort.CODEC.canonicalBytes(reopened.rows().getFirst()));
    }

    private static SourceCollector.Request request() {
        return new SourceCollector.Request("daily", DAY, CODES, "test-universe-v1", null);
    }
    private static Map<String,JsonNode> raw() throws Exception {
        return Json.MAPPER.readValue(golden("raw-row.json"), new TypeReference<LinkedHashMap<String,JsonNode>>() {});
    }
    static byte[] golden(String name) throws Exception {
        try (var stream = DailySemanticsCompatibilityTest.class.getResourceAsStream("/daily-t12/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }
    private static String goldenText(String name) throws Exception { return new String(golden(name), StandardCharsets.UTF_8); }
    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static void sortSet(ObjectNode node, String field) {
        var values = new ArrayList<JsonNode>(); node.get(field).forEach(values::add);
        values.sort(Comparator.comparing(JsonNode::asText));
        node.putArray(field).addAll(values);
    }
}
