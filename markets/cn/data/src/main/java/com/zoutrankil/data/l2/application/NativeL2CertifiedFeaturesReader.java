package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.l2.L2EventResponseFeatureField;
import com.zoutrankil.data.domain.l2.L2IntradayBarFeatureField;
import com.zoutrankil.data.domain.l2.L2T0TrainingLabelField;
import com.zoutrankil.data.l2.mapper.L2EventResponseFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.mapper.L2T0TrainingLabelsMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import static com.zoutrankil.data.l2.application.NativeL2SourceSupport.*;

/** Native D087/D088/D089 scans selected by the latest successful per-symbol D085 receipt. */
final class NativeL2CertifiedFeaturesReader {
    enum Product {
        INTRADAY("l2_intraday_bar_features", "intraday_bars", "l2-intraday-bar-features-parquet-v1"),
        EVENT("l2_event_response_features", "event_response", "l2-event-response-features-parquet-v1"),
        TRAINING("l2_t0_training_labels", "training_labels", "l2-t0-training-labels-parquet-v1");
        final String dataset, receipt, version;
        Product(String dataset, String receipt, String version) { this.dataset = dataset; this.receipt = receipt; this.version = version; }
        LinkedHashMap<String, StorageType> columns() {
            var result = new LinkedHashMap<String, StorageType>();
            switch (this) {
                case INTRADAY -> { for (var field : L2IntradayBarFeatureField.values()) result.put(field.fieldName(), field.storageType()); }
                case EVENT -> { for (var field : L2EventResponseFeatureField.values()) result.put(field.fieldName(), field.storageType()); }
                case TRAINING -> { for (var field : L2T0TrainingLabelField.values()) result.put(field.fieldName(), field.storageType()); }
            }
            return result;
        }
        void validate(ObjectNode row, LocalDate day) throws IOException {
            switch (this) {
                case INTRADAY -> new L2IntradayBarFeaturesMapper().fromParquet(row, day);
                case EVENT -> new L2EventResponseFeaturesMapper().fromParquet(row, day);
                case TRAINING -> new L2T0TrainingLabelsMapper().fromParquet(row, day);
            }
        }
    }
    record Batch(LocalDate day, int id, List<ObjectNode> rows, String receiptFingerprint, String featurePath) {}
    record Scan(ObjectNode inspection, List<Batch> batches, List<ObjectNode> files) {}
    private static final int PAGE_ROWS = 200;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private NativeL2CertifiedFeaturesReader() {}

