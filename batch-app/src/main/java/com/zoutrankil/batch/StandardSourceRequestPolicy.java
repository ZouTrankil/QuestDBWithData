package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Common bounded security request mechanics, with narrow provider-specific hooks. */
class StandardSourceRequestPolicy implements SourceRequestPolicy {
    @Override public Set<String> validate(SourceContract contract, LocalDate logicalDate, Set<String> expectedCodes,
                                          String universeVersion, String tsCode, LocalDate rangeStart, LocalDate rangeEnd) {
        Objects.requireNonNull(logicalDate);
        Objects.requireNonNull(rangeStart); Objects.requireNonNull(rangeEnd);
        if(rangeStart.isAfter(rangeEnd)||!logicalDate.equals(rangeEnd))
            throw new IllegalArgumentException("Logical date must equal range end");
        validateRange(contract,logicalDate,rangeStart,rangeEnd);
        expectedCodes=Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
        validateLogicalDate(contract,logicalDate);
        if((!contract.isMarketAggregate() && expectedCodes.isEmpty()) || (contract.isMarketAggregate() && (!expectedCodes.isEmpty() || tsCode!=null)) || expectedCodes.size()>20000 || universeVersion==null || universeVersion.isBlank())
            throw new IllegalArgumentException("Versioned bounded expected entity coverage required");
        if(expectedCodes.stream().anyMatch(c -> !contract.validCode(c))) throw new IllegalArgumentException("Invalid universe code");
        if(tsCode!=null && (!expectedCodes.equals(Set.of(tsCode)))) throw new IllegalArgumentException("Filtered source scope must match expected universe");
        validateCardinality(contract,expectedCodes);
        return expectedCodes;
    }

    protected void validateRange(SourceContract contract,LocalDate logicalDate,LocalDate start,LocalDate end) {
        if(!start.equals(logicalDate)||!end.equals(logicalDate))
            throw new IllegalArgumentException("Only monthly or quarterly aggregates support ranges");
    }
    protected void validateLogicalDate(SourceContract contract,LocalDate date) {}
    protected void validateCardinality(SourceContract contract,Set<String> expectedCodes) {}

    @Override public PageContract pageContract(SourceContract c) {
        return shortPage(c,sourceFields(c),sourceKeys(c),c.isMarketAggregate()?Set.of("start_date","end_date"):
                        Set.of(c.dateParameter(),"ts_code","limit","offset","suspend_type"),
                c.paged()?PageContract.Paging.OFFSET:PageContract.Paging.NONE,64,c.sourceDocumentation());
    }
    @Override public PageContract providerContract(SourceContract contract,String code) { return pageContract(contract); }

    @Override public List<Map<String,Object>> queries(SourceContract contract,SourceCollector.Request request) {
        var params=baseParameters(contract,request);
        var queryCodes=queryCodes(request);
        if(queryCodes.isEmpty()) queryCodes.add(request.tsCode());
        var queries=new ArrayList<Map<String,Object>>();
        for(String code:queryCodes) {
            for(String category:categories()) {
                var queryParams=new LinkedHashMap<>(params);
                if(code!=null) queryParams.put(entityParameter(),code);
                if(category!=null) queryParams.put("type",category);
                queries.add(queryParams);
            }
        }
        return List.copyOf(queries);
    }
    protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=new LinkedHashMap<String,Object>();
        params.put(c.dateParameter(),request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        return params;
    }
    protected ArrayList<String> queryCodes(SourceCollector.Request request) { return new ArrayList<>(); }
    protected String entityParameter() { return "ts_code"; }
    protected List<String> categories() { return Arrays.asList((String)null); }

    @Override public SourceCollectionSession openSession(SourceContract contract,SourceCollector.Request request,SourceRowPolicy rows) {
        return new StandardSourceCollectionSession(contract,request,rows);
    }

    protected static List<String> sourceFields(SourceContract c) { return c.businessColumns().stream().map(SourceContract.Column::source).toList(); }
    protected static List<String> sourceKeys(SourceContract c) { return c.columns().stream().filter(SourceContract.Column::key).map(SourceContract.Column::source).toList(); }
    protected static PageContract shortPage(SourceContract c,List<String> fields,List<String> keys,Set<String> allowed,
                                             PageContract.Paging paging,int maxPages,String documentation) {
        return new PageContract(c.endpoint(),fields,keys,allowed,paging,PageContract.Completion.SHORT_PAGE,
                paging==PageContract.Paging.OFFSET?"limit":null,paging==PageContract.Paging.OFFSET?"offset":null,
                c.pageSize(),c.pageSize(),maxPages,c.maxRows(),documentation);
    }
    protected static PageContract unpaged(SourceContract c,List<String> fields,List<String> keys,Set<String> allowed,String documentation) {
        return shortPage(c,fields,keys,allowed,PageContract.Paging.NONE,1,documentation);
    }
}
