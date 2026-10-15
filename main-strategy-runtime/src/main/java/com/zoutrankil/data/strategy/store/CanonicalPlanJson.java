package com.zoutrankil.data.strategy.store;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Python v1 model defaults and json.dumps(sort_keys=True, ensure_ascii=False) semantics. */
final class CanonicalPlanJson {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final List<String> OPTIONAL_NUMBERS = List.of("base_target_weight", "selection_score",
            "component_l2_score_smooth", "component_buy_breadth_smooth", "trend_score", "liquidity_score",
            "score_component_l2_contrib", "score_buy_breadth_contrib", "score_trend_contrib",
            "score_liquidity_contrib", "weight_component_l2", "weight_buy_breadth", "weight_trend",
            "weight_liquidity", "optimized_score_quality", "dynamic_overlay", "state_dynamic_overlay",
            "top10_high_vol_weight");
    static final List<String> TARGET_COLUMNS;
    private static final Set<String> PLAN_FIELDS = Set.of("schema_version", "kind", "available", "status",
            "strategy", "signal_date", "execution_date", "valuation_date", "rebalance_trigger",
            "portfolio_state", "state_trigger_reason", "targets", "diagnostic_top_candidates_excluded",
            "diagnostic_payload_path", "target_symbols");
    private static final Set<String> TARGET_FIELDS;
    static {
        var fields = new HashSet<>(OPTIONAL_NUMBERS);
        fields.addAll(List.of("symbol", "name", "asset_type", "target_weight", "self_overheat_flag",
                "component_crowding_flag", "signal_date", "execution_date", "valuation_date", "component_set"));
        TARGET_FIELDS = Set.copyOf(fields);
        var columns = new ArrayList<>(List.of("symbol", "name", "asset_type", "target_weight"));
        columns.addAll(OPTIONAL_NUMBERS);
        columns.addAll(List.of("component_crowding_flag", "self_overheat_flag"));
        TARGET_COLUMNS = List.copyOf(columns);
    }

