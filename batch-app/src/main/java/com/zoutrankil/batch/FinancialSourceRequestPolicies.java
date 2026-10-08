package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.service.PageExecutor;
import java.time.LocalDate;
import java.util.*;

/** Publication and report-period request rules with their original narrow scopes. */
final class MainBusinessSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override protected void validateLogicalDate(SourceContract c,LocalDate date) {
        if(!SourcePeriods.isQuarterEnd(date)) throw new IllegalArgumentException("fina_mainbz logical date must be a quarter end");
    }
    @Override protected void validateCardinality(SourceContract c,Set<String> expectedCodes) {
        if(expectedCodes.size()!=1) throw new IllegalArgumentException("fina_mainbz currently requires one explicit security scope");
    }
    @Override protected List<String> categories() { return List.of("P","D"); }
    @Override public PageContract pageContract(SourceContract c) {
        return shortPage(c,sourceFields(c),sourceKeys(c),Set.of("period","type","ts_code","limit","offset"),
                PageContract.Paging.OFFSET,20,c.sourceDocumentation());
    }
    @Override public SourceCollectionSession openSession(SourceContract c,SourceCollector.Request request,SourceRowPolicy rows) {
        return new MainBusinessCollectionSession(c,request,rows);
    }
}

final class MainBusinessCollectionSession extends StandardSourceCollectionSession {
    MainBusinessCollectionSession(SourceContract c,SourceCollector.Request request,SourceRowPolicy rows) { super(c,request,rows); }
    @Override public void validateResponse(Map<String,Object> pageParams,PageExecutor.Page page) {
        String requestedType=Objects.toString(pageParams.get("type"),"");
        if(!Set.of("P","D").contains(requestedType) || page.rows().stream().anyMatch(row -> {
            var category=row.get("bz_code");return category==null||!category.isTextual()||!requestedType.equals(category.asText().trim());
        })) throw new IllegalArgumentException("fina_mainbz response category does not match request");
    }
    @Override public void finish(List<Map<String,Object>> normalized) {
        var keys=new HashMap<String,String>();
        for(var row:normalized) {
            String key=contract.key(row),category=Objects.toString(row.get("bz_code"),"");
            String prior=keys.putIfAbsent(key,category);
            if(prior!=null) throw new IllegalArgumentException("P/D categories collide under the registered fina_mainbz business key");
        }
    }
}

final class DividendSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override protected void validateCardinality(SourceContract c,Set<String> expectedCodes) {
        if(expectedCodes.size()!=1)
            throw new IllegalArgumentException("dividend currently requires one explicit security scope per announcement date");
    }
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("ann_date","ts_code"),c.sourceDocumentation());
    }
}

final class AuditSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override protected void validateCardinality(SourceContract c,Set<String> expectedCodes) {
        if(expectedCodes.size()!=1)
            throw new IllegalArgumentException("fina_audit currently requires one explicit security scope per announcement date");
    }
}
