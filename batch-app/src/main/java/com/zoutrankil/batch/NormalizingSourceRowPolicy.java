package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record NormalizingSourceRowPolicy(SourceRowPreparation preparation, SourceValuePolicy values,
                                  SourceRowScope scope, Footer footer, Observation observation) implements SourceRowPolicy {
    enum Footer { NONE, MARGIN }
    enum Observation { FROZEN_DATE, MONTH, QUARTER }
    NormalizingSourceRowPolicy {
        Objects.requireNonNull(preparation); Objects.requireNonNull(values); Objects.requireNonNull(scope);
        Objects.requireNonNull(footer); Objects.requireNonNull(observation);
    }
    @Override public List<Map<String,JsonNode>> prepareRows(SourceContract contract, List<Map<String,JsonNode>> rows,
            LocalDate date, Set<String> expectedCodes, String providerCode) {
        return preparation.prepare(contract,rows,date,expectedCodes,providerCode);
    }
    @Override public boolean ignoredFooter(SourceContract contract, Map<String,JsonNode> row, LocalDate date) {
        if (footer == Footer.NONE) return false;
        var code=row.get("ts_code");var sourceDay=row.get("trade_date");
        if(code==null || !code.isTextual() || !code.asText().equals("日期："+date+".BJ")
                || sourceDay==null || !sourceDay.isTextual()
                || !sourceDay.asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE))) return false;
        return contract.columns().stream().filter(c -> c.type().equals("DOUBLE"))
                .allMatch(c -> row.containsKey(c.source()) && row.get(c.source()).isNull());
    }
    @Override public Map<String,Object> normalize(SourceContract contract, Map<String,JsonNode> row,
                                                 LocalDate date, Set<String> expectedCodes) {
        values.beforeRow(date);
        var normalized=new LinkedHashMap<String,Object>();
        for(var column:contract.businessColumns()) {
            JsonNode value=row.get(column.source());
            if(value==null) throw new IllegalArgumentException("Schema drift: missing "+column.source());
            if(value.isNull()) {
                if(column.key()) throw new IllegalArgumentException("Null business key");
                normalized.put(column.target(),values.nullValue(column)); continue;
            }
            Object converted=values.convert(contract,column,value,date);
            values.afterValue(column,value,date);
            normalized.put(column.target(),converted);
        }
        scope.validate(contract,row,normalized,date,expectedCodes);
        return Collections.unmodifiableMap(normalized);
    }
    @Override public Map<String,Object> validateNormalized(SourceContract contract, Map<String,Object> row,
                                                          LocalDate date, Set<String> expectedCodes) {
        var source=new LinkedHashMap<String,JsonNode>();
        var business=contract.businessColumns();
        if(!row.keySet().equals(new HashSet<>(business.stream().map(SourceContract.Column::target).toList())))
            throw new IllegalArgumentException("Frozen schema differs from registered contract");
        for(var column:business) source.put(column.source(),Json.MAPPER.valueToTree(values.sourceValue(column,row.get(column.target()))));
        return normalize(contract,source,date,expectedCodes);
    }
    @Override public LocalDate observationDate(SourceContract contract, Map<String,JsonNode> row, LocalDate fallback) {
        if(observation == Observation.MONTH) {
            var month=row.get("month"); if(month==null||!month.isTextual()) return fallback;
            return YearMonth.parse(month.asText(),SourceContract.BASIC_MONTH).atDay(1);
        }
        if(observation == Observation.QUARTER) {
            var quarter=row.get("quarter"); if(quarter==null||!quarter.isTextual()||!quarter.asText().matches("[0-9]{4}Q[1-4]")) return fallback;
            int year=Integer.parseInt(quarter.asText().substring(0,4)),q=Character.digit(quarter.asText().charAt(5),10);
            return YearMonth.of(year,q*3).atEndOfMonth();
        }
        return fallback;
    }
}
