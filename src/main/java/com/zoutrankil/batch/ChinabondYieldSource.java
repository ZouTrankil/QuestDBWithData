package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.client.SharedRequestBudget;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.service.PageExecutor;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpStatusCode;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Direct Java reader for the public ChinaBond yield history table; raw wide rows are archived upstream. */
public final class ChinabondYieldSource {
    private static final String BASE="https://yield.chinabond.com.cn";
    private static final String BUDGET_ID="public-chinabond-yield-v1";
    private final WebClient web;private final SharedRequestBudget budget;private final TushareProperties limits;
    public ChinabondYieldSource(WebClient web,SharedRequestBudget budget,TushareProperties limits) {
        this.web=web;this.budget=budget;this.limits=limits;
    }
    public PageExecutor.Fetcher fetcher() {
        return params -> {
            String start=basic(params,"start_date"),end=basic(params,"end_date");
            if(LocalDate.parse(start,DateTimeFormatter.BASIC_ISO_DATE).isAfter(LocalDate.parse(end,DateTimeFormatter.BASIC_ISO_DATE))
                    || LocalDate.parse(end,DateTimeFormatter.BASIC_ISO_DATE).isAfter(LocalDate.parse(start,DateTimeFormatter.BASIC_ISO_DATE).plusYears(1).minusDays(1)))
                throw new IllegalArgumentException("ChinaBond query interval must be positive and shorter than one year");
            var future=budget.execute("chinabond_yield",BUDGET_ID,() -> web.get()
                    .uri(uri -> uri.path("/cbweb-pbc-web/pbc/historyQuery")
                            .queryParam("startDate",iso(start)).queryParam("endDate",iso(end)).queryParam("gjqx","0")
                            .queryParam("qxId","ycqx").queryParam("locale","cn_ZH").build())
                    .header("User-Agent","Mozilla/5.0 (compatible; QuestDBWithData/1.0)")
                    .header("Referer","https://www.chinabond.com.cn/")
                    .header("Accept","text/html,application/xhtml+xml")
                    .retrieve().onStatus(HttpStatusCode::isError,response -> response.releaseBody()
                            .then(reactor.core.publisher.Mono.error(new IllegalStateException("ChinaBond HTTP status "+response.statusCode().value()))))
                    .bodyToMono(String.class).timeout(limits.getRequestTimeout())).toFuture();
            try {
                String html;
                while(true) {
                    if(Thread.currentThread().isInterrupted()) throw new CancellationException("ChinaBond read interrupted");
                    try { html=future.get(100,TimeUnit.MILLISECONDS);break; }
                    catch(TimeoutException waiting) { }
                }
                var rows=parse(html,LocalDate.parse(start,DateTimeFormatter.BASIC_ISO_DATE));
                return new PageExecutor.Page(rows,null,false,"chinabond-history-v1");
            } catch(ExecutionException failure) {
                Throwable cause=failure.getCause();if(cause instanceof Exception exception) throw exception;
                throw new IllegalStateException("ChinaBond request failed");
            } finally { if(!future.isDone()) future.cancel(true); }
        };
    }
    private static String basic(Map<String,Object> params,String name) {
        Object value=params.get(name);if(!(value instanceof String text)||!text.matches("[0-9]{8}")) throw new IllegalArgumentException("Explicit ChinaBond dates required");return text;
    }
    private static String iso(String basic) { return basic.substring(0,4)+"-"+basic.substring(4,6)+"-"+basic.substring(6,8); }

    static List<Map<String,JsonNode>> parse(String html,LocalDate requestedDate) throws Exception {
        if(html==null||html.isBlank()||html.length()>8*1024*1024) throw new IllegalArgumentException("ChinaBond response is empty or exceeds byte bound");
        var tables=new ArrayList<List<List<String>>>();var rows=new ArrayList<List<String>>();var cells=new ArrayList<String>();
        var cell=new StringBuilder();int[] table={-1};boolean[] inCell={false};
        new ParserDelegator().parse(new StringReader(html),new HTMLEditorKit.ParserCallback() {
            @Override public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {
                if(tag==HTML.Tag.TABLE) {table[0]++;if(table[0]==1) tables.add(rows);}
                if(table[0]==1&&tag==HTML.Tag.TR) cells.clear();
                if(table[0]==1&&(tag==HTML.Tag.TD||tag==HTML.Tag.TH)) {cell.setLength(0);inCell[0]=true;}
            }
            @Override public void handleText(char[] data,int pos) {if(table[0]==1&&inCell[0])cell.append(data);}
            @Override public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {if(table[0]==1&&tag==HTML.Tag.BR&&inCell[0])cell.append(' ');}
            @Override public void handleEndTag(HTML.Tag tag,int pos) {
                if(table[0]==1&&(tag==HTML.Tag.TD||tag==HTML.Tag.TH)&&inCell[0]) {cells.add(cell.toString().replace('\u00a0',' ').trim());inCell[0]=false;}
                if(table[0]==1&&tag==HTML.Tag.TR&&!cells.isEmpty()) {rows.add(List.copyOf(cells));cells.clear();}
                if(tag==HTML.Tag.TABLE&&table[0]==1)table[0]=-1;
            }
        },true);
        if(tables.isEmpty()) throw new IllegalArgumentException("ChinaBond response missing declared data table");
        var tableRows=tables.getFirst();
        // Parser callback stores each completed row; strip an initial empty accumulator if present.
        tableRows=tableRows.stream().filter(r -> !r.isEmpty()).toList();
        if(tableRows.isEmpty()) throw new IllegalArgumentException("ChinaBond data table is empty or malformed");
        var header=tableRows.getFirst();var indexes=new LinkedHashMap<String,Integer>();
        for(String field:List.of("曲线名称","日期","3月","6月","1年","3年","5年","7年","10年","30年")) {
            int i=header.indexOf(field);if(i<0)throw new IllegalArgumentException("ChinaBond schema drift: missing "+field);indexes.put(field,i);
        }
        var output=new ArrayList<Map<String,JsonNode>>();
        for(int index=1;index<tableRows.size();index++) {
            var values=tableRows.get(index);if(values.size()!=header.size())throw new IllegalArgumentException("ChinaBond table row width changed");
            LocalDate date=LocalDate.parse(values.get(indexes.get("日期")),DateTimeFormatter.ISO_LOCAL_DATE);
            if(!date.equals(requestedDate)) continue;
            var row=new LinkedHashMap<String,JsonNode>();
            row.put("raw_trade_date",Json.MAPPER.valueToTree(date.toString()));row.put("raw_curve_name",Json.MAPPER.valueToTree(values.get(indexes.get("曲线名称"))));
            for(String field:List.of("3月","6月","1年","3年","5年","7年","10年","30年")) {
                String suffix=switch(field){case "3月"->"3m";case "6月"->"6m";case "1年"->"1y";case "3年"->"3y";case "5年"->"5y";case "7年"->"7y";case "10年"->"10y";default->"30y";};
                String text=values.get(indexes.get(field)).trim().replace(",","");
                JsonNode value=text.isBlank()||Set.of("-","--","—").contains(text)?Json.MAPPER.nullNode():
                        text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")?Json.MAPPER.valueToTree(Double.parseDouble(text)):null;
                if(value==null)throw new IllegalArgumentException("Invalid ChinaBond numeric field "+field);
                row.put("raw_"+suffix,value);
            }
            output.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(output);
    }
}
