package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual JSONL decoder without launching a process or trusting retained fixtures. */
class L2ManifestDailySourceContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    static final String HASH = "a".repeat(64), SCHEMA = "b".repeat(64), ROOT = "c".repeat(64);
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualParserRetainsEvidenceRowsAndCompletion(boolean daily) throws Exception {
        var nodes = stream(daily);
        var pages = new ArrayList<SyncJobRunner.Page<?>>();
        var completion = parse(daily, nodes, pages, () -> false);
        assertEquals(1, completion.pages()); assertEquals(1, completion.rows()); assertTrue(completion.complete());
        assertEquals(JSON.writeValueAsString(nodes.get(2)), completion.evidence());
        assertEquals(JSON.writeValueAsString(nodes.get(1).get("responseEvidence")), pages.getFirst().responseEvidence());
        assertEquals("page-1", pages.getFirst().cursor()); assertEquals(1, pages.getFirst().rows().size());
        if (daily) assertNotEquals(HASH, pages.getFirst().sourceFingerprint());
        else assertEquals(HASH, pages.getFirst().sourceFingerprint());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rejectsMissingCompletionAfterDeliveringOnlyTheBoundedPage(boolean daily) throws Exception {
        var nodes = stream(daily); nodes.removeLast(); var pages = new ArrayList<SyncJobRunner.Page<?>>();
        assertThrows(IOException.class, () -> parse(daily, nodes, pages, () -> false));
        assertEquals(1, pages.size());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void changedInspectionRejectsBeforeAnyPage(boolean daily) throws Exception {
        var nodes = stream(daily); nodes.getFirst().put("schemaFingerprint", "d".repeat(64));
        var pages = new ArrayList<SyncJobRunner.Page<?>>();
        assertThrows(IOException.class, () -> parse(daily, nodes, pages, () -> false)); assertTrue(pages.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void malformedLineageRejectsBeforeAnyPage(boolean daily) throws Exception {
        var nodes = stream(daily); var evidence = (ObjectNode) nodes.get(1).get("responseEvidence");
        if (daily) ((ObjectNode)evidence.path("featureParts").get(0)).put("path", "l2_daily_features/../secret");
        else evidence.put("sourceSha256", "not-a-hash");
        var pages = new ArrayList<SyncJobRunner.Page<?>>();
        assertThrows(IOException.class, () -> parse(daily, nodes, pages, () -> false)); assertTrue(pages.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void wrongSymbolRejectsBeforeAnyPage(boolean daily) throws Exception {
        var nodes = stream(daily); ((ObjectNode)nodes.get(1).path("rows").get(0)).put("symbol", "000002.SZ");
        var pages = new ArrayList<SyncJobRunner.Page<?>>();
        assertThrows(IOException.class, () -> parse(daily, nodes, pages, () -> false)); assertTrue(pages.isEmpty());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void extraContentAfterCompletionAndWrongCountersCannotCertify(boolean daily) throws Exception {
        var nodes = stream(daily); nodes.add(nodes.get(1).deepCopy());
        assertThrows(IOException.class, () -> parse(daily, nodes, new ArrayList<>(), () -> false));
        nodes.removeLast(); nodes.get(2).put("returnedRows", 2);
        assertThrows(IOException.class, () -> parse(daily, nodes, new ArrayList<>(), () -> false));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void cancellationPrecedesReadingHeaderOrCallingConsumer(boolean daily) throws Exception {
        var pages = new ArrayList<SyncJobRunner.Page<?>>();
        assertThrows(CancellationException.class, () -> parse(daily, stream(daily), pages, () -> true));
        assertTrue(pages.isEmpty());
    }

    @Test void manifestAllowsCertifiedEmptyInspectionButDailyRequiresSelectedRows() {
        assertEquals(0, new L2DatasetManifestParquetSource.Inspection(DAY, DAY, List.of(), 0, 0, 0, 0, 0,
                HASH, SCHEMA, "l2-dataset-manifest-parquet-v1", true, false).selectedRows());
        assertThrows(IllegalArgumentException.class, () -> new L2DailyFeaturesParquetSource.Inspection(
                DAY, DAY, List.of(DAY), 0, 0, 0, 0, 0, HASH, SCHEMA, "l2-daily-features-parquet-v1", ROOT, true));
    }

    static Object inspection(boolean daily) {
        return daily ? new L2DailyFeaturesParquetSource.Inspection(DAY, DAY, List.of(DAY), 1, 1, 1, 1, 12,
                HASH, SCHEMA, "l2-daily-features-parquet-v1", ROOT, true)
                : new L2DatasetManifestParquetSource.Inspection(DAY, DAY, List.of(DAY), 1, 1, 1, 1, 12,
                HASH, SCHEMA, "l2-dataset-manifest-parquet-v1", true, false);
    }

    private SyncJobRunner.SourceCompletion parse(boolean daily, List<ObjectNode> nodes,
            List<SyncJobRunner.Page<?>> pages, BooleanSupplier cancelled) throws Exception {
        var lines = new ArrayList<String>(); for (var node : nodes) lines.add(JSON.writeValueAsString(node));
        Path path = directory.resolve("source.jsonl"); Files.write(path, lines, StandardCharsets.UTF_8);
        Object source = daily ? new L2DailyFeaturesParquetSource(directory, directory.resolve("never.py"), "never-python")
                : new L2DatasetManifestParquetSource(directory, directory.resolve("never.py"), "never-python");
        Object expected = inspection(daily);
        var method = source.getClass().getDeclaredMethod("consumeOutput", Path.class, expected.getClass(),
                List.class, SyncJobRunner.PageConsumer.class, BooleanSupplier.class);
        method.setAccessible(true);
        SyncJobRunner.PageConsumer<Object> consumer = pages::add;
        try { return (SyncJobRunner.SourceCompletion) method.invoke(source, path, expected, List.of("000001.SZ"), consumer, cancelled); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    static ArrayList<ObjectNode> stream(boolean daily) {
        var header = JSON.createObjectNode().put("kind", "header").put("from", "20260921").put("to", "20260921")
                .put("sourceRows", 1).put("selectedRows", 1).put("files", 1).put("pages", 1).put("sourceBytes", 12)
                .put("sourceFingerprint", HASH).put("schemaFingerprint", SCHEMA)
                .put("parserVersion", daily ? "l2-daily-features-parquet-v1" : "l2-dataset-manifest-parquet-v1");
        header.putArray("dates").add("20260921");
        if (daily) header.put("rootIdentity", ROOT).put("completeForSelectedSymbols", true);
        else header.putObject("quality").put("completeForDiscoveredFiles", true).put("externalCompletionManifest", false);
        var page = JSON.createObjectNode().put("kind", "page").put("cursor", "page-1").put("sourceFingerprint", HASH);
        var evidence = page.putObject("responseEvidence").put("date", "20260921");
        if (daily) evidence.putArray("featureParts").addObject().put("path", "l2_daily_features/20260921/part.parquet").put("sha256", SCHEMA);
        else evidence.put("sourceSha256", SCHEMA);
        var row = page.putArray("rows").addObject();
        if (daily) {
            for (var field : L2DailyFeatureField.values()) row.putNull(field.fieldName());
            row.put("ts", "2026-09-21T00:00:00").put("symbol", "000001.SZ");
        } else {
            for (String field : List.of("trade_date", "symbol", "market", "board", "source_root", "output_root", "feature_version",
                    "daily_feature_ok", "t0_ok", "raw_row_counts", "output_paths", "cost_config", "horizons_min", "errors", "batch_id")) row.putNull(field);
            row.put("trade_date", "20260921").put("symbol", "000001.SZ").put("batch_id", 1);
        }
        var completion = JSON.createObjectNode().put("kind", "completion").put("complete", true)
                .put("sourceFingerprint", HASH).put("files", 1).put("sourceRows", 1).put("returnedRows", 1).put("pages", 1);
        if (daily) completion.put("completeForSelectedSymbols", true);
        return new ArrayList<>(List.of(header, page, completion));
    }
}
