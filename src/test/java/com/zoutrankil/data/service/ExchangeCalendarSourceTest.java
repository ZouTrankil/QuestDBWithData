package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCalendarSourceTest {
    @TempDir Path temp;
    private final ExchangeCalendarSlices.Slice slice=new ExchangeCalendarSlices.Slice(
            "SSE",LocalDate.of(2026,9,25),LocalDate.of(2026,9,26));
    private Map<String,JsonNode> row(String day,JsonNode flag) {
        var f=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        return Map.of("exchange",f.textNode("SSE"),"cal_date",f.textNode(day),"is_open",flag,
                "pretrade_date",f.textNode("20260924"));
    }
    private ExchangeCalendarSource source(PageExecutor.Fetcher fetcher) {
        return new ExchangeCalendarSource(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> parameters,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                assertEquals(Set.of("exchange","start_date","end_date"),parameters.keySet());
                return new PageExecutor().execute(contract,parameters,fetcher,consumer,validator,cancelled);
            }
        },temp);
    }
    @Test void malformedAndIncompleteResponsesNeverYieldAnAcceptedSlice() {
        var f=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        var a=row("20260925",f.numberNode(0));var b=row("20260926",f.numberNode(0));
        for(var rows:List.of(List.<Map<String,JsonNode>>of(),List.of(a),List.of(a,a),
                List.of(a,row("20260927",f.numberNode(1))),List.of(a,row("20260926",f.textNode("0"))))) {
            var source=source(params->new PageExecutor.Page(rows,null,false,null));
            assertThrows(Exception.class,()->source.fetch(slice,()->false));
        }
        var valid=source(params->new PageExecutor.Page(List.of(b,a),null,false,null));
        assertDoesNotThrow(()->assertEquals(2,valid.fetch(slice,()->false).rows().size()));
    }
    @Test void providerFailureAndCancellationRemainFailuresRatherThanEmptyCalendar() {
        var failure=source(params->{throw new java.io.IOException("source unavailable");});
        var incomplete=assertThrows(PageExecutor.Incomplete.class,()->failure.fetch(slice,()->false));
        assertEquals(0,incomplete.consumedRows());
        var cancelled=source(params->{throw new AssertionError("cancelled request reached source");});
        assertThrows(PageExecutor.Incomplete.class,()->cancelled.fetch(slice,()->true));
    }
}
