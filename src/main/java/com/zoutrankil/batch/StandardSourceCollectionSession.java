package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.service.PageExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A collection-local view of immutable policies and the admitted request. */
class StandardSourceCollectionSession implements SourceCollectionSession {
    protected final SourceContract contract;
    protected final SourceCollector.Request request;
    protected final SourceRowPolicy rows;

    StandardSourceCollectionSession(SourceContract contract,SourceCollector.Request request,SourceRowPolicy rows) {
        this.contract=contract;this.request=request;this.rows=rows;
    }
    @Override public void validateResponse(Map<String,Object> pageParams,PageExecutor.Page page) {}
    @Override public List<Map<String,JsonNode>> prepareRows(String providerCode,List<Map<String,JsonNode>> values) {
        return rows.prepareRows(contract,values,request.logicalDate(),request.expectedCodes(),providerCode);
    }
    protected LocalDate observationDate(Map<String,JsonNode> row) {
        LocalDate observation=rows.observationDate(contract,row,request.logicalDate());
        if(observation.isBefore(request.rangeStart())||observation.isAfter(request.rangeEnd()))
            throw new IllegalArgumentException("Source observation is outside requested range");
        return observation;
    }
    @Override public void validateRow(Map<String,JsonNode> row) {
        LocalDate observation=observationDate(row);
        if(!rows.ignoredFooter(contract,row,observation)) rows.normalize(contract,row,observation,request.expectedCodes());
    }
    @Override public void acceptPage(PageExecutor.Page page,Consumer<Map<String,Object>> normalizedSink) {
        for(var row:page.rows()) {
            LocalDate observation=observationDate(row);
            if(rows.ignoredFooter(contract,row,observation)) continue;
            normalizedSink.accept(rows.normalize(contract,row,observation,request.expectedCodes()));
        }
    }
    @Override public void finish(List<Map<String,Object>> normalized) {}
}
