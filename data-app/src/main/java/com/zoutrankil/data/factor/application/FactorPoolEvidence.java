package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Validated pool computation output. This is not coverage of the GLOBAL observation table. */
public final class FactorPoolEvidence {
    private FactorPoolEvidence() {}
    public static final int MAX_BYTES=128*1024*1024;
    public record Snapshot(byte[] bytes,String sha256,String inputFingerprint,
            List<FactorIncrementalPlanner.Requested> sections) {}
    public static Snapshot read(Path path) throws Exception {
        return decode(FileEvidenceStore.readBounded(path,MAX_BYTES,
                () -> new IllegalArgumentException("Pool evidence exceeds 128 MiB")));
    }
    public static Snapshot decode(byte[] bytes) throws Exception {
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Pool evidence exceeds 128 MiB");
        var root=JobDefinitionJson.mapper().readTree(bytes);
        if(!root.path("schema").asText().equals("java.factor_pool_values.v1")
                || !root.path("sourceComplete").isBoolean() || root.path("sourceComplete").booleanValue())
            throw new IllegalArgumentException("Expected partial pool evidence; source certification is not implemented");
        var identity=root.path("inputIdentity");
        String fingerprint=root.path("inputFingerprint").asText();
        if(!fingerprint.matches("[a-f0-9]{64}") || !fingerprint.equals(FileEvidenceStore.sha256(
                JobDefinitionJson.canonicalMapper().writeValueAsBytes(JobDefinitionJson.mapper().convertValue(identity,Map.class)))))
            throw new IllegalArgumentException("Pool input identity digest mismatch");
        var factors=textSet(identity.path("factors"));var pools=textSet(identity.path("pools"));
        if(factors.isEmpty() || pools.isEmpty())throw new IllegalArgumentException("Explicit factors and pools required");
        LocalDate from=LocalDate.parse(identity.path("from").asText()),through=LocalDate.parse(identity.path("through").asText());
        if(through.isBefore(from) || through.toEpochDay()-from.toEpochDay()>30)
            throw new IllegalArgumentException("Bounded pool date interval required");
        var expected=new HashMap<String,Set<String>>();
        var coverageKeys=new HashSet<String>();
        for(var coverage:array(root,"coverage")) {
            LocalDate date=LocalDate.parse(coverage.path("date").asText());String pool=coverage.path("pool").asText();
            String key=date+"/"+pool;
            if(date.isBefore(from) || date.isAfter(through) || !pools.contains(pool) || !coverageKeys.add(key)
                    || !coverage.path("available").isBoolean())throw new IllegalArgumentException("Invalid pool coverage");
            if(!coverage.path("available").booleanValue())continue;
            var symbols=new HashSet<String>();var allSymbols=new HashSet<String>();
            for(var member:array(coverage,"members")) {
                String symbol=member.path("symbol").asText();
                if(symbol.isBlank() || !allSymbols.add(symbol))throw new IllegalArgumentException("Duplicate pool member");
                if(array(member,"reasons").isEmpty())symbols.add(symbol);
            }
            expected.put(key,symbols);
        }
        var requests=new ArrayList<FactorIncrementalPlanner.Requested>();var sections=new HashSet<String>();
        long count=0;
        for(var section:array(root,"sections")) {
            LocalDate date=LocalDate.parse(section.path("date").asText());String pool=section.path("poolId").asText();
            var definition=section.path("definition");String factor=definition.path("factorId").asText();
            String key=date+"/"+pool;var symbols=textSet(array(section,"symbols"));
            if(!factors.contains(factor) || !sections.add(key+"/"+factor) || !symbols.equals(expected.get(key)))
                throw new IllegalArgumentException("Pool section has missing, duplicate or unexpected members");
            count+=symbols.size();if(count>100_000)throw new IllegalArgumentException("Pool result exceeds row budget");
            var values=section.path("values");
            for(String column:List.of("raw","rank","zscore","neutralized","effective","status"))
                if(array(values,column).size()!=symbols.size())throw new IllegalArgumentException("Misaligned pool values");
            var id=new FactorIncrementalPlanner.Section(date,factor,definition.path("version").asText(),pool);
            requests.add(new FactorIncrementalPlanner.Requested(id,fingerprint,definition.path("lookbackPeriods").asInt(-1)));
        }
        if(sections.size()!=(long)expected.size()*factors.size())throw new IllegalArgumentException("Missing pool factor sections");
        return new Snapshot(bytes.clone(),FileEvidenceStore.sha256(bytes),fingerprint,List.copyOf(requests));
    }
    private static JsonNode array(JsonNode parent,String field) {
        var node=parent.path(field);if(!node.isArray())throw new IllegalArgumentException("Expected array: "+field);return node;
    }
    private static Set<String> textSet(JsonNode values) {
        if(!values.isArray())throw new IllegalArgumentException("Expected identifier array");
        var set=new HashSet<String>();
        for(var value:values)if(!value.isTextual() || value.asText().isBlank() || !set.add(value.asText()))
            throw new IllegalArgumentException("Unique nonempty identifiers required");
        return Set.copyOf(set);
    }
}
