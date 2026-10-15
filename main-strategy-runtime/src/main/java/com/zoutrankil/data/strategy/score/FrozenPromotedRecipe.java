package com.zoutrankil.data.strategy.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.l2.application.NativeL2ParquetReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Accepted, hash-pinned Python promotion package. This loader never trains or substitutes a recipe. */
public record FrozenPromotedRecipe(Recipe recipe, List<DatedValues> factorWeights,
        List<DatedValues> factorStates, List<DatedValues> l2Weights, Map<String, String> artifactSha256) {
    public record Recipe(String strategyId, String scoreRecipeId, LocalDate effectiveFrom,
            LocalDate trainingCutoff, String inputSchemaVersion, String codeRevision,
            double componentL2Weight, double buyBreadthWeight, double trendWeight, double liquidityWeight,
            double dynamicOverlay, int topN, double maxSingleWeight) {
        public Recipe {
            if (!"ETF_ONLY_TOP2".equals(strategyId) || scoreRecipeId == null || scoreRecipeId.isBlank()
                    || effectiveFrom == null || trainingCutoff == null || inputSchemaVersion == null
                    || !inputSchemaVersion.equals("etf_only_top2_score_panel_v1") || codeRevision == null || codeRevision.isBlank())
                throw new IllegalArgumentException("Accepted ETF_ONLY_TOP2 recipe identity/schema required");
            for (double value : new double[]{componentL2Weight, buyBreadthWeight, trendWeight, liquidityWeight})
                if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid fixed recipe weight");
            if (!Double.isFinite(dynamicOverlay) || dynamicOverlay < 0 || dynamicOverlay > 1 || topN < 1
                    || !Double.isFinite(maxSingleWeight) || maxSingleWeight <= 0 || maxSingleWeight > 1)
                throw new IllegalArgumentException("Invalid promoted overlay/allocation parameters");
        }
    }
    public record DatedValues(LocalDate date, Map<String, Object> values) {
        public DatedValues { Objects.requireNonNull(date); values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
        public double number(String key) { return ScoreMath.number(values.get(key)); }
    }
    public FrozenPromotedRecipe {
        Objects.requireNonNull(recipe); factorWeights = List.copyOf(factorWeights); factorStates = List.copyOf(factorStates);
        l2Weights = List.copyOf(l2Weights); artifactSha256 = Map.copyOf(artifactSha256);
        for (var entry : Map.of("factor_weights", factorWeights, "factor_states", factorStates, "l2_factor_weights", l2Weights).entrySet()) {
            if (entry.getValue().isEmpty()) throw new IllegalArgumentException("Missing promoted " + entry.getKey());
            for (var row : entry.getValue()) if (row.date().isAfter(recipe.trainingCutoff()))
                throw new IllegalArgumentException("Promoted " + entry.getKey() + " exceeds training cutoff");
        }
        for (var role : List.of("recipe", "factor_weights", "factor_states", "l2_factor_weights"))
            if (!Objects.toString(artifactSha256.get(role), "").matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Pinned SHA256 required for " + role);
        var l2Columns = new HashSet<String>(); l2Weights.forEach(row -> l2Columns.addAll(row.values().keySet()));
        for (String factor : L2StockScoreProducer.POSITIVE_FACTORS) requireColumn(l2Columns, "w_" + factor);
        for (String factor : L2StockScoreProducer.RISK_FACTORS.keySet()) requireColumn(l2Columns, "risk_active_" + factor);
        var etfColumns = new HashSet<String>(); factorWeights.forEach(row -> etfColumns.addAll(row.values().keySet()));
        for (String factor : EtfOnlyTop2ScoreProducer.FACTOR_COLUMNS.keySet()) requireColumn(etfColumns, "w_" + factor);
        requireColumn(etfColumns, "dynamic_evidence_sum");
    }
    private static void requireColumn(Set<String> columns, String name) {
        if (!columns.contains(name)) throw new IllegalArgumentException("Promoted table lacks " + name);
    }
    /** Roles: recipe, factor_weights, factor_states, l2_factor_weights. Caller must supply trusted acceptance hashes. */
    public static FrozenPromotedRecipe load(Path recipePath, Path allowedRoot, Map<String, String> expectedSha256) throws IOException {
        Path root = allowedRoot.toRealPath(), path = recipePath.toRealPath();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Promoted recipe escapes allowed artifact root");
        byte[] bytes = pinned(path, "recipe", expectedSha256);
        JsonNode json = new ObjectMapper().readTree(bytes);
        var recipe = new Recipe(text(json, "strategy_id"), text(json, "score_recipe_id"),
                date(text(json, "effective_from")), date(text(json, "training_cutoff")),
                text(json, "input_schema_version"), text(json, "code_revision"),
                requiredNumber(json, "component_l2_weight"), requiredNumber(json, "buy_breadth_weight"),
                requiredNumber(json, "trend_weight"), requiredNumber(json, "liquidity_weight"),
                json.has("dynamic_overlay") ? requiredNumber(json, "dynamic_overlay") : .10,
                json.has("top_n") ? json.get("top_n").asInt() : 2,
                json.has("max_single_weight") ? requiredNumber(json, "max_single_weight") : .50);
        var tables = new LinkedHashMap<String, List<DatedValues>>();
        for (var role : List.of("factor_weights", "factor_states", "l2_factor_weights")) {
            String field = switch (role) { case "factor_weights" -> "factor_weight_artifact"; case "factor_states" -> "factor_state_artifact"; default -> "l2_factor_weight_artifact"; };
            Path file = path.getParent().resolve(text(json, field)).normalize().toRealPath();
            if (!file.startsWith(root)) throw new IllegalArgumentException("Promoted artifact escapes allowed root: " + role);
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            byte[] artifact = pinned(file, role, expectedSha256);
            if (name.endsWith(".csv")) tables.put(role, csv(artifact));
            else if (name.endsWith(".parquet")) {
                var rows = new ArrayList<DatedValues>();
                try {
                    NativeL2ParquetReader.read(file, null, 1_000_000, () -> {}, row -> {
                        if (!row.hasNonNull("trade_date")) throw new IllegalArgumentException("Promoted Parquet requires trade_date");
                        var values = new LinkedHashMap<String, Object>();
                        row.fields().forEachRemaining(entry -> {
                            if (!entry.getKey().equals("trade_date")) {
                                JsonNode value = entry.getValue();
                                values.put(entry.getKey(), value.isNull() ? Double.NaN : value.isNumber() ? value.asDouble()
                                        : value.isBoolean() ? value.asBoolean() : value.asText());
                            }
                        });
                        rows.add(new DatedValues(date(row.get("trade_date").asText()), values));
                    });
                } catch (IOException | IllegalArgumentException error) { throw error; }
                catch (Exception error) { throw new IOException("Cannot read accepted promoted Parquet: " + role, error); }
                pinned(file, role, expectedSha256);
                rows.sort(Comparator.comparing(DatedValues::date)); tables.put(role, List.copyOf(rows));
            } else throw new IllegalArgumentException("Accepted CSV or Parquet promotion artifact required: " + role);
        }
        return new FrozenPromotedRecipe(recipe, tables.get("factor_weights"), tables.get("factor_states"), tables.get("l2_factor_weights"), expectedSha256);
    }
    private static byte[] pinned(Path path, String role, Map<String, String> hashes) throws IOException {
        String expected = hashes.get(role);
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Accepted SHA256 required: " + role);
        if (Files.size(path) > 64L * 1024 * 1024) throw new IllegalArgumentException("Promotion artifact exceeds bounded read: " + role);
        byte[] bytes = Files.readAllBytes(path);
        if (!sha256(bytes).equals(expected)) throw new IllegalArgumentException("Promotion artifact hash mismatch: " + role);
        return bytes;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Last observation on or before signal date. A pre-history date intentionally gets an empty weight row. */
    public static Map<String, Object> asOf(List<DatedValues> source, LocalDate date) {
        DatedValues selected = null;
        for (var row : source) if (!row.date().isAfter(date) && (selected == null || !row.date().isBefore(selected.date()))) selected = row;
        return selected == null ? Map.of() : selected.values();
    }
    static List<DatedValues> csv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        var records = new ArrayList<List<String>>(); var record = new ArrayList<String>(); var field = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') { if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') { field.append('"'); i++; } else quoted = !quoted; }
            else if (!quoted && (ch == ',' || ch == '\n' || ch == '\r')) {
                record.add(field.toString()); field.setLength(0);
                if (ch != ',') { if (!(record.size() == 1 && record.getFirst().isBlank())) records.add(List.copyOf(record)); record.clear(); if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++; }
            } else field.append(ch);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote");
        if (!field.isEmpty() || !record.isEmpty()) { record.add(field.toString()); records.add(List.copyOf(record)); }
        if (records.isEmpty()) throw new IllegalArgumentException("Empty promoted CSV");
        var columns = records.removeFirst(); int dateIndex = columns.indexOf("trade_date");
        if (dateIndex < 0 || new HashSet<>(columns).size() != columns.size()) throw new IllegalArgumentException("Unique columns and trade_date required");
        var rows = new ArrayList<DatedValues>();
        for (var values : records) {
            if (values.size() != columns.size()) throw new IllegalArgumentException("Malformed promoted CSV row");
            var result = new LinkedHashMap<String, Object>();
            for (int i = 0; i < columns.size(); i++) if (i != dateIndex) {
                String value = values.get(i).strip(); double number = ScoreMath.number(value);
                result.put(columns.get(i), value.isBlank() || value.equalsIgnoreCase("nan") ? Double.NaN : Double.isNaN(number) ? value : number);
            }
            rows.add(new DatedValues(date(values.get(dateIndex).strip()), result));
        }
        rows.sort(Comparator.comparing(DatedValues::date)); return List.copyOf(rows);
    }
    private static LocalDate date(String value) { return value.matches("[0-9]{8}") ? LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE) : LocalDate.parse(value.substring(0, 10)); }
    private static String text(JsonNode json, String name) {
        if (!json.hasNonNull(name) || !json.get(name).isTextual() || json.get(name).asText().isBlank()) throw new IllegalArgumentException("Recipe requires " + name);
        return json.get(name).asText();
    }
    private static double requiredNumber(JsonNode json, String name) {
        if (!json.hasNonNull(name) || !json.get(name).isNumber()) throw new IllegalArgumentException("Recipe requires numeric " + name);
        return json.get(name).asDouble();
    }
}
