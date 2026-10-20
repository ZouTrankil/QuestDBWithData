package com.zoutrankil.data.strategy.input;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 用业务生效区间和当时可用时间共同选版本；缺历史就停止，不拿今天的名称或行业补过去。 */
public final class PointInTimeHistory {
    private final Path path;
    private final String sha;
    private final JsonNode document;
    private final Map<String, List<JsonNode>> metadataIndex = new HashMap<>(),
            contextIndex = new HashMap<>();

    public PointInTimeHistory(Path path, String sha) throws Exception {
        this.path = path.toAbsolutePath().normalize();
        this.sha = sha;
        if (!sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("历史输入需要批准摘要");
        byte[] bytes = read(this.path, 128L * 1024 * 1024);
        if (!StrategySourceCoverage.sha(bytes).equals(sha))
            throw new IllegalStateException("历史输入摘要不符");
        document = JobDefinitionJson.mapper().readTree(bytes);
        if (!"java.strategy.point_in_time.v1".equals(document.path("schema").asText())
                || !document.path("decisionTimes").isObject()
                || !document.path("stockMetadata").isArray()
                || !document.path("marketContext").isArray()
                || !document.path("sourceEvidence").isArray()
                || document.path("sourceEvidence").isEmpty())
            throw new IllegalStateException("历史输入格式或原件证据缺失");
        for (var version : document.path("stockMetadata"))
            metadataIndex
                    .computeIfAbsent(version.required("ts_code").asText(), k -> new ArrayList<>())
                    .add(version);
        for (var version : document.path("marketContext"))
            contextIndex
                    .computeIfAbsent(
                            version.required("trade_date").asText(), k -> new ArrayList<>())
                    .add(version);
        revalidate();
    }

    public String sha256() {
        return sha;
    }

    public void revalidate() throws Exception {
        if (!StrategySourceCoverage.sha(read(path, 128L * 1024 * 1024)).equals(sha))
            throw new IllegalStateException("历史输入已改变");
        for (var proof : document.path("sourceEvidence")) {
            String expected = proof.path("sha256").asText();
            Path original = path.getParent().resolve(proof.required("path").asText()).normalize();
            if (!expected.matches("[a-f0-9]{64}")
                    || !StrategySourceCoverage.sha(read(original, 128L * 1024 * 1024))
                            .equals(expected)) throw new IllegalStateException("历史原件摘要不符");
        }
    }

    private Instant cutoff(LocalDate date, List<Map<String, Object>> calendar) {
        var node = document.path("decisionTimes").path(date.toString());
        if (!node.isTextual()) throw new IllegalStateException("缺历史决策时间：" + date);
        Instant cutoff = Instant.parse(node.asText());
        var zone = ZoneId.of("Asia/Shanghai");
        LocalDate next =
                calendar.stream()
                        .filter(r -> r.get("is_open") instanceof Number n && n.intValue() == 1)
                        .map(r -> QuestDbStrategyInputs.date(r.get("cal_date")))
                        .filter(d -> d.isAfter(date))
                        .min(Comparator.naturalOrder())
                        .orElseThrow(() -> new IllegalStateException("决策时间核验缺下一交易日"));
        if (cutoff.isBefore(date.atTime(15, 0).atZone(zone).toInstant())
                || cutoff.isAfter(next.atTime(9, 30).atZone(zone).toInstant()))
            throw new IllegalStateException("历史决策时间超出收盘至下一开盘区间：" + date);
        return cutoff;
    }

    /** v2 行显式适配原历史证据；可用时刻仍取原件，不用计算或导出时间替代。 */
    public List<Map<String, Object>> metadata(
            StrategyInputSchema schema,
            List<Map<String, Object>> l2,
            List<Map<String, Object>> calendar) {
        if (schema == StrategyInputSchema.V1) return metadata(l2, calendar);
        return StrategyInputRows.standard(
                "stock_metadata",
                metadata(
                        StrategyInputRows.legacyModel("l2_raw", l2),
                        StrategyInputRows.legacyModel("calendar", calendar)));
    }

    public List<Map<String, Object>> context(
            StrategyInputSchema schema,
            List<Map<String, Object>> required,
            List<Map<String, Object>> calendar) {
        if (schema == StrategyInputSchema.V1) return context(required, calendar);
        return StrategyInputRows.standard(
                "market_context",
                context(
                        StrategyInputRows.legacyModel("market_context", required),
                        StrategyInputRows.legacyModel("calendar", calendar),
                        true));
    }

    public List<Map<String, Object>> metadata(
            List<Map<String, Object>> l2, List<Map<String, Object>> calendar) {
        var wanted = new TreeMap<String, Map.Entry<LocalDate, String>>();
        for (var row : l2) {
            LocalDate date = QuestDbStrategyInputs.date(row.get("ts"));
            String code = Objects.toString(row.get("symbol"));
            wanted.put(date + "|" + code, Map.entry(date, code));
        }
        var out = new ArrayList<Map<String, Object>>();
        var cutoffs = new HashMap<LocalDate, Instant>();
        for (var item : wanted.values()) {
            LocalDate date = item.getKey();
            String code = item.getValue();
            Instant cutoff = cutoffs.computeIfAbsent(date, d -> cutoff(d, calendar));
            JsonNode best = null;
            Instant available = null;
            for (var version : metadataIndex.getOrDefault(code, List.of())) {
                LocalDate from = LocalDate.parse(version.required("effectiveFrom").asText());
                LocalDate to =
                        version.hasNonNull("effectiveTo")
                                ? LocalDate.parse(version.get("effectiveTo").asText())
                                : LocalDate.MAX;
                Instant known = Instant.parse(version.required("availableAt").asText());
                if (!from.isBefore(to)) throw new IllegalStateException("历史名称或行业生效区间非法");
                if (date.isBefore(from) || !date.isBefore(to) || known.isAfter(cutoff)) continue;
                if (available == null || known.isAfter(available)) {
                    best = version;
                    available = known;
                } else if (known.equals(available) && !version.equals(best))
                    throw new IllegalStateException("历史名称或行业版本冲突：" + code + " " + date);
            }
            if (best == null) throw new IllegalStateException("缺当时可用的名称或行业：" + code + " " + date);
            var row = new LinkedHashMap<String, Object>();
            row.put("trade_date", date);
            row.put("ts_code", code);
            for (String field : List.of("name", "industry")) {
                if (!best.path(field).isTextual())
                    throw new IllegalStateException("历史资料字段缺失：" + field);
                row.put(field, best.get(field).asText());
            }
            row.put("available_at", available.toString());
            out.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(out);
    }

    public List<Map<String, Object>> context(
            List<Map<String, Object>> required, List<Map<String, Object>> calendar) {
        return context(required, calendar, false);
    }

    private List<Map<String, Object>> context(
            List<Map<String, Object>> required,
            List<Map<String, Object>> calendar,
            boolean includeAvailability) {
        var out = new ArrayList<Map<String, Object>>();
        for (var current : required) {
            LocalDate date = QuestDbStrategyInputs.date(current.get("trade_date"));
            Instant cutoff = cutoff(date, calendar), available = null;
            JsonNode best = null;
            for (var revision : contextIndex.getOrDefault(date.toString(), List.of())) {
                Instant known = Instant.parse(revision.required("availableAt").asText());
                if (known.isAfter(cutoff)) continue;
                if (available == null || known.isAfter(available)) {
                    best = revision;
                    available = known;
                } else if (known.equals(available) && !revision.equals(best))
                    throw new IllegalStateException("历史市场状态版本冲突：" + date);
            }
            if (best == null || !best.path("values").isObject())
                throw new IllegalStateException("缺当时可用的市场状态：" + date);
            var values =
                    JobDefinitionJson.mapper()
                            .convertValue(
                                    best.get("values"),
                                    new TypeReference<LinkedHashMap<String, Object>>() {});
            var fields = new HashSet<>(current.keySet());
            fields.remove("trade_date");
            if (!values.keySet().equals(fields))
                throw new IllegalStateException("历史市场状态列与正式契约不同：" + date);
            values.put("trade_date", date);
            // v2 单独保留证据的可用时刻；updated_at 仍是原计算更新时间。
            if (includeAvailability) {
                if (values.containsKey("available_at"))
                    throw new IllegalStateException("历史市场值不能覆盖证据可用时刻");
                values.put("available_at", available.toString());
            }
            out.add(Collections.unmodifiableMap(values));
        }
        return List.copyOf(out);
    }

    private static byte[] read(Path file, long maximum) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maximum)
            throw new IllegalStateException("历史输入或原件缺失、是链接或过大");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(Math.toIntExact(maximum + 1));
            if (bytes.length > maximum) throw new IllegalStateException("历史原件变大");
            return bytes;
        }
    }
}