    static Scan scan(Product product, Path requestedRoot, LocalDate from, LocalDate to, List<String> symbols,
            int maxRows, int maxFiles, NativeL2DailyParquetReader.Control control) throws Exception {
        control.run();
        if (maxRows < 1 || maxFiles < 1) throw new IllegalArgumentException("Positive L2 source budgets required");
        Path root = root(requestedRoot);
        var files = new TreeMap<String, ObjectNode>();
        var discovered = new LinkedHashMap<LocalDate, List<Path>>();
        var batches = new ArrayList<Batch>();
        var keys = new TreeSet<String>();
        int sourceRows = 0, selectedRows = 0, pages = 0;
        long[] outputBytes = {0};
        Set<String> selected = Set.copyOf(symbols);
        var columns = product.columns();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            control.run();
            Path directory = partition(root, "l2_dataset_manifest", day);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            var manifests = parts(root, directory, false);
            discovered.put(day, manifests);
            var latest = new TreeMap<String, ObjectNode>();
            var receiptKeys = new TreeSet<String>();
            LocalDate date = day;
            for (Path file : manifests) {
                addFile(root, file, files, maxFiles, MAX_BYTES, control);
                var schema = NativeL2ParquetReader.schema(file);
                for (var required : Map.of("trade_date", "string", "symbol", "string", "batch_id", "int64",
                        "t0_ok", "bool", "output_paths", "string").entrySet())
                    if (!required.getValue().equals(schema.get(required.getKey())))
                        throw new IOException("L2 manifest lacks the typed T0 receipt fields");
                sourceRows = Math.addExact(sourceRows, Math.toIntExact(NativeL2ParquetReader.read(file, null,
                        DECODED_PART_ROWS, control, row -> {
                    String symbol = text(row, "symbol");
                    if (!selected.contains(symbol)) return;
                    if (!text(row, "trade_date").replace("-", "").equals(BASIC.format(date)))
                        throw new IOException("L2 receipt trade_date differs from its partition");
                    int id = batch(row);
                    if (!receiptKeys.add(symbol + ":" + id)) throw new IOException("L2 manifest repeats a symbol/batch receipt");
                    var previous = latest.get(symbol);
                    if (previous == null || batch(previous) < id) latest.put(symbol, row);
                })));
            }
            if (!latest.keySet().equals(selected)) throw new IOException("L2 manifest does not cover every selected symbol");
            var byBatch = new TreeMap<Integer, Set<String>>();
            var receipts = new TreeMap<Integer, List<ObjectNode>>();
            var paths = new TreeMap<Integer, Path>();
            Path featureDirectory = partition(root, product.dataset, day);
            var featureCandidates = parts(root, featureDirectory, false);
            for (var entry : latest.entrySet()) {
                var receipt = entry.getValue();
                if (!receipt.path("t0_ok").isBoolean() || !receipt.path("t0_ok").booleanValue())
                    throw new IOException("L2 latest manifest batch is not certified t0_ok");
                int id = batch(receipt);
                var output = JSON.readTree(text(receipt, "output_paths"));
                if (output == null || !output.isObject() || !output.path(product.receipt).isTextual())
                    throw new IOException("L2 manifest has no approved feature output receipt");
                Path expected = featureDirectory.resolve("part-%05d.parquet".formatted(id));
                Path raw = Path.of(output.path(product.receipt).textValue());
                Path receiptPath = (raw.isAbsolute() ? raw : root.resolve(raw)).toAbsolutePath().normalize();
                if (!receiptPath.equals(expected.toAbsolutePath().normalize()) || !featureCandidates.contains(expected))
                    throw new IOException("L2 output receipt differs from its expected date/batch part");
                paths.put(id, expected);
                byBatch.computeIfAbsent(id, ignored -> new TreeSet<>()).add(entry.getKey());
                // Sorted JSON keys match the receipt protocol's canonical hash.
                receipts.computeIfAbsent(id, ignored -> new ArrayList<>()).add(JSON.valueToTree(new TreeMap<>(Map.of(
                        "batch_id", id, "date", BASIC.format(day), product.receipt, relative(root, expected),
                        "symbol", entry.getKey(), "t0_ok", true))));
            }
            for (var entry : byBatch.entrySet()) {
                Path file = paths.get(entry.getKey());
                addFile(root, file, files, maxFiles, MAX_BYTES, control);
                var actual = NativeL2ParquetReader.schema(file);
                validateSchema(columns, actual);
                var rows = new ArrayList<ObjectNode>();
                var observed = new TreeSet<String>();
                int remaining = maxRows - selectedRows;
                NativeL2ParquetReader.read(file, actual, DECODED_PART_ROWS, control, row -> {
                    String symbol = text(row, "symbol");
                    if (!entry.getValue().contains(symbol)) return;
                    normalizeNumeric(columns, row);
                    product.validate(row, date);
                    String key = symbol + ":" + text(row, "minute")
                            + (product == Product.EVENT ? ":" + text(row, "event_type") : "");
                    if (!keys.add(key)) throw new IOException("L2 source repeats a complete business key");
                    if (rows.size() >= remaining) throw new IOException("L2 selected rows exceed the frozen row budget");
                    outputBytes[0] = Math.addExact(outputBytes[0], JSON.writeValueAsBytes(row).length);
                    if (outputBytes[0] > MAX_BYTES) throw new IOException("L2 decoded rows exceed their frozen byte budget");
                    observed.add(symbol); rows.add(row);
                });
                if (!observed.equals(entry.getValue())) throw new IOException("L2 feature symbols disagree with the manifest receipt");
                rows.sort(Comparator.comparing((ObjectNode row) -> row.path("minute").textValue())
                        .thenComparing(row -> row.path("symbol").textValue()).thenComparing(row -> row.path("event_type").asText("")));
                selectedRows = Math.addExact(selectedRows, rows.size());
                pages = Math.addExact(pages, (rows.size() + PAGE_ROWS - 1) / PAGE_ROWS);
                batches.add(new Batch(day, entry.getKey(), List.copyOf(rows), sha(JSON.writeValueAsBytes(receipts.get(entry.getKey()))), relative(root, file)));
            }
        }
        if (batches.isEmpty() || selectedRows == 0) throw new IOException("L2 interval contains no certified feature rows");
        var inventory = List.copyOf(files.values());
        var inspection = JSON.createObjectNode().put("kind", "inspection").put("from", BASIC.format(from)).put("to", BASIC.format(to))
                .put("sourceRows", sourceRows).put("selectedRows", selectedRows).put("files", files.size()).put("pages", pages)
                .put("sourceBytes", inventory.stream().mapToLong(file -> file.path("bytes").longValue()).sum())
                .put("sourceFingerprint", sha(JSON.writeValueAsBytes(inventory)))
                .put("schemaFingerprint", sha(JSON.writeValueAsBytes(columns.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue().name())).toList())))
                .put("parserVersion", product.version).put("rootIdentity", sha(root.toString().getBytes(StandardCharsets.UTF_8)))
                .put("completeForSelectedSymbols", true);
        var dates = inspection.putArray("dates"); discovered.keySet().forEach(day -> dates.add(BASIC.format(day)));
        if (product == Product.TRAINING) inspection.set("outcomeCoverageByHorizon", JSON.valueToTree(outcomes(batches)));
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Path directory = partition(root, "l2_dataset_manifest", day);
            var current = Files.exists(directory, LinkOption.NOFOLLOW_LINKS) ? parts(root, directory, false) : List.<Path>of();
            if (!current.equals(discovered.getOrDefault(day, List.of()))) throw new IOException("L2 manifest inventory changed during inspection");
        }
        verifyFiles(root, inventory, control);
        return new Scan(inspection, List.copyOf(batches), inventory);
    }

    private static void validateSchema(Map<String, StorageType> columns, Map<String, String> actual) throws IOException {
        if (!List.copyOf(columns.keySet()).equals(List.copyOf(actual.keySet()))) throw new IOException("L2 source field order/set differs from its frozen contract");
        for (var entry : columns.entrySet()) {
            String type = actual.get(entry.getKey());
            boolean supported = switch (entry.getValue()) {
                case TIMESTAMP -> "timestamp[ns]".equals(type);
                case STRING, SYMBOL -> "string".equals(type);
                case LONG -> "int64".equals(type);
                case DOUBLE -> Set.of("double", "float", "int64", "int32").contains(type);
                default -> false;
            };
            if (!supported) throw new IOException("L2 source scalar type changed: " + entry.getKey());
        }
    }

    private static void normalizeNumeric(Map<String, StorageType> columns, ObjectNode row) throws IOException {
        for (var entry : columns.entrySet()) if (entry.getValue() == StorageType.DOUBLE) {
            var value = row.get(entry.getKey());
            if (value == null || value.isNull()) continue;
            if (!value.isNumber()) throw new IOException("L2 numeric source field is not a number");
            double converted = value.doubleValue();
            if (!Double.isFinite(converted) || value.isIntegralNumber()
                    && java.math.BigDecimal.valueOf(converted).compareTo(value.decimalValue()) != 0)
                throw new IOException("L2 numeric source value cannot be represented exactly as DOUBLE");
            row.put(entry.getKey(), converted);
        }
    }

    private static Map<String, Map<String, Long>> outcomes(List<Batch> batches) {
        var rows = batches.stream().flatMap(batch -> batch.rows().stream()).toList();
        var result = new LinkedHashMap<String, Map<String, Long>>();
        for (String horizon : List.of("1m", "3m", "5m", "10m", "15m", "30m")) {
            String sell = "sell_first_gross_alpha_" + horizon, buy = "buy_first_aux_gross_alpha_" + horizon;
            var counts = new LinkedHashMap<String, Long>();
            counts.put("rows", (long) rows.size());
            counts.put("future_vwap_return_non_null", rows.stream().filter(row -> !row.path("future_vwap_return_" + horizon).isNull()).count());
            counts.put("future_mid_return_non_null", rows.stream().filter(row -> !row.path("future_mid_return_" + horizon).isNull()).count());
            counts.put("sell_alpha_non_null", rows.stream().filter(row -> !row.path(sell).isNull()).count());
            counts.put("buy_alpha_non_null", rows.stream().filter(row -> !row.path(buy).isNull()).count());
            counts.put("sell_binary_zero_with_missing_alpha", rows.stream().filter(row -> row.path(sell).isNull() && row.path("sell_first_opportunity_label_" + horizon).longValue() == 0).count());
            counts.put("buy_binary_zero_with_missing_alpha", rows.stream().filter(row -> row.path(buy).isNull() && row.path("buy_first_aux_opportunity_label_" + horizon).longValue() == 0).count());
            result.put(horizon, counts);
        }
        return result;
    }

    static void writeStream(Scan scan, Path output, NativeL2DailyParquetReader.Control control) throws Exception {
        var inspection = scan.inspection();
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            long[] bytes = {0};
            emit(writer, inspection.deepCopy().put("kind", "header"), bytes, MAX_BYTES, control);
            int pageNumber = 0;
            for (var batch : scan.batches()) for (int offset = 0; offset < batch.rows().size(); offset += PAGE_ROWS) {
                var page = JSON.createObjectNode().put("kind", "page").put("sourceFingerprint", text(inspection, "sourceFingerprint"))
                        .put("cursor", BASIC.format(batch.day()) + ":" + batch.id() + ":" + offset);
                var evidence = page.putObject("responseEvidence").put("date", BASIC.format(batch.day())).put("batchId", batch.id())
                        .put("sourceRowOffset", offset).put("page", ++pageNumber).put("manifestReceiptFingerprint", batch.receiptFingerprint())
                        .put("reader", "java-parquet-1.17.1");
                var part = scan.files().stream().filter(file -> file.path("path").textValue().equals(batch.featurePath())).findFirst().orElseThrow();
                evidence.putArray("featureParts").addObject().put("path", batch.featurePath()).put("sha256", text(part, "sha256"));
                var rows = page.putArray("rows"); batch.rows().subList(offset, Math.min(offset + PAGE_ROWS, batch.rows().size())).forEach(rows::add);
                emit(writer, page, bytes, MAX_BYTES, control);
            }
            emit(writer, JSON.createObjectNode().put("kind", "completion").put("sourceFingerprint", text(inspection, "sourceFingerprint"))
                    .put("files", inspection.path("files").intValue()).put("sourceRows", inspection.path("sourceRows").intValue())
                    .put("returnedRows", inspection.path("selectedRows").intValue()).put("pages", pageNumber)
                    .put("complete", true).put("completeForSelectedSymbols", true), bytes, MAX_BYTES, control);
        }
    }
}
