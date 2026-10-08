package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import java.util.*;

/** Static and full-market snapshot request contracts. */
final class FuturesBasicSourceRequestPolicy extends SingleFuturesSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) { return providerContract(c,null); }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,sourceFields(c).stream().filter(f -> !f.equals("trade_time_desc")).toList(),List.of("ts_code"),
                Set.of("exchange","fut_type"),
                c.sourceDocumentation()+"; exchange is required, fut_type=1 selects standard contracts, response cap is not paged");
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=new LinkedHashMap<String,Object>();params.put("fut_type","1");return params;
    }
    @Override protected String entityParameter() { return "exchange"; }
}

final class EtfBasicSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) { return providerContract(c,null); }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,sourceFields(c),List.of("ts_code"),Set.of("market","status"),
                c.sourceDocumentation()+"; fund_basic returns a bounded static market snapshot without paging");
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=new LinkedHashMap<String,Object>();params.put("market","E");return params;
    }
    @Override protected String entityParameter() { return "market"; }
}

final class ThsIndexSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) { return providerContract(c,null); }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,sourceFields(c),List.of("ts_code"),Set.of(),
                c.sourceDocumentation()+"; full static snapshot, no request parameters, hard response cap");
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        return new LinkedHashMap<>();
    }
}

final class EtfShareSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) { return providerContract(c,null); }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,List.of("ts_code","trade_date","fd_share"),List.of("ts_code","trade_date"),Set.of("trade_date"),
                c.sourceDocumentation()+"; one full-market trading-date snapshot, no paging, vendor cap must not be reached");
    }
}

final class DisclosureSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) { return providerContract(c,null); }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("end_date"),
                c.sourceDocumentation()+"; one exact report quarter per request, no paging, hard response cap");
    }
}
