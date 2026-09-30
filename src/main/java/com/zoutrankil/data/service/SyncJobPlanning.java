package com.zoutrankil.data.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.SyncJobDefinition;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure catalog validation; owns no connector, target connection or ledger. */
public final class SyncJobPlanning {
    private SyncJobPlanning() {}
    public static SyncJobDefinition.FrozenRequest prepare(SyncJobRegistry jobs, Map<String,String> options)
            throws Exception {
        if (!options.keySet().containsAll(Set.of("--job", "--version", "--logical-date"))
                || !Set.of("--job", "--version", "--logical-date", "--parameters", "--parameters-file", "--mode", "--from", "--to")
                .containsAll(options.keySet()))
            throw new IllegalArgumentException("Explicit job, version and logical-date required; unknown planning option");
        var definition = jobs.require(options.get("--job"), Integer.parseInt(options.get("--version")));
        String input = parameterInput(options);
        if (input.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("Parameters exceed 64 KiB");
        var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var node = mapper.readTree(input);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Parameter object required");
        var values = new LinkedHashMap<String,Object>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            var spec = definition.parameters().get(entry.getKey());
            if (spec == null) throw new IllegalArgumentException("Unknown job parameter: " + entry.getKey());
            var value = entry.getValue();
            Object normalized = switch (spec.type()) {
                case STRING -> text(value);
                case DATE -> LocalDate.parse(text(value));
                case INTEGER -> {
                    if (!value.isIntegralNumber() || !value.canConvertToInt())
                        throw new IllegalArgumentException("Integer parameter required");
                    yield value.intValue();
                }
                case BOOLEAN -> {
                    if (!value.isBoolean()) throw new IllegalArgumentException("Boolean parameter required");
                    yield value.booleanValue();
                }
                case STRING_LIST -> {
                    if (!value.isArray()) throw new IllegalArgumentException("String list required");
                    var items = new ArrayList<String>();
                    value.forEach(item -> items.add(text(item)));
                    yield List.copyOf(items);
                }
            };
            values.put(entry.getKey(), normalized);
        }
        var mode = options.containsKey("--mode")
                ? SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT)) : null;
        return jobs.prepare(definition.jobId(), definition.version(), mode, values,
                date(options.get("--from")), date(options.get("--to")), date(options.get("--logical-date")));
    }
    static String parameterInput(Map<String,String> options) throws java.io.IOException {
        if (options.containsKey("--parameters") && options.containsKey("--parameters-file"))
            throw new IllegalArgumentException("Choose parameters or parameters-file, not both");
        if (!options.containsKey("--parameters-file")) return options.getOrDefault("--parameters", "{}");
        try (var stream = java.nio.file.Files.newInputStream(java.nio.file.Path.of(options.get("--parameters-file")))) {
            byte[] bytes = stream.readNBytes(65537);
            if (bytes.length > 65536) throw new IllegalArgumentException("Parameters exceed 64 KiB");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static String text(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("Text parameter required without coercion");
        return value.textValue();
    }
    private static LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
}
