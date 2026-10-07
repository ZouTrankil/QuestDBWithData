package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.PageContract;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Observation-period and date-window provider requests. */
final class MonthlySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override protected void validateRange(SourceContract c,LocalDate logicalDate,LocalDate start,LocalDate end) {
        long months=java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(start),YearMonth.from(end))+1;
        if(start.getDayOfMonth()!=1||end.getDayOfMonth()!=1||months>Math.min(c.maxRows(),2000))
            throw new IllegalArgumentException("Monthly range must use month starts and stay within the source row budget");
    }
    @Override protected void validateLogicalDate(SourceContract c,LocalDate date) {
        if(date.getDayOfMonth()!=1)
            throw new IllegalArgumentException(c.dataset()+" logical date must identify the first day of its observation month");
    }
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("m","start_m","end_m"),c.sourceDocumentation());
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=new LinkedHashMap<String,Object>();
        params.put(c.dateParameter(),request.logicalDate().format(SourceContract.BASIC_MONTH));
        params.remove("m");
        if(request.rangeStart().equals(request.rangeEnd())) params.put("m",request.logicalDate().format(SourceContract.BASIC_MONTH));
        else { params.put("start_m",request.rangeStart().format(SourceContract.BASIC_MONTH)); params.put("end_m",request.rangeEnd().format(SourceContract.BASIC_MONTH)); }
        return params;
    }
}

final class QuarterlySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override protected void validateRange(SourceContract c,LocalDate logicalDate,LocalDate start,LocalDate end) {
        long quarters=SourcePeriods.quartersBetween(start,end)+1;
        if(!SourcePeriods.isQuarterEnd(start)||!SourcePeriods.isQuarterEnd(end)||quarters>Math.min(c.maxRows(),2000))
            throw new IllegalArgumentException("Quarterly range must use quarter ends and stay within the source row budget");
    }
    @Override protected void validateLogicalDate(SourceContract c,LocalDate date) {
        if(!SourcePeriods.isQuarterEnd(date))
            throw new IllegalArgumentException(c.dataset()+" logical date must identify a quarter end");
    }
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c).stream().filter(f -> !f.equals("report_date")).toList(),List.of("quarter"),
                Set.of("q","start_q","end_q"),c.sourceDocumentation());
    }
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);
        if(request.rangeStart().equals(request.rangeEnd())) params.put("q",SourcePeriods.quarter(request.logicalDate()));
        else { params.remove("q");params.put("start_q",SourcePeriods.quarter(request.rangeStart())); params.put("end_q",SourcePeriods.quarter(request.rangeEnd())); }
        return params;
    }
    @Override public SourceCollectionSession openSession(SourceContract c,SourceCollector.Request request,SourceRowPolicy rows) {
        return new StandardSourceCollectionSession(c,request,rows) {
            @Override public List<Map<String,JsonNode>> prepareRows(String providerCode,List<Map<String,JsonNode>> values) {
                var perQuarter=new ArrayList<Map<String,JsonNode>>();
                for(var row:values) perQuarter.addAll(rows.prepareRows(contract,List.of(row),
                        rows.observationDate(contract,row,request.logicalDate()),request.expectedCodes(),providerCode));
                return List.copyOf(perQuarter);
            }
        };
    }
}

class EndDateSourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override protected LinkedHashMap<String,Object> baseParameters(SourceContract c,SourceCollector.Request request) {
        var params=super.baseParameters(c,request);
        params.put("end_date",request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        return params;
    }
}

final class UnpagedDateRangeSourceRequestPolicy extends EndDateSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("start_date","end_date"),c.sourceDocumentation());
    }
}

final class UsTreasurySourceRequestPolicy extends StandardSourceRequestPolicy {
    @Override public PageContract pageContract(SourceContract c) {
        return unpaged(c,sourceFields(c),sourceKeys(c),Set.of("date"),c.sourceDocumentation());
    }
}
