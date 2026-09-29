package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Strict JSON boundary: schema determines Java types, never heuristic date/numeric coercion. */
public final class ReadGroupJson {
    private final DatasetRegistry datasets;
    private final QuestDbBoundedReader reader;
    public ReadGroupJson(DatasetRegistry datasets, QuestDbBoundedReader reader) {
        this.datasets = Objects.requireNonNull(datasets); this.reader = Objects.requireNonNull(reader);
    }
    public ReadGroupRequest read(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("Read request exceeds 1 MiB");
            return parse(bytes);
        }
    }
    public ReadGroupRequest parse(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 1024 * 1024) throw new IllegalArgumentException("Bounded JSON required");
        var root = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        fields(root, "timeoutMillis", "members");
        int millis = integer(root.required("timeoutMillis"));
        var membersNode = root.required("members");
        if (!membersNode.isArray() || membersNode.isEmpty() || membersNode.size() > 64)
            throw new IllegalArgumentException("Explicit bounded read members required");
        var members = new ArrayList<ReadGroupRequest.Member>();
        for (var member : membersNode) {
            fields(member, "memberId", "datasetId", "definitionVersion", "query");
            String id = text(member.required("datasetId"));
            var definition = datasets.require(id).definition();
            int version = integer(member.required("definitionVersion"));
            if (version != definition.schemaVersion()) throw new IllegalArgumentException("Unknown dataset version");
            var query = member.required("query");
            fields(query, "columns", "equalities", "rangeColumn", "fromInclusive", "toExclusive", "pageSize", "cursor");
            var columns = query.required("columns");
            if (!columns.isArray() || columns.size() > 4096) throw new IllegalArgumentException("Explicit projection required");
            var projection = new ArrayList<String>(); for (var column : columns) projection.add(text(column));
            var equalities = new LinkedHashMap<String,Object>();
            if (query.has("equalities")) {
                var filters = query.get("equalities");
                if (!filters.isObject()) throw new IllegalArgumentException("Equality object required");
                var iterator = filters.fields();
                while (iterator.hasNext()) {
                    var filter = iterator.next();
                    equalities.put(filter.getKey(), value(column(definition, filter.getKey()), filter.getValue()));
                }
            }
            String range = nullableText(query.get("rangeColumn"));
            Object from = range == null ? null : value(column(definition, range), query.required("fromInclusive"));
            Object to = range == null ? null : value(column(definition, range), query.required("toExclusive"));
            if (range == null && (nonnull(query.get("fromInclusive")) || nonnull(query.get("toExclusive"))))
                throw new IllegalArgumentException("Range values require rangeColumn");
            DatasetReadCursor cursor = null;
            if (nonnull(query.get("cursor"))) {
                var node = query.get("cursor"); fields(node, "queryFingerprint", "keyValues", "sourceVersion");
                var keys = node.required("keyValues");
                if (!keys.isArray() || keys.size() != definition.businessKey().size())
                    throw new IllegalArgumentException("Cursor must contain every business key");
                var values = new ArrayList<Object>();
                for (int i = 0; i < keys.size(); i++) values.add(value(column(definition, definition.businessKey().get(i)), keys.get(i)));
                cursor = new DatasetReadCursor(text(node.required("queryFingerprint")), values,
                        nullableText(node.get("sourceVersion")));
            }
            var typed = new DatasetReadQuery(projection, equalities, range, from, to,
                    integer(query.required("pageSize")), cursor);
            // SQL construction only: reject key/projection/cursor/type errors without touching QuestDB.
            reader.prepare(definition, typed, cursor == null ? null : cursor.sourceVersion());
            members.add(new ReadGroupRequest.Member(text(member.required("memberId")), id, version, typed));
        }
        return new ReadGroupRequest(members, Duration.ofMillis(millis));
    }
    private static DatasetDefinition.Column column(DatasetDefinition definition, String name) {
        return definition.columns().stream().filter(c -> c.logicalName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dataset column"));
    }
    static Object value(DatasetDefinition.Column column, JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (column.temporal() != null) return switch (column.temporal().kind()) {
            case BUSINESS_DATE -> LocalDate.parse(text(node));
            case INSTANT -> Instant.parse(text(node));
            case TECHNICAL -> {
                fields(node, "storageCarrier", "reason");
                yield new TemporalValues.TechnicalTimestamp(Instant.parse(text(node.required("storageCarrier"))),
                        text(node.required("reason")));
            }
        };
        return switch (column.storageType()) {
            case SYMBOL, STRING, VARCHAR, LONG256, IPV4 -> text(node);
            case CHAR -> { String s = text(node); if (s.length() != 1) throw new IllegalArgumentException("One character required"); yield s.charAt(0); }
            case BOOLEAN -> { if (!node.isBoolean()) throw new IllegalArgumentException("Boolean required"); yield node.booleanValue(); }
            case BYTE -> { int n = integer(node); if (n < Byte.MIN_VALUE || n > Byte.MAX_VALUE) throw new IllegalArgumentException("Byte overflow"); yield (byte)n; }
            case SHORT -> { int n = integer(node); if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) throw new IllegalArgumentException("Short overflow"); yield (short)n; }
            case INT -> integer(node);
            case LONG -> { if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new IllegalArgumentException("Long integer required"); yield node.longValue(); }
            case FLOAT -> { if (!node.isNumber() || !Float.isFinite(node.floatValue())) throw new IllegalArgumentException("Finite float required"); yield node.floatValue(); }
            case DOUBLE -> { if (!node.isNumber() || !Double.isFinite(node.doubleValue())) throw new IllegalArgumentException("Finite double required"); yield node.doubleValue(); }
            case UUID -> UUID.fromString(text(node));
            case BINARY -> Base64.getDecoder().decode(text(node));
            default -> throw new IllegalArgumentException("Temporal contract required");
        };
    }
    static void fields(JsonNode node, String... allowed) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        var names = Set.of(allowed); var fields = node.fieldNames();
        while (fields.hasNext()) if (!names.contains(fields.next())) throw new IllegalArgumentException("Unknown request property");
    }
    static int integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw new IllegalArgumentException("Integer required");
        return node.intValue();
    }
    private static boolean nonnull(JsonNode node) { return node != null && !node.isNull(); }
    private static String nullableText(JsonNode node) { return nonnull(node) ? text(node) : null; }
    static String text(JsonNode node) {
        if (!node.isTextual() || node.textValue().length() > 4096) throw new IllegalArgumentException("Bounded string required");
        return node.textValue();
    }
}
