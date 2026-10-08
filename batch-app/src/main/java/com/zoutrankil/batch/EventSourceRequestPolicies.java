package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import java.util.*;

/** Event sources retain their provider response shape until raw evidence has been written. */
final class StHistorySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return new PageContract(c.endpoint(),List.of("ts_code","name","start_date","end_date","ann_date","change_reason"),
                List.of("ts_code","name","start_date"),Set.of("start_date","end_date","ts_code","limit","cursor"),
                PageContract.Paging.CURSOR,PageContract.Completion.EXPLICIT_END,"limit","cursor",
                c.pageSize(),c.pageSize(),100,c.maxRows(),c.sourceDocumentation());
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);params.put("start_date","20100101");return params;
    }
    @Override public SourceCollectionSession openSession(SourceContract c,SourceCollector.Request request,SourceRowPolicy rows) {
        return new StHistoryCollectionSession(c,request,rows);
    }
}

final class SuspensionSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,List.of("ts_code","trade_date","suspend_timing","suspend_type"),
                List.of("ts_code","trade_date"),Set.of("trade_date","ts_code","suspend_type"),c.sourceDocumentation());
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);params.put("suspend_type","S");return params;
    }
}
