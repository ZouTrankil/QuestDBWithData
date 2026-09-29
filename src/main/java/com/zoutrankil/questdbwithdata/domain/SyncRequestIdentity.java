package com.zoutrankil.questdbwithdata.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Recovery identity includes full frozen configuration, parameters, logical date and target. */
public final class SyncRequestIdentity {
    private SyncRequestIdentity() {}
    public static String snapshotJson(SyncJobDefinition.FrozenRequest request) {
        Objects.requireNonNull(request,"Frozen request required");
        try { return JobDefinitionJson.mapper().writeValueAsString(request); }
        catch (Exception error) { throw new IllegalArgumentException("Cannot serialize frozen request",error); }
    }
    public static String fingerprint(SyncJobDefinition.FrozenRequest request,String targetId) {
        return fingerprint(snapshotJson(request),targetId);
    }
    public static String fingerprint(String frozenJson,String targetId) {
        if(targetId==null || targetId.isBlank() || targetId.length()>128 || frozenJson==null || frozenJson.length()>1048576)
            throw new IllegalArgumentException("Bounded frozen request and target required");
        try {
            var json=JobDefinitionJson.mapper();
            var root=json.readTree(frozenJson);
            if(root==null || !root.isObject() || !root.path("definition").isObject()
                    || !root.path("parameters").isObject() || !root.path("mode").isTextual()
                    || !root.path("logicalDate").isTextual() || !root.has("from") || !root.has("to"))
                throw new IllegalArgumentException("Complete frozen request required");
            var envelope=json.createObjectNode();
            envelope.put("targetId",targetId);
            envelope.set("request",canonical(root,""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("Invalid recovery identity",failure); }
    }
    private static JsonNode canonical(JsonNode node,String path) {
        if(node.isObject()) {
            var output=JsonNodeFactory.instance.objectNode();
            var names=new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for(String name:names) output.set(name,canonical(node.get(name),path+"/"+name));
            return output;
        }
        if(node.isArray()) {
            var values=new ArrayList<JsonNode>(); node.forEach(v->values.add(canonical(v,path+"/[]")));
            // Only schema sets are order independent. Parameter lists and dependency ordering remain significant.
            if(path.equals("/definition/supportedModes")
                    || (path.startsWith("/definition/parameters/") && path.endsWith("/choices")))
                values.sort(Comparator.comparing(JsonNode::toString));
            var output=JsonNodeFactory.instance.arrayNode(); values.forEach(output::add); return output;
        }
        return node.deepCopy();
    }
}
