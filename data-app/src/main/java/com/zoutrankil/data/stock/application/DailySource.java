package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.client.dto.TushareDailyDto;
import com.zoutrankil.data.domain.DailyMarketBar;
import com.zoutrankil.data.domain.DailySemantics;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.stock.mapper.DailyMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One trade-date request; an exact 6000-row cap is rejected and retried in supported code groups. */
public final class DailySource {
    public static final int UNPAGED_ROW_CAP = 6000;
    public static final int CODE_GROUP_SIZE = 1000;
    public static final List<String> FIELDS = DailySemantics.V1.sourceFields();
    public static final PageContract BY_DATE = new PageContract("daily", FIELDS,
            DailySemantics.V1.businessKey(), Set.of("trade_date", "ts_code"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, UNPAGED_ROW_CAP + 1, UNPAGED_ROW_CAP + 1, 1,
            UNPAGED_ROW_CAP + 1, "Tushare daily documentation: unpaged daily query is capped at 6000 rows; request bound is 6001 so exact cap rows are detectable");
    private static final PageContract BY_CODE_GROUP = new PageContract("daily", FIELDS,
            DailySemantics.V1.businessKey(), Set.of("trade_date", "ts_code"), PageContract.Paging.NONE,
            PageContract.Completion.SHORT_PAGE, null, null, CODE_GROUP_SIZE, CODE_GROUP_SIZE + 1, 1,
            CODE_GROUP_SIZE, "Tushare daily documentation permits comma-separated ts_code filters; group size is bounded below the 6000-row cap");

    private final TusharePageService pages;
    private final Path evidence;
    private final DailyMapper mapper = new DailyMapper();

    public DailySource(TusharePageService pages, Path evidence) {
        this.pages = Objects.requireNonNull(pages);
        this.evidence = Objects.requireNonNull(evidence).toAbsolutePath().normalize();
    }

    public SyncJobRunner.Page<DailyMarketBar> fetch(LocalDate day, Supplier<List<String>> inventory,
                                                     BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(day);
        String basicDate = day.format(DateTimeFormatter.BASIC_ISO_DATE);
        var raw = new ArrayList<Map<String, JsonNode>>();
        boolean splitByCode = false;
        var completion = new int[2];
        List<Map<String, JsonNode>> cappedResponseRows = List.of();
        List<List<String>> fallbackGroups = new ArrayList<>();
        String inventoryFingerprint = null;
        try {
            var result = pages.execute(BY_DATE, Map.of("trade_date", basicDate),
                    (page, receipt) -> raw.addAll(page.rows()), row -> validateRow(row, day, null), cancelled);
            completion[0] = result.pages();
            completion[1] = result.rows();
            if (raw.size() >= UNPAGED_ROW_CAP) {
                splitByCode = true;
                cappedResponseRows = List.copyOf(raw);
                raw.clear();
                completion[0] = 0;
                completion[1] = 0;
            }
        } catch (PageExecutor.Truncated capped) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw capped;
            splitByCode = true;
            raw.clear();
            completion[0] = 0;
            completion[1] = 0;
            List<String> codes = List.copyOf(Objects.requireNonNull(inventory.get(), "D002 code inventory required on cap"));
            if (codes.isEmpty() || codes.size() > 10000 || new HashSet<>(codes).size() != codes.size()
                    || codes.stream().anyMatch(code -> !com.zoutrankil.data.domain.StockDetailInfo.validCode(code))) {
                throw new IllegalStateException("Bounded unique D002 stock-code inventory required for capped daily response");
            }
            inventoryFingerprint = sha256(String.join("\n", codes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int start = 0; start < codes.size(); start += CODE_GROUP_SIZE) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("Daily code-group fallback cancelled");
                var group = codes.subList(start, Math.min(codes.size(), start + CODE_GROUP_SIZE));
                fallbackGroups.add(List.copyOf(group));
                var groupCompletion = pages.execute(BY_CODE_GROUP,
                        Map.of("trade_date", basicDate, "ts_code", String.join(",", group)),
                        (page, receipt) -> raw.addAll(page.rows()),
                        row -> validateRow(row, day, Set.copyOf(group)), cancelled);
                completion[0] = Math.addExact(completion[0], groupCompletion.pages());
                completion[1] = Math.addExact(completion[1], groupCompletion.rows());
                if (raw.size() > 10000) throw new IllegalStateException("Daily fallback exceeds source-row budget");
            }
        }
        if (splitByCode && fallbackGroups.isEmpty()) {
            // The 6000-row response was available in memory, but a larger collection still needs full coverage.
            List<String> codes = List.copyOf(Objects.requireNonNull(inventory.get(), "D002 code inventory required on cap"));
            if (codes.isEmpty() || codes.size() > 10000 || new HashSet<>(codes).size() != codes.size()
                    || codes.stream().anyMatch(code -> !com.zoutrankil.data.domain.StockDetailInfo.validCode(code))) {
                throw new IllegalStateException("Bounded unique D002 stock-code inventory required for capped daily response");
            }
            inventoryFingerprint = sha256(String.join("\n", codes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int start = 0; start < codes.size(); start += CODE_GROUP_SIZE) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("Daily code-group fallback cancelled");
                var group = codes.subList(start, Math.min(codes.size(), start + CODE_GROUP_SIZE));
                fallbackGroups.add(List.copyOf(group));
                var groupCompletion = pages.execute(BY_CODE_GROUP,
                        Map.of("trade_date", basicDate, "ts_code", String.join(",", group)),
                        (page, receipt) -> raw.addAll(page.rows()),
                        row -> validateRow(row, day, Set.copyOf(group)), cancelled);
                completion[0] = Math.addExact(completion[0], groupCompletion.pages());
                completion[1] = Math.addExact(completion[1], groupCompletion.rows());
                if (raw.size() > 10000) throw new IllegalStateException("Daily fallback exceeds source-row budget");
            }
        }
        var rowOrder = Comparator.comparing((Map<String, JsonNode> row) -> row.get("ts_code").asText())
                .thenComparing(row -> row.get("trade_date").asText());
        raw.sort(rowOrder);
        cappedResponseRows = cappedResponseRows.stream().sorted(rowOrder).toList();
        var typed = raw.stream().map(this::decode).map(mapper::fromSource).toList();
        var keys = new HashSet<DailyMarketBar.Key>();
        for (var row : typed) {
            if (!row.tradeDate().equals(day) || !keys.add(row.key()))
                throw new IllegalStateException("Daily source returned a duplicate key or a different trade date");
        }
        var receiptBody = new LinkedHashMap<String, Object>();
        receiptBody.put("endpoint", "daily");
        receiptBody.put("tradeDate", basicDate);
        receiptBody.put("fields", FIELDS);
        receiptBody.put("splitByCode", splitByCode);
        receiptBody.put("completionPages", completion[0]);
        receiptBody.put("returnedRows", typed.size());
        receiptBody.put("initialCappedRowCount", cappedResponseRows.size());
        receiptBody.put("initialCappedRows", cappedResponseRows);
        receiptBody.put("fallbackGroups", fallbackGroups);
        receiptBody.put("inventoryFingerprint", inventoryFingerprint);
        receiptBody.put("rawRows", raw);
        byte[] bytes = JobDefinitionJson.canonicalMapper()
                .writeValueAsBytes(receiptBody);
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalArgumentException("Daily source receipt exceeds 16 MiB");
        Files.createDirectories(evidence);
        Path file = evidence.resolve("daily-" + basicDate + "-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(file, bytes);
        return new SyncJobRunner.Page<>(typed, sha256(bytes), file.toString(), basicDate);
    }

    public static SyncJobRunner.Page<DailyMarketBar> reopen(Path receipt, String expectedHash,
                                                              LocalDate expectedDate) throws Exception {
        if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}") || Files.size(receipt) > 16L * 1024 * 1024)
            throw new IllegalArgumentException("Bounded daily receipt and SHA-256 required");
        byte[] bytes = FileEvidenceStore.readBounded(receipt, 16 * 1024 * 1024,
                () -> new IllegalArgumentException("Bounded daily receipt and SHA-256 required"));
        if (!sha256(bytes).equals(expectedHash)) throw new IllegalStateException("Daily source receipt fingerprint changed");
        var json = JobDefinitionJson.mapper();
        var proof = json.readTree(bytes);
        String expected = expectedDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!"daily".equals(proof.path("endpoint").asText())
                || !expected.equals(proof.path("tradeDate").asText())
                || !json.valueToTree(FIELDS).equals(proof.path("fields"))
                || !proof.path("returnedRows").canConvertToInt()
                || !proof.path("rawRows").isArray()
                || proof.path("rawRows").size() != proof.path("returnedRows").asInt())
            throw new IllegalStateException("Daily source receipt scope/completion differs");
        var raw = json.convertValue(proof.path("rawRows"), new TypeReference<List<Map<String, JsonNode>>>() {});
        var split = proof.path("splitByCode");
        if (!split.isBoolean()) throw new IllegalStateException("Daily receipt is missing its split decision");
        if (split.booleanValue()) {
            var groupsNode = proof.path("fallbackGroups");
            var capRowsNode = proof.path("initialCappedRows");
            var capCount = proof.path("initialCappedRowCount");
            var inventoryHash = proof.path("inventoryFingerprint").asText("");
            if (!groupsNode.isArray() || groupsNode.size() == 0 || !capRowsNode.isArray()
                    || !capCount.isIntegralNumber() || capCount.intValue() < 0
                    || !inventoryHash.matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Capped daily receipt lacks code-group completeness evidence");
            var allCodes = new ArrayList<String>();
            for (var group : groupsNode) {
                if (!group.isArray() || group.isEmpty() || group.size() > CODE_GROUP_SIZE)
                    throw new IllegalStateException("Invalid daily fallback code group");
                for (var code : group) {
                    if (!code.isTextual() || !com.zoutrankil.data.domain.StockDetailInfo.validCode(code.asText()))
                        throw new IllegalStateException("Invalid code in daily fallback receipt");
                    allCodes.add(code.asText());
                }
            }
            if (new HashSet<>(allCodes).size() != allCodes.size()
                    || !sha256(String.join("\n", allCodes).getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(inventoryHash)
                    || capCount.intValue() != capRowsNode.size())
                throw new IllegalStateException("Daily fallback inventory/cap evidence fingerprint differs");
            for (var capRow : json.convertValue(capRowsNode, new TypeReference<List<Map<String, JsonNode>>>() {})) {
                var bar = new DailyMapper().fromSource(decodeRow(capRow));
                if (!bar.tradeDate().equals(expectedDate) || !allCodes.contains(bar.tsCode()))
                    throw new IllegalStateException("Initial capped daily response is outside its receipt inventory");
            }
            for (var row : raw) {
                var bar = new DailyMapper().fromSource(decodeRow(row));
                if (!bar.tradeDate().equals(expectedDate) || !allCodes.contains(bar.tsCode()))
                    throw new IllegalStateException("Daily fallback row is outside its receipt inventory");
            }
        } else if (!proof.path("fallbackGroups").isArray() || proof.path("fallbackGroups").size() != 0
                || proof.path("initialCappedRowCount").asInt(-1) != 0
                || !proof.path("initialCappedRows").isArray() || proof.path("initialCappedRows").size() != 0
                || !proof.path("inventoryFingerprint").isNull()) {
            throw new IllegalStateException("Uncapped daily receipt unexpectedly contains code fallback groups");
        }
        var typed = new ArrayList<DailyMarketBar>();
        var keys = new HashSet<DailyMarketBar.Key>();
        var mapper = new DailyMapper();
        for (var row : raw) {
            var value = mapper.fromSource(decodeRow(row));
            if (!value.tradeDate().equals(expectedDate) || !keys.add(value.key()))
                throw new IllegalStateException("Daily receipt contains a duplicate key or out-of-scope row");
            typed.add(value);
        }
        return new SyncJobRunner.Page<>(typed, expectedHash, receipt.toAbsolutePath().normalize().toString(), expected);
    }

    private void validateRow(Map<String, JsonNode> row, LocalDate day, Set<String> allowedCodes) {
        var value = mapper.fromSource(decode(row));
        if (!value.tradeDate().equals(day) || allowedCodes != null && !allowedCodes.contains(value.tsCode()))
            throw new IllegalArgumentException("Daily row is outside the frozen trade-date/code scope");
    }

    private TushareDailyDto decode(Map<String, JsonNode> row) { return decodeRow(row); }

    private static TushareDailyDto decodeRow(Map<String, JsonNode> row) {
        return new TushareDailyDto(text(row, "ts_code"), text(row, "trade_date"),
                numeric(row, "open"), numeric(row, "high"), numeric(row, "low"), numeric(row, "close"),
                numeric(row, "pre_close"), numeric(row, "change"), numeric(row, "pct_chg"),
                numeric(row, "vol"), numeric(row, "amount"), numeric(row, "ah_vol"), numeric(row, "ah_amount"));
    }

    private static String text(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("Text Tushare daily field required: " + field);
        return value.textValue();
    }

    private static Double numeric(Map<String, JsonNode> row, String field) {
        JsonNode value = row.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Finite numeric Tushare daily field required: " + field);
        return value.doubleValue();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return FileEvidenceStore.sha256(bytes);
    }
}
