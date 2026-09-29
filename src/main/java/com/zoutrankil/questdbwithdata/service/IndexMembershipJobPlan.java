package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.*;

/** The day bounds observation, not an unsupported upstream trade-date filter. */
public final class IndexMembershipJobPlan {
    private IndexMembershipJobPlan() {}
    public static SyncJobDefinition definition() {
        return new SyncJobDefinition("data.index_member",1,"index_member",1,"index_membership_owner",
                Set.of(Mode.INCREMENTAL),Mode.INCREMENTAL,Map.of(
                "classificationReceipt",new Parameter(ParameterType.STRING,true,4096,1,Set.of()),
                "classificationFingerprint",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                "industryCodes",new Parameter(ParameterType.STRING_LIST,true,9,32,Set.of()),
                "selection",new Parameter(ParameterType.STRING,true,16,1,Set.of("CURRENT","HISTORICAL","BOTH"))),
                "tushare.shared","index_member.l2_explicit","questdb.full_key_values",
                new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofMinutes(30),
                new Budget(1,32,64,128000,256*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    }
    public static FrozenRequest freeze(IndexMembershipClassificationSource.Catalog catalog,List<String> codes,
                                       IndexMembershipSource.Selection selection,LocalDate day) throws Exception {
        var trusted=IndexMembershipClassificationSource.reopen(Path.of(catalog.receipt()),catalog.fingerprint());
        if(!trusted.industries().equals(catalog.industries())) throw new IllegalArgumentException("Classification object differs from receipt");
        trusted.select(codes,selection);
        return definition().freeze(null,Map.of("classificationReceipt",trusted.receipt(),"classificationFingerprint",trusted.fingerprint(),
                "industryCodes",List.copyOf(codes),"selection",selection.name()),day,day,day);
    }
    public static List<IndexMembershipSource.Scope> scopes(FrozenRequest request) throws Exception {
        if(!request.definition().equals(definition()) || request.mode()!=Mode.INCREMENTAL || request.from()==null
                || !request.from().equals(request.to()) || !request.from().equals(request.logicalDate()))
            throw new IllegalArgumentException("Frozen membership observation contract required");
        var p=request.parameters();
        var catalog=IndexMembershipClassificationSource.reopen(Path.of((String)p.get("classificationReceipt")),
                (String)p.get("classificationFingerprint"));
        @SuppressWarnings("unchecked") var codes=(List<String>)p.get("industryCodes");
        return catalog.select(codes,IndexMembershipSource.Selection.valueOf((String)p.get("selection")));
    }
    static FrozenRequest restore(String frozenJson) throws Exception {
        var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();var saved=json.readTree(frozenJson);
        if(!definition().equals(json.treeToValue(saved.path("definition"),SyncJobDefinition.class)))
            throw new IllegalArgumentException("Frozen membership definition changed");
        Map<String,Object> parameters=json.convertValue(saved.path("parameters"),new com.fasterxml.jackson.core.type.TypeReference<>() {});
        var request=definition().freeze(Mode.valueOf(saved.path("mode").asText()),parameters,
                LocalDate.parse(saved.path("from").asText()),LocalDate.parse(saved.path("to").asText()),
                LocalDate.parse(saved.path("logicalDate").asText()));
        scopes(request);return request;
    }
}
