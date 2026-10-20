package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 从独立批准的预期键及已完成上游运行组装清单；不查询目标表推导应有证券。 */
public final class StrategyCoverageAssembler {
    private static final Map<String, Set<String>> UPSTREAM =
            Map.ofEntries(
                    Map.entry(
                            "stock_daily",
                            Set.of(
                                    "data.stk_factor",
                                    "data.stk_limit",
                                    "data.stk_suspend",
                                    "data.stk_st_daily",
                                    "data.daily_basic")),
                    Map.entry("l2_raw", Set.of("data.l2_daily_features")),
                    Map.entry("etf_factor", Set.of("data.etf_factor")),
                    Map.entry("etf_daily", Set.of("data.etf_daily")),
                    Map.entry("stock_metadata", Set.of("data.stock_detail_info")),
                    Map.entry("etf_reference", Set.of("data.etf_basic")),
                    Map.entry("etf_portfolio", Set.of("data.etf_portfolio")),
                    Map.entry("sentiment", Set.of("data.market_sentiment_daily")),
                    Map.entry("market_context", Set.of("data.regime_features_monitor_daily")),
                    Map.entry("calendar", Set.of("data.exchange_calendar")));

    public record Published(Path path, String sha256) {}

    private StrategyCoverageAssembler() {}

