package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Restores a failed/cancelled/partial run without consulting today's calendar, checkpoint or target range. */
public final class FrozenRunRequest {
    private FrozenRunRequest() {}
    public record Restored(SyncJobDefinition.FrozenRequest request,String targetId) {}
    public static Restored restore(Path ledgerPath,String priorId,SyncJobDefinition expected)throws Exception {
        Objects.requireNonNull(expected);var ledger=SyncRunLedger.openReadOnly(ledgerPath);
        var prior=ledger.getRun(priorId);var state=ledger.get(priorId).state();
        if(!Set.of(SyncRunState.FAILED,SyncRunState.CANCELLED,SyncRunState.PARTIAL).contains(state)
                ||!prior.jobId().equals(expected.jobId())||prior.jobVersion()!=expected.version())
            throw new IllegalStateException("Only a terminal failed/cancelled/partial run of the current job definition can resume");
        var json=JobDefinitionJson.mapper();var saved=json.readTree(prior.frozenJson());
        var definition=json.treeToValue(saved.path("definition"),SyncJobDefinition.class);
        if(!definition.equals(expected))throw new IllegalStateException("Frozen job definition changed before recovery");
        Map<String,Object> parameters=json.convertValue(saved.path("parameters"),new TypeReference<LinkedHashMap<String,Object>>(){});
        for(var spec:expected.parameters().entrySet())if(parameters.containsKey(spec.getKey())
                &&spec.getValue().type()==SyncJobDefinition.ParameterType.DATE)
            parameters.put(spec.getKey(),LocalDate.parse(saved.path("parameters").path(spec.getKey()).textValue()));
        var from=saved.path("from").isNull()?null:LocalDate.parse(saved.path("from").asText());
        var to=saved.path("to").isNull()?null:LocalDate.parse(saved.path("to").asText());
        var request=expected.freeze(SyncJobDefinition.Mode.valueOf(saved.path("mode").asText()),parameters,
                from,to,LocalDate.parse(saved.path("logicalDate").asText()));
        if(!SyncRequestIdentity.fingerprint(request,prior.targetId()).equals(SyncRequestIdentity.fingerprint(prior.frozenJson(),prior.targetId())))
            throw new IllegalStateException("Restored frozen request differs from durable recovery identity");
        if(parameters.containsKey("targetId")&&!prior.targetId().equals(parameters.get("targetId")))
            throw new IllegalStateException("Frozen target differs from the run target");
        return new Restored(request,prior.targetId());
    }
}
