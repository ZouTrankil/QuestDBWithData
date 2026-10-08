package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.service.PageExecutor;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** ST event accumulation belongs to one collection, never to the reusable strategy. */
final class StHistoryCollectionSession extends StandardSourceCollectionSession {
    private final List<Map<String,JsonNode>> history=new ArrayList<>();

    StHistoryCollectionSession(SourceContract c,SourceCollector.Request request,SourceRowPolicy rows) { super(c,request,rows); }

    @Override public void validateRow(Map<String,JsonNode> row) {
        for(String field:List.of("ts_code","name","start_date","end_date","ann_date","change_reason"))
            if(!row.containsKey(field)) throw new IllegalArgumentException("ST history schema drift: missing "+field);
        String code=row.get("ts_code").asText().trim();
        if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("Invalid ST history code");
        if(!row.get("name").isTextual()||!row.get("start_date").isTextual()||!row.get("ann_date").isTextual()) throw new IllegalArgumentException("Invalid ST history fields");
        LocalDate.parse(row.get("start_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate.parse(row.get("ann_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
        var end=row.get("end_date"); if(end!=null&&!end.isNull()&&!end.asText().isBlank()) LocalDate.parse(end.asText(),DateTimeFormatter.BASIC_ISO_DATE);
    }
    @Override public void acceptPage(PageExecutor.Page page,Consumer<Map<String,Object>> normalizedSink) {
        history.addAll(page.rows());
    }
    @Override public void finish(List<Map<String,Object>> normalized) {
        var active=new TreeSet<String>();
        for(var event:history) {
            String name=event.get("name").asText();
            if(!name.toUpperCase(Locale.ROOT).contains("ST")) continue;
            LocalDate start=LocalDate.parse(event.get("start_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
            var endNode=event.get("end_date");
            LocalDate end=endNode==null||endNode.isNull()||endNode.asText().isBlank()?request.logicalDate():LocalDate.parse(endNode.asText(),DateTimeFormatter.BASIC_ISO_DATE);
            if(!start.isAfter(request.logicalDate())&&!end.isBefore(request.logicalDate())) active.add(event.get("ts_code").asText().trim());
        }
        for(String code:active) {
            var row=new LinkedHashMap<String,JsonNode>();
            row.put("ts_code",Json.MAPPER.valueToTree(code)); row.put("trade_date",Json.MAPPER.valueToTree(request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE)));
            row.put("is_st",Json.MAPPER.valueToTree(1)); normalized.add(rows.normalize(contract,row,request.logicalDate(),request.expectedCodes()));
        }
    }
}
