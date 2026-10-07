package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Read-only, typed plan for registered groups; all members freeze before any runner is called. */
public final class SyncGroupPlanning {
    private SyncGroupPlanning() {}
    public static SyncGroupPlan prepare(SyncGroupRegistry groups, SyncJobRegistry jobs,
                                        Map<String,String> options) throws Exception {
        if (!options.keySet().containsAll(Set.of("--group","--version","--logical-date"))
                || !Set.of("--group","--version","--logical-date","--parameters","--mode",
                "--from","--to","--overrides","--parameters-file","--overrides-file").containsAll(options.keySet()))
            throw new IllegalArgumentException("Explicit group, version and logical-date required");
        if (options.containsKey("--overrides") && options.containsKey("--overrides-file"))
            throw new IllegalArgumentException("Choose one override input");
        String id=options.get("--group"); int version=Integer.parseInt(options.get("--version"));
        var definition=groups.require(id,version);
        var common=parseObject(SyncJobPlanning.parameterInput(options));
        String overrideInput=options.getOrDefault("--overrides","{}");
        if (options.containsKey("--overrides-file")) {
            Path file=Path.of(options.get("--overrides-file"));
            if (Files.size(file)>65536) throw new IllegalArgumentException("Overrides exceed 64 KiB");
            overrideInput=Files.readString(file,StandardCharsets.UTF_8);
        }
        var overrides=parseObject(overrideInput);
        var known=definition.orderedMembers().stream().map(m -> m.job().jobId()).collect(
                java.util.stream.Collectors.toSet());
        if (!known.containsAll(overrides.keySet())) throw new IllegalArgumentException("Unknown group override member");
        var prepared=new LinkedHashMap<String,SyncGroupPlan.Override>();
        var globalWindow=window(options.get("--from"),options.get("--to"));
        var globalMode=mode(options.get("--mode"));
        for (var member:definition.orderedMembers()) {
            var job=jobs.require(member.job().jobId(),member.job().version());
            var override=overrides.get(member.job().jobId());
            if (override!=null && !override.isObject()) throw new IllegalArgumentException("Object member override required");
            if (override!=null) fields(override,Set.of("mode","from","to","parameters"));
            var merged=new LinkedHashMap<String,JsonNode>(common);
            if (override!=null && override.has("parameters")) merged.putAll(object(override.get("parameters")));
            var typed=typed(job,merged);
            var ownMode=override!=null && override.has("mode") ? mode(text(override.get("mode"))) : globalMode;
            var ownWindow=override!=null && (override.has("from") || override.has("to"))
                    ? window(override.has("from")?nullableText(override.get("from")):null,
                    override.has("to")?nullableText(override.get("to")):null) : globalWindow;
            prepared.put(job.jobId(),new SyncGroupPlan.Override(ownMode,ownWindow,typed));
        }
        return SyncGroupPlan.prepare(groups,jobs,id,version,LocalDate.parse(options.get("--logical-date")),
                null,null,Map.of(),prepared);
    }
    private static Map<String,JsonNode> parseObject(String input) throws Exception {
        if (input.getBytes(StandardCharsets.UTF_8).length>65536)
            throw new IllegalArgumentException("Group parameter JSON exceeds 64 KiB");
        return object(RequestJson.readTree(input));
    }
    private static Map<String,JsonNode> object(JsonNode node) {
        if (node==null || !node.isObject()) throw new IllegalArgumentException("JSON object required");
        var map=new LinkedHashMap<String,JsonNode>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(),e.getValue()));
        return map;
    }
    private static void fields(JsonNode node,Set<String> allowed) {
        node.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k))
            throw new IllegalArgumentException("Unknown group override field: "+k); });
    }
    private static Map<String,Object> typed(SyncJobDefinition job,Map<String,JsonNode> input) {
        var result=new LinkedHashMap<String,Object>();
        for (var entry:input.entrySet()) {
            var spec=job.parameters().get(entry.getKey());
            if (spec==null) throw new IllegalArgumentException("Unknown parameter for "+job.jobId());
            var value=entry.getValue();
            Object typed=switch (spec.type()) {
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
                    var items=new ArrayList<String>(); value.forEach(item -> items.add(text(item)));
                    yield List.copyOf(items);
                }
            };
            result.put(entry.getKey(),typed);
        }
        return result;
    }
    private static String nullableText(JsonNode value) { return value.isNull() ? null : text(value); }
    private static String text(JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("String required without coercion");
        return value.textValue();
    }
    private static SyncJobDefinition.Mode mode(String value) {
        return value==null?null:SyncJobDefinition.Mode.valueOf(value.toUpperCase(Locale.ROOT));
    }
    private static SyncGroupPlan.Window window(String from,String to) {
        return from==null && to==null?null:new SyncGroupPlan.Window(
                from==null?null:LocalDate.parse(from),to==null?null:LocalDate.parse(to));
    }
}
