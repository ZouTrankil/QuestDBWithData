package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Provider routes that explicitly fan out over the admitted entity set. */
class PerEntitySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override protected ArrayList<String> queryCodes(SourceCollector.Request request) { return new ArrayList<>(request.expectedCodes()); }
}

class IndexSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("trade_date","ts_code"),c.sourceDocumentation());
    }
}

final class IndexMarketSourceRequestPolicy extends IndexSourceRequestPolicy {
    @Override public PageContract providerContract(SourceContract c,String code) {
        if(code==null || !code.endsWith(".SI")) return pageContract(c);
        var fields=sourceFields(c).stream().filter(f -> !f.equals("pre_close"))
                .map(f -> f.equals("pct_chg")?"pct_change":f).toList();
        return new PageContract("sw_daily",fields,sourceKeys(c),Set.of("trade_date","ts_code"),
                PageContract.Paging.NONE,PageContract.Completion.SHORT_PAGE,null,null,c.pageSize(),c.pageSize(),1,
                c.maxRows(),"legacy index_daily_market sw_daily route; pct_change maps to pct_chg");
    }
}

final class CalendarSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("exchange","start_date","end_date"),c.sourceDocumentation());
    }
    @Override protected String entityParameter() { return "exchange"; }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);
        params.put("end_date",request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        return params;
    }
}

class SingleFuturesSourceRequestPolicy extends PerEntitySourceRequestPolicy {
    @Override protected void validateCardinality(SourceContract c,Set<String> expectedCodes) {
        if(expectedCodes.size()!=1)
            throw new IllegalArgumentException(c.dataset()+" currently requires one explicit futures contract/product per trade date");
    }
}

final class FuturesHoldingSourceRequestPolicy extends SingleFuturesSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return shortPage(c,sourceFields(c),sourceKeys(c),Set.of("trade_date","symbol","exchange"),
                c.paged()?PageContract.Paging.OFFSET:PageContract.Paging.NONE,64,c.sourceDocumentation());
    }
    @Override public PageContract providerContract(SourceContract c,String code) {
        return unpaged(c,sourceFields(c).stream().filter(f -> !f.equals("exchange")).toList(),sourceKeys(c),
                Set.of("trade_date","symbol","exchange"),
                c.sourceDocumentation()+"; optional provider exchange column is nullable in the legacy model");
    }
    @Override protected String entityParameter() { return "symbol"; }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);params.put("exchange","CFFEX");return params;
    }
}

final class ChipSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return shortPage(c,sourceFields(c),sourceKeys(c),Set.of("trade_date","ts_code","limit","offset"),
                PageContract.Paging.OFFSET,100,c.sourceDocumentation());
    }
}