    public static Published assemble(
            Path input, String approvedSha, Path output, Object currentClocks) throws Exception {
        input = input.toAbsolutePath().normalize();
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        input,
                        128 * 1024 * 1024,
                        () -> new IllegalStateException("覆盖输入超过 128 MiB"));
        if (!approvedSha.matches("[a-f0-9]{64}")
                || !FileEvidenceStore.sha256(bytes).equals(approvedSha))
            throw new IllegalStateException("独立覆盖输入摘要不符");
        var json = JobDefinitionJson.mapper();
        JsonNode document = json.readTree(bytes);
        var schema = StrategyInputSchema.fromApproval(document.path("schema").asText());
        if (!StrategySourceCoverage.objectSha(currentClocks)
                .equals(document.path("sourceClocksSha256").asText()))
            throw new IllegalStateException("来源版本与批准输入不同");
        var names = new HashSet<String>();
        document.required("sources").fieldNames().forEachRemaining(names::add);
        if (!names.equals(schema.keys().keySet())) throw new IllegalStateException("覆盖输入缺来源");
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("schema", schema.coverageSchema());
        if (schema == StrategyInputSchema.V2) {
            if (!schema.inputSchema().equals(document.path("inputSchema").asText())
                    || !schema.keyEncoding().equals(document.path("keyEncoding").asText()))
                throw new IllegalStateException("v2 批准输入缺输入及键编码版本");
            manifest.put("inputSchema", schema.inputSchema());
            manifest.put("keyEncoding", schema.keyEncoding());
        }
        for (String key : List.of("start", "end", "warmupStart", "sourceClocksSha256"))
            manifest.put(key, document.required(key).asText());
        var sources = new TreeMap<String, Object>();
        Map<String, Object> boundRuns =
                schema == StrategyInputSchema.V2 ? new TreeMap<>() : new LinkedHashMap<>();
        for (var entry : schema.keys().entrySet()) {
            String name = entry.getKey();
            var item = document.path("sources").path(name);
            if (schema == StrategyInputSchema.V2
                    && !json.valueToTree(entry.getValue()).equals(item.path("businessKeys")))
                throw new IllegalStateException("v2 批准输入键名不符：" + name);
            var keys = item.required("expectedKeys");
            if (!keys.isArray() || keys.size() > 5000000)
                throw new IllegalStateException("预期键必须为有界数组");
            var rows = new ArrayList<Map<String, Object>>();
            for (var key : keys) {
                if (!key.isArray() || key.size() != entry.getValue().size())
                    throw new IllegalStateException("预期键维度错误：" + name);
                var row = new LinkedHashMap<String, Object>();
                for (int i = 0; i < key.size(); i++) {
                    if (!key.get(i).isTextual() || key.get(i).textValue().isBlank())
                        throw new IllegalStateException("预期键为空或不是文本");
                    row.put(entry.getValue().get(i), key.get(i).textValue());
                }
                rows.add(row);
            }
            if (rows.isEmpty() && !Set.of("l2_raw", "etf_portfolio").contains(name))
                throw new IllegalStateException("必需来源的预期范围为空：" + name);
            if (!item.path("upstreamRuns").isArray() || item.path("upstreamRuns").isEmpty())
                throw new IllegalStateException("缺真实上游运行：" + name);
            var upstreamJobs = new HashSet<String>();
            for (var upstream : item.path("upstreamRuns")) {
                Path ledger =
                        input.getParent().resolve(upstream.required("ledger").asText()).normalize();
                String id = upstream.required("runId").asText();
                var store = SyncRunLedger.openReadOnly(ledger);
                var run = store.get(id);
                var frozen = store.getRun(id);
                if (!Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state())) throw new IllegalStateException("上游未完成：" + name);
                if (!UPSTREAM.get(name).contains(frozen.jobId()))
                    throw new IllegalStateException("上游任务归属不同：" + name);
                upstreamJobs.add(frozen.jobId());
                boundRuns.put(
                        ledger + "|" + id,
                        Map.of(
                                "ledger",
                                ledger.toAbsolutePath().toString(),
                                "runId",
                                id,
                                "sha256",
                                StrategySourceCoverage.objectSha(List.of(frozen, run))));
            }
            if (!upstreamJobs.equals(UPSTREAM.get(name)))
                throw new IllegalStateException("缺必需上游任务凭证：" + name);
            Path certificate =
                    input.getParent().resolve(item.required("certificate").asText()).normalize();
            String digest = item.required("certificateSha256").asText();
            byte[] receipt =
                    FileEvidenceStore.readBounded(
                            certificate,
                            128 * 1024 * 1024,
                            () -> new IllegalStateException("上游凭证过大"));
            if (!digest.matches("[a-f0-9]{64}")
                    || !FileEvidenceStore.sha256(receipt).equals(digest))
                throw new IllegalStateException("上游凭证摘要不符：" + name);
            var source =
                    new LinkedHashMap<String, Object>(
                            Map.of(
                                    "expectedRows",
                                    rows.size(),
                                    "expectedKeysSha256",
                                    schema.keySha(name, rows),
                                    "certificate",
                                    certificate.toAbsolutePath().toString(),
                                    "certificateSha256",
                                    digest));
            if (schema == StrategyInputSchema.V2) source.put("businessKeys", entry.getValue());
            sources.put(name, source);
        }
        manifest.put("sources", sources);
        manifest.put("upstreamRuns", List.copyOf(boundRuns.values()));
        var history = document.required("pointInTime");
        Path historyPath = input.getParent().resolve(history.required("path").asText()).normalize();
        var pinned = new PointInTimeHistory(historyPath, history.required("sha256").asText());
        manifest.put(
                "pointInTime",
                Map.of("path", historyPath.toAbsolutePath().toString(), "sha256", pinned.sha256()));
        // 原批准文件也进入依赖链，发布前再次检查，防止组装过程中被修改。
        manifest.put("coverageInputs", Map.of("path", input.toString(), "sha256", approvedSha));
        if (!FileEvidenceStore.sha256(
                        FileEvidenceStore.readBounded(
                                input,
                                128 * 1024 * 1024,
                                () -> new IllegalStateException("覆盖输入变大")))
                .equals(approvedSha)) throw new IllegalStateException("覆盖输入已改变");
        byte[] published =
                schema == StrategyInputSchema.V2
                        ? json.copy()
                                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                                .writeValueAsBytes(manifest)
                        : json.writeValueAsBytes(manifest);
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        FileEvidenceStore.writeNewDurable(target, published);
        String sha = FileEvidenceStore.sha256(published);
        new StrategySourceCoverage(target.toString(), sha).revalidate();
        return new Published(target, sha);
    }
}
