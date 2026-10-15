package com.zoutrankil.data.strategy.store;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Validated official v1 payload; never synthesizes targets from score candidates. */
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
    /** Neutral Java values let both CLI Jackson 2 and HTTP Jackson 3 render the official contract. */
    @JsonValue
    public Map<String,Object> jsonValue() { return JSON.convertValue(payload, new TypeReference<Map<String,Object>>() {}); }
    public String canonicalJson() { return CanonicalPlanJson.write(payload); }
    public String semanticSha256() {
        ObjectNode semantic = payload.deepCopy();
        semantic.remove("diagnostic_payload_path");
        return CanonicalPlanJson.sha256(CanonicalPlanJson.write(semantic).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
