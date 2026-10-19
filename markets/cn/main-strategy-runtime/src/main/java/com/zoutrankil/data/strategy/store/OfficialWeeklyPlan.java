package com.zoutrankil.data.strategy.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 已经校验的正式第 1 版载荷；不根据评分候选自行合成目标持仓。 */
public final class OfficialWeeklyPlan {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectNode payload;

    OfficialWeeklyPlan(ObjectNode payload) { this.payload = payload.deepCopy(); }

    public String strategy() { return payload.path("strategy").asText(); }
    public LocalDate signalDate() { return LocalDate.parse(payload.path("signal_date").asText()); }
    public LocalDate executionDate() { return LocalDate.parse(payload.path("execution_date").asText()); }
    public LocalDate valuationDate() {
        return payload.path("valuation_date").isNull() ? null
                : LocalDate.parse(payload.path("valuation_date").asText());
    }
    public List<String> targetSymbols() {
        return java.util.stream.StreamSupport.stream(payload.path("targets").spliterator(), false)
                .map(target -> target.path("symbol").asText()).sorted().toList();
    }
    public ObjectNode payload() { return payload.deepCopy(); }
    /** 使用中性的 Java 值，让 CLI 的 Jackson 2 和 HTTP 的 Jackson 3 都能序列化同一正式契约。 */
    @JsonValue
    public Map<String,Object> jsonValue() { return JSON.convertValue(payload, new TypeReference<Map<String,Object>>() {}); }
    public String canonicalJson() { return CanonicalPlanJson.write(payload); }
    public String semanticSha256() {
        ObjectNode semantic = payload.deepCopy();
        semantic.remove("diagnostic_payload_path");
        return CanonicalPlanJson.sha256(CanonicalPlanJson.write(semantic).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
