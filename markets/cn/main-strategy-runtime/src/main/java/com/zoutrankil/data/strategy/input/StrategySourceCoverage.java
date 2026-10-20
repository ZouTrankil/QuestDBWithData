package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.repository.SyncRunLedger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 用已批准的来源清单核对完整业务键，不能拿本次查询结果给自己证明完整。 */
public final class StrategySourceCoverage {
    static final Map<String, List<String>> KEYS = StrategyInputSchema.V1.keys();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;
    private final String expectedSha256;
    private final JsonNode document;
    private final StrategyInputSchema schema;

    public StrategySourceCoverage(String file, String sha256) throws Exception {
        if (file == null || file.isBlank() || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalStateException("评分需要已批准的 source-coverage 文件和 SHA-256，不能仅凭日期有数据发布");
        path = Path.of(file).toAbsolutePath().normalize();
        expectedSha256 = sha256;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
            throw new IllegalStateException("评分来源清单缺失或超过 1 MiB");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 1024 * 1024 || !sha(bytes).equals(expectedSha256))
            throw new IllegalStateException("评分来源清单摘要不符");
        document = JSON.readTree(bytes);
        schema = StrategyInputSchema.fromCoverage(document.path("schema").asText());
        if (!document.path("sources").isObject()) throw new IllegalStateException("评分来源清单格式不符");
        if (schema == StrategyInputSchema.V2) {
            if (!schema.keyEncoding().equals(document.path("keyEncoding").asText())
                    || !schema.inputSchema().equals(document.path("inputSchema").asText()))
                throw new IllegalStateException("v2 覆盖清单缺明确的输入及键编码版本");
            for (var entry : schema.keys().entrySet()) {
                if (!JSON.valueToTree(entry.getValue())
                        .equals(document.path("sources").path(entry.getKey()).path("businessKeys")))
                    throw new IllegalStateException("v2 来源键声明不符：" + entry.getKey());
            }
        }
        var names = new HashSet<String>();
        document.path("sources").fieldNames().forEachRemaining(names::add);
        if (!names.equals(schema.keys().keySet()))
            throw new IllegalStateException("评分来源清单必须覆盖全部输入");
        revalidate();
    }

    public StrategyInputSchema inputSchema() {
        return schema;
    }

    public Map<String, Object> verify(
            LocalDate start,
            LocalDate end,
            LocalDate warmup,
            Map<String, List<Map<String, Object>>> tables,
            Object clocks)
            throws Exception {
        if (!start.toString().equals(document.path("start").asText())
                || !end.toString().equals(document.path("end").asText())
                || !warmup.toString().equals(document.path("warmupStart").asText())
                || !objectSha(clocks).equals(document.path("sourceClocksSha256").asText()))
            throw new IllegalStateException("评分来源清单的区间或数据库版本已变化，需要重新核验来源");
        if (!tables.keySet().equals(schema.keys().keySet()))
            throw new IllegalStateException("评分输入与覆盖清单不一致");
        for (var source : schema.keys().entrySet()) {
            var expected = document.path("sources").path(source.getKey());
            var rows = tables.get(source.getKey());
            if (!expected.path("expectedRows").isIntegralNumber()
                    || !expected.path("expectedRows").canConvertToLong()
                    || expected.path("expectedRows").asLong(-1) != rows.size()
                    || !schema.keySha(source.getKey(), rows)
                            .equals(expected.path("expectedKeysSha256").asText()))
                throw new IllegalStateException("评分输入缺失、多出或重复业务键：" + source.getKey());
        }
        revalidate();
        var result =
                Map.<String, Object>of(
                        "manifest",
                        path.toString(),
                        "sha256",
                        expectedSha256,
                        "verifiedSources",
                        new TreeSet<>(schema.keys().keySet()));
        if (schema == StrategyInputSchema.V1) return result;
        var versioned = new LinkedHashMap<String, Object>(result);
        versioned.put("schema", schema.coverageSchema());
        versioned.put("inputSchema", schema.inputSchema());
        versioned.put("keyEncoding", schema.keyEncoding());
        return Map.copyOf(versioned);
    }

    /** 发布前重查原清单和原始凭证，文件被替换时不推进产物别名。 */
    public void revalidate() throws Exception {
        if (!fileSha(path, 1024 * 1024).equals(expectedSha256))
            throw new IllegalStateException("评分来源清单被修改");
        for (String name : schema.keys().keySet()) {
            var source = document.path("sources").path(name);
            String file = source.path("certificate").asText();
            String digest = source.path("certificateSha256").asText();
            if (file.isBlank() || !digest.matches("[a-f0-9]{64}"))
                throw new IllegalStateException("评分来源缺少原始核验凭证：" + name);
            Path receipt = path.getParent().resolve(file).normalize();
            if (!fileSha(receipt, 128L * 1024 * 1024).equals(digest))
                throw new IllegalStateException("评分来源核验凭证已变化：" + name);
        }
        if (document.has("pointInTime")) history().revalidate();
        if (document.has("coverageInputs")) {
            var input = document.get("coverageInputs");
            Path file = path.getParent().resolve(input.required("path").asText()).normalize();
            if (!fileSha(file, 128L * 1024 * 1024).equals(input.required("sha256").asText()))
                throw new IllegalStateException("批准的覆盖输入已改变");
        }
        if (document.has("upstreamRuns"))
            for (var source : document.get("upstreamRuns")) {
                var ledger =
                        SyncRunLedger.openReadOnly(Path.of(source.required("ledger").asText()));
                String id = source.required("runId").asText();
                if (!objectSha(List.of(ledger.getRun(id), ledger.get(id)))
                        .equals(source.required("sha256").asText()))
                    throw new IllegalStateException("上游核验记录已改变");
            }
    }

    public PointInTimeHistory history() throws Exception {
        if (!document.path("pointInTime").isObject())
            throw new IllegalStateException("历史评分缺 pointInTime 名称、行业及市场状态证据；不能使用今天的资料代替");
        var source = document.get("pointInTime");
        return new PointInTimeHistory(
                path.getParent().resolve(source.required("path").asText()),
                source.required("sha256").asText());
    }

    static String keySha(List<Map<String, Object>> rows, List<String> keys) throws Exception {
        var encoded = new TreeSet<String>();
        for (var row : rows) {
            var key = new ArrayList<String>();
            for (String field : keys) {
                Object value = row.get(field);
                if (value == null) throw new IllegalStateException("评分输入业务键为空：" + field);
                key.add(value.toString());
            }
            if (!encoded.add(JSON.writeValueAsString(key)))
                throw new IllegalStateException("评分输入业务键重复");
        }
        var digest = MessageDigest.getInstance("SHA-256");
        for (String key : encoded) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String objectSha(Object value) throws Exception {
        return sha(JSON.writeValueAsBytes(value));
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String fileSha(Path file, long maximum) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("评分来源凭证不存在或是符号链接");
        var digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = input.read(buffer)) != -1; ) {
                size += n;
                if (size > maximum) throw new IllegalStateException("评分来源凭证超过大小上限");
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
