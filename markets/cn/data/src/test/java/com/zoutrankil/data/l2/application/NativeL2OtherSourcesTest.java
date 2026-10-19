package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.l2.L2DatasetManifest;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeatures;
import com.zoutrankil.data.domain.l2.L2T0TrainingLabels;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real compressed files cover D085 and each native minute/label receipt adapter. */
class NativeL2OtherSourcesTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);
    private static final String SYMBOL = "000001.SZ";
    @TempDir Path root;

    @Test void manifestPreservesFullSourceBudgetsFilterPagesAndEmptyWindow() throws Exception {
        var schema = manifestSchema();
        write(partition("l2_dataset_manifest").resolve("part-00000.parquet"), schema,
                List.of(receipt(schema, SYMBOL, 0, true, "{}"), receipt(schema, "000002.SZ", 0, true, "{}")));
        var source = new L2DatasetManifestParquetSource(root);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        assertEquals(2, inspection.sourceRows()); assertEquals(1, inspection.selectedRows()); assertEquals(1, inspection.pages());
        var pages = new ArrayList<SyncJobRunner.Page<L2DatasetManifest>>();
        var completion = source.stream(inspection, List.of(SYMBOL), 10, 10, pages::add, () -> false, Duration.ofSeconds(30));
        assertTrue(completion.complete()); assertEquals(SYMBOL, pages.getFirst().rows().getFirst().symbol());
        assertTrue(pages.getFirst().responseEvidence().contains("sourceRowsInPage\":2"));
        assertNotEquals(inspection.sourceFingerprint(), pages.getFirst().sourceFingerprint());
        assertThrows(IOException.class, () -> source.inspect(DAY, DAY, List.of(SYMBOL), 1, 10));
        var empty = source.inspect(DAY.plusDays(1), DAY.plusDays(1), List.of(), 10, 10);
        assertEquals(0, empty.files()); assertEquals(0, empty.selectedRows());
        assertTrue(source.stream(empty, List.of(), 10, 10, ignored -> fail("Empty window emitted rows"),
                () -> false, Duration.ofSeconds(30)).complete());
    }

    @Test void manifestRejectsDuplicateInvalidJsonNoncontiguousPartAndReorderedFields() throws Exception {
        var schema = manifestSchema();
        Path file = partition("l2_dataset_manifest").resolve("part-00000.parquet");
        var receipt = receipt(schema, SYMBOL, 0, true, "{}");
        write(file, schema, List.of(receipt, receipt));
        assertThrows(IOException.class, () -> new L2DatasetManifestParquetSource(root).inspect(DAY, DAY, List.of(), 10, 10));
        write(file, schema, List.of(receipt(schema, SYMBOL, 0, true, "{")));
        assertThrows(IOException.class, () -> new L2DatasetManifestParquetSource(root).inspect(DAY, DAY, List.of(), 10, 10));
        Files.delete(file);
        write(file.resolveSibling("part-00001.parquet"), schema, List.of(receipt(schema, SYMBOL, 1, true, "{}")));
        assertThrows(IOException.class, () -> new L2DatasetManifestParquetSource(root).inspect(DAY, DAY, List.of(), 10, 10));
        Files.delete(file.resolveSibling("part-00001.parquet"));
        var fields = new ArrayList<>(schema.getFields()); java.util.Collections.swap(fields, 0, 1);
        var reordered = new MessageType("manifest", fields);
        write(file, reordered, List.of(receipt(reordered, SYMBOL, 0, true, "{}")));
        assertThrows(IOException.class, () -> new L2DatasetManifestParquetSource(root).inspect(DAY, DAY, List.of(), 10, 10));
    }

    @Test void allMinuteProductsReadFrozenFieldsAndLatestPerSymbolReceipt() throws Exception {
        for (var product : NativeL2CertifiedFeaturesReader.Product.values()) {
            fixture(product, List.of(SYMBOL), List.of(1), true, false, false);
            var scan = NativeL2CertifiedFeaturesReader.scan(product, root, DAY, DAY, List.of(SYMBOL), 10, 10,
                    NativeL2DailyParquetReader.Control.of(() -> false, Duration.ofSeconds(30)));
            assertEquals(1, scan.inspection().path("selectedRows").intValue());
            assertEquals(product.columns().size(), scan.batches().getFirst().rows().getFirst().size());
            assertEquals(1, scan.batches().getFirst().id());
            assertEquals(2, scan.inspection().path("files").intValue());
        }
        var intraday = new L2IntradayBarFeaturesParquetSource(root);
        fixture(NativeL2CertifiedFeaturesReader.Product.INTRADAY, List.of(SYMBOL), List.of(1), true, false, false);
        var inspected = intraday.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        var pages = new ArrayList<SyncJobRunner.Page<L2IntradayBarFeatures>>();
        assertTrue(intraday.stream(inspected, List.of(SYMBOL), 10, 10, pages::add, () -> false, Duration.ofSeconds(30)).complete());
        assertEquals(DAY.atTime(1, 30).toInstant(ZoneOffset.UTC), pages.getFirst().rows().getFirst().minute());
        fixture(NativeL2CertifiedFeaturesReader.Product.EVENT, List.of(SYMBOL), List.of(0), true, false, false);
        var event = new L2EventResponseFeaturesParquetSource(root);
        assertTrue(event.stream(event.inspect(DAY, DAY, List.of(SYMBOL), 10, 10), List.of(SYMBOL), 10, 10,
                ignored -> {}, () -> false, Duration.ofSeconds(30)).complete());
    }

    @Test void pagesAccountForEachCertifiedBatchAndPreserveNullOutcomeCounts() throws Exception {
        fixture(NativeL2CertifiedFeaturesReader.Product.TRAINING, List.of(SYMBOL, "000002.SZ"), List.of(0, 1), true, false, false);
        var source = new L2T0TrainingLabelsParquetSource(root);
        var symbols = List.of(SYMBOL, "000002.SZ");
        var inspection = source.inspect(DAY, DAY, symbols, 10, 10);
        assertEquals(2, inspection.selectedRows()); assertEquals(2, inspection.pages());
        assertEquals(6, inspection.outcomeCoverageByHorizon().size());
        assertEquals(2L, inspection.outcomeCoverageByHorizon().get("1m").get("sell_binary_zero_with_missing_alpha"));
        assertEquals(0L, inspection.outcomeCoverageByHorizon().get("1m").get("sell_alpha_non_null"));
        var pages = new ArrayList<SyncJobRunner.Page<L2T0TrainingLabels>>();
        var completion = source.stream(inspection, symbols, 10, 10, pages::add, () -> false, Duration.ofSeconds(30));
        assertEquals(2, completion.pages()); assertNotEquals(pages.get(0).cursor(), pages.get(1).cursor());
        assertTrue(pages.getFirst().responseEvidence().contains("manifestReceiptFingerprint"));
    }

    @Test void eventFullKeyRetainsDifferentEventCategoriesAtTheSameSymbolMinute() throws Exception {
        var product = NativeL2CertifiedFeaturesReader.Product.EVENT;
        fixture(product, List.of(SYMBOL), List.of(0), true, false, false);
        var schema = featureSchema(product, false);
        var events = com.zoutrankil.data.domain.l2.L2EventResponseFeatureField.EVENT_TYPE_ALLOWED.stream().sorted().toList();
        write(partition(product.dataset).resolve("part-00000.parquet"), schema, List.of(
                feature(schema, product, SYMBOL, Map.of("event_type", events.get(0))),
                feature(schema, product, SYMBOL, Map.of("event_type", events.get(1)))));
        var source = new L2EventResponseFeaturesParquetSource(root);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        assertEquals(2, inspection.selectedRows());
        var pages = new ArrayList<SyncJobRunner.Page<com.zoutrankil.data.domain.l2.L2EventResponseFeatures>>();
        source.stream(inspection, List.of(SYMBOL), 10, 10, pages::add, () -> false, Duration.ofSeconds(30));
        var rows = pages.getFirst().rows();
        assertEquals(rows.get(0).minute(), rows.get(1).minute());
        assertNotEquals(rows.get(0).key(), rows.get(1).key());
    }

    @Test void failedReceiptDuplicateBusinessKeyChangedSourceAndCancellationEmitNoPages() throws Exception {
        var product = NativeL2CertifiedFeaturesReader.Product.INTRADAY;
        fixture(product, List.of(SYMBOL), List.of(0), false, false, false);
        var source = new L2IntradayBarFeaturesParquetSource(root);
        assertThrows(IOException.class, () -> source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
        fixture(product, List.of(SYMBOL), List.of(0), true, true, false);
        assertThrows(IOException.class, () -> source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
        fixture(product, List.of(SYMBOL), List.of(0), true, false, false);
        var inspection = source.inspect(DAY, DAY, List.of(SYMBOL), 10, 10);
        var pages = new ArrayList<SyncJobRunner.Page<L2IntradayBarFeatures>>();
        assertThrows(CancellationException.class, () -> source.stream(inspection, List.of(SYMBOL), 10, 10,
                pages::add, () -> true, Duration.ofSeconds(30)));
        fixture(product, List.of(SYMBOL), List.of(0), false, false, false);
        assertThrows(IOException.class, () -> source.stream(inspection, List.of(SYMBOL), 10, 10,
                pages::add, () -> false, Duration.ofSeconds(30)));
        assertTrue(pages.isEmpty());
    }

    @Test void timezoneChangedSchemaAndInvalidTrainingFormulaFailBeforePages() throws Exception {
        fixture(NativeL2CertifiedFeaturesReader.Product.EVENT, List.of(SYMBOL), List.of(0), true, false, true);
        assertThrows(IOException.class, () -> new L2EventResponseFeaturesParquetSource(root).inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
        var product = NativeL2CertifiedFeaturesReader.Product.TRAINING;
        fixture(product, List.of(SYMBOL), List.of(0), true, false, false);
        var schema = featureSchema(product, false);
        // Nonzero commission with a zero advertised round-trip cost violates the frozen formula.
        var bad = feature(schema, product, SYMBOL, Map.of("commission_rate", 0.01));
        write(partition(product.dataset).resolve("part-00000.parquet"), schema, List.of(bad));
        assertThrows(IOException.class, () -> new L2T0TrainingLabelsParquetSource(root).inspect(DAY, DAY, List.of(SYMBOL), 10, 10));
    }

    private void fixture(NativeL2CertifiedFeaturesReader.Product product, List<String> symbols, List<Integer> batches,
            boolean certified, boolean duplicate, boolean utc) throws Exception {
        var manifests = new ArrayList<Group>(); var manifestSchema = manifestSchema();
        for (int index = 0; index < symbols.size(); index++) {
            int batch = batches.get(index);
            Path path = partition(product.dataset).resolve("part-%05d.parquet".formatted(batch));
            var schema = featureSchema(product, utc);
            var row = feature(schema, product, symbols.get(index));
            write(path, schema, duplicate ? List.of(row, row) : List.of(row));
            manifests.add(receipt(manifestSchema, symbols.get(index), batch, certified,
                    NativeL2SourceSupport.JSON.writeValueAsString(Map.of(product.receipt, root.relativize(path).toString().replace('\\', '/')))));
            if (batch > 0) manifests.add(receipt(manifestSchema, symbols.get(index), batch - 1, true,
                    NativeL2SourceSupport.JSON.writeValueAsString(Map.of(product.receipt, "retired-old-source.parquet"))));
        }
        write(partition("l2_dataset_manifest").resolve("part-00000.parquet"), manifestSchema, manifests);
    }

    private static MessageType manifestSchema() {
        var fields = new LinkedHashMap<String, StorageType>();
        for (String name : List.of("trade_date", "symbol", "market", "board", "source_root", "output_root", "feature_version",
                "daily_feature_ok", "t0_ok", "raw_row_counts", "output_paths", "cost_config", "horizons_min", "errors", "batch_id"))
            fields.put(name, name.equals("batch_id") ? StorageType.LONG : List.of("daily_feature_ok", "t0_ok").contains(name) ? StorageType.BOOLEAN : StorageType.STRING);
        return schema(fields, false);
    }
    private static Group receipt(MessageType schema, String symbol, long batch, boolean certified, String outputs) {
        return new SimpleGroupFactory(schema).newGroup().append("trade_date", "20260921").append("symbol", symbol)
                .append("market", "SZ").append("board", "MAIN").append("feature_version", "reference")
                .append("daily_feature_ok", true).append("t0_ok", certified).append("raw_row_counts", "{}")
                .append("output_paths", outputs).append("cost_config", "{}").append("horizons_min", "[1,3,5,10,15,30]")
                .append("errors", "[]").append("batch_id", batch);
    }
    private static MessageType featureSchema(NativeL2CertifiedFeaturesReader.Product product, boolean utc) {
        return schema(product.columns(), utc);
    }
    private static MessageType schema(Map<String, StorageType> columns, boolean utc) {
        var builder = Types.buildMessage();
        columns.forEach((name, type) -> {
            var value = Types.optional(switch (type) {
                case TIMESTAMP, LONG -> PrimitiveTypeName.INT64; case STRING, SYMBOL -> PrimitiveTypeName.BINARY;
                case BOOLEAN -> PrimitiveTypeName.BOOLEAN; case DOUBLE -> PrimitiveTypeName.DOUBLE; default -> throw new IllegalStateException();
            });
            if (type == StorageType.TIMESTAMP) value.as(LogicalTypeAnnotation.timestampType(utc, LogicalTypeAnnotation.TimeUnit.NANOS));
            if (type == StorageType.STRING || type == StorageType.SYMBOL) value.as(LogicalTypeAnnotation.stringType());
            builder.addField(value.named(name));
        });
        return builder.named("l2");
    }
    private static Group feature(MessageType schema, NativeL2CertifiedFeaturesReader.Product product, String symbol) {
        return feature(schema, product, symbol, Map.of());
    }
    private static Group feature(MessageType schema, NativeL2CertifiedFeaturesReader.Product product, String symbol, Map<String, Object> overrides) {
        var row = new SimpleGroupFactory(schema).newGroup();
        product.columns().forEach((name, type) -> {
            switch (type) {
                case TIMESTAMP -> row.append(name, DAY.atTime(9, 30).toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L);
                case LONG -> row.append(name, 0L);
                case DOUBLE -> {
                    if (product != NativeL2CertifiedFeaturesReader.Product.TRAINING || !name.contains("alpha_") && !name.startsWith("future_"))
                        row.append(name, (Double) overrides.getOrDefault(name, 0.0));
                }
                case STRING, SYMBOL -> row.append(name, switch (name) {
                    case "trade_date" -> "20260921"; case "symbol" -> symbol; case "market" -> "SZ"; case "board" -> "MAIN";
                    case "event_type" -> (String) overrides.getOrDefault(name,
                            com.zoutrankil.data.domain.l2.L2EventResponseFeatureField.EVENT_TYPE_ALLOWED.iterator().next());
                    case "executability_reason" -> "wide_spread_or_thin_depth"; case "policy_label" -> "rule_data_missing";
                    case "policy_reason" -> "needs_inventory_limit_st_price_cage_inputs"; case "primary_t0_side" -> "sell_first_inventory_required";
                    default -> "native-reference";
                });
                default -> throw new IllegalStateException();
            }
        });
        return row;
    }
    private Path partition(String dataset) { return root.resolve(dataset).resolve("year=2026/month=09/trade_date=20260921"); }
    private static void write(Path file, MessageType schema, List<Group> rows) throws IOException {
        Files.createDirectories(file.getParent()); Files.deleteIfExists(file);
        try (var writer = ExampleParquetWriter.builder(new LocalOutputFile(file)).withType(schema)
                .withConf(new PlainParquetConfiguration()).withCompressionCodec(CompressionCodecName.SNAPPY).build()) {
            for (var row : rows) writer.write(row);
        }
    }
}