    static OfficialWeeklyPlan read(byte[] bytes) {
        try {
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) throw invalid("plan must be an object");
            return normalize(object, true);
        } catch (PlanStoreException e) { throw e; }
        catch (Exception e) { throw new PlanStoreException("invalid official plan JSON", e); }
    }

    static OfficialWeeklyPlan normalize(ObjectNode source, boolean requireAuthorityMarkers) {
        rejectUnknown(source, PLAN_FIELDS, "plan");
        ObjectNode plan = JSON.createObjectNode();
        marker(source, plan, "schema_version", "v1", requireAuthorityMarkers);
        marker(source, plan, "kind", "official_weekly_plan", requireAuthorityMarkers);
        marker(source, plan, "status", "official_weekly_targets_only", requireAuthorityMarkers);
        trueMarker(source, plan, "available", requireAuthorityMarkers);
        trueMarker(source, plan, "diagnostic_top_candidates_excluded", requireAuthorityMarkers);
        plan.put("strategy", requiredText(source, "strategy"));
        LocalDate signal = requiredDate(source, "signal_date");
        LocalDate execution = requiredDate(source, "execution_date");
        if (execution.isBefore(signal)) throw invalid("execution date precedes signal date");
        plan.put("signal_date", signal.toString());
        plan.put("execution_date", execution.toString());
        optionalDate(source, plan, "valuation_date");
        for (String name : List.of("rebalance_trigger", "portfolio_state", "state_trigger_reason", "diagnostic_payload_path"))
            plan.put(name, textOrDefault(source, name, ""));
        JsonNode inputTargets = source.get("targets");
        if (inputTargets == null || !inputTargets.isArray() || inputTargets.size() < 1 || inputTargets.size() > 2)
            throw invalid("official plan requires one or two targets");
        ArrayNode targets = plan.putArray("targets");
        Set<String> symbols = new HashSet<>();
        for (JsonNode input : inputTargets) {
            if (!(input instanceof ObjectNode target)) throw invalid("target must be an object");
            rejectUnknown(target, TARGET_FIELDS, "target");
            ObjectNode normalized = JSON.createObjectNode();
            String symbol = requiredText(target, "symbol").strip().toUpperCase(Locale.ROOT);
            if (!symbols.add(symbol)) throw invalid("official plan requires distinct target symbols");
            normalized.put("symbol", symbol);
            normalized.put("name", textOrDefault(target, "name", ""));
            marker(target, normalized, "asset_type", "etf", false);
            normalized.put("target_weight", requiredNumber(target, "target_weight"));
            weight(normalized, "target_weight");
            for (String name : OPTIONAL_NUMBERS) {
                JsonNode value = target.get(name);
                if (value == null || value.isNull()) normalized.putNull(name);
                else normalized.put(name, requiredNumber(target, name));
            }
            weight(normalized, "base_target_weight");
            weight(normalized, "top10_high_vol_weight");
            for (String name : List.of("self_overheat_flag", "component_crowding_flag")) {
                JsonNode flag = target.get(name);
                if (flag == null || flag.isNull()) normalized.putNull(name);
                else if (flag.isBoolean()) normalized.put(name, flag.booleanValue());
                else throw invalid(name + " must be a boolean or null");
            }
            if (!requiredDate(target, "signal_date").equals(signal)
                    || !requiredDate(target, "execution_date").equals(execution))
                throw invalid("target dates must match official plan identity");
            normalized.put("signal_date", signal.toString());
            normalized.put("execution_date", execution.toString());
            optionalDate(target, normalized, "valuation_date");
            ArrayNode components = normalized.putArray("component_set");
            JsonNode componentInput = target.get("component_set");
            Set<String> uniqueComponents = new HashSet<>();
            if (componentInput != null) {
                if (!componentInput.isArray()) throw invalid("component_set must be an array");
                for (JsonNode component : componentInput) {
                    if (!component.isTextual() || !uniqueComponents.add(component.textValue()))
                        throw invalid("component_set requires distinct strings for the canonical SQLite key");
                    components.add(component.textValue());
                }
            }
            targets.add(normalized);
        }
        JsonNode reported = source.get("target_symbols");
        if (reported != null) {
            ArrayNode expected = JSON.createArrayNode();
            symbols.stream().sorted().forEach(expected::add);
            if (!expected.equals(reported)) throw invalid("snapshot target_symbols disagree with executable targets");
        }
        return new OfficialWeeklyPlan(plan);
    }

    static String write(JsonNode node) {
        var result = new StringBuilder();
        append(node, result);
        return result.toString();
    }

    private static void append(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            out.append('{');
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            boolean first = true;
            for (String name : names) {
                if (!first) out.append(',');
                first = false;
                quote(name, out); out.append(':'); append(node.get(name), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) { if (i > 0) out.append(','); append(node.get(i), out); }
            out.append(']');
        } else if (node.isTextual()) quote(node.textValue(), out);
        else if (node.isFloatingPointNumber()) out.append(pythonDouble(node.doubleValue()));
        else if (node.isIntegralNumber()) out.append(node.asText());
        else if (node.isBoolean()) out.append(node.booleanValue());
        else if (node.isNull()) out.append("null");
        else throw invalid("unsupported canonical JSON value");
    }

    /** Choose the shortest round-tripping decimal, then Python's exponent presentation. */
    static String pythonDouble(double value) {
        if (!Double.isFinite(value)) throw invalid("non-finite plan numbers are forbidden");
        if (value == 0) return Double.doubleToRawLongBits(value) < 0 ? "-0.0" : "0.0";
        BigDecimal exact = new BigDecimal(value), shortest = null;
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (Double.doubleToRawLongBits(Double.parseDouble(candidate.toString())) == Double.doubleToRawLongBits(value)) {
                shortest = candidate.stripTrailingZeros(); break;
            }
        }
        if (shortest == null) throw invalid("cannot canonicalize plan number");
        int exponent = shortest.precision() - shortest.scale() - 1;
        if (exponent >= -4 && exponent < 16) {
            String plain = shortest.toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        String coefficient = shortest.movePointLeft(exponent).toPlainString();
        return coefficient + "e" + (exponent >= 0 ? "+" : "-")
                + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                            throw invalid("unpaired Unicode surrogate");
                        out.append(c).append(text.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) throw invalid("unpaired Unicode surrogate");
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String requiredText(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid("missing " + name);
        return value.textValue();
    }
    private static String textOrDefault(ObjectNode node, String name, String fallback) {
        JsonNode value = node.get(name);
        if (value == null) return fallback;
        if (!value.isTextual()) throw invalid(name + " must be a string");
        return value.textValue();
    }
    private static double requiredNumber(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) throw invalid(name + " must be finite numeric");
        return value.doubleValue();
    }
    private static void weight(ObjectNode node, String name) {
        if (!node.path(name).isNull() && (node.path(name).doubleValue() < 0 || node.path(name).doubleValue() > 1))
            throw invalid(name + " must be between zero and one");
    }
    private static LocalDate requiredDate(ObjectNode node, String name) {
        try {
            String text = requiredText(node, name);
            LocalDate date = LocalDate.parse(text);
            if (!date.toString().equals(text)) throw invalid(name + " must use ISO date");
            return date;
        } catch (PlanStoreException e) { throw e; }
        catch (Exception e) { throw new PlanStoreException("invalid " + name, e); }
    }
    private static void optionalDate(ObjectNode source, ObjectNode target, String name) {
        JsonNode value = source.get(name);
        if (value == null || value.isNull()) target.putNull(name);
        else target.put(name, requiredDate(source, name).toString());
    }
    private static void marker(ObjectNode source, ObjectNode target, String name, String expected, boolean required) {
        String actual = required ? requiredText(source, name) : textOrDefault(source, name, expected);
        if (!expected.equals(actual)) throw invalid("official plan requires " + name + "=" + expected);
        target.put(name, expected);
    }
    private static void trueMarker(ObjectNode source, ObjectNode target, String name, boolean required) {
        JsonNode value = source.get(name);
        if ((value == null && required) || (value != null && (!value.isBoolean() || !value.booleanValue())))
            throw invalid("official plan requires " + name + "=true");
        target.put(name, true);
    }
    private static void rejectUnknown(ObjectNode source, Set<String> fields, String description) {
        source.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw invalid("unknown " + description + " field: " + name); });
    }
    private static PlanStoreException invalid(String message) { return new PlanStoreException(message); }
}
