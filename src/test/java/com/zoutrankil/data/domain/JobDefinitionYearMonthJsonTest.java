package com.zoutrankil.data.domain;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.zoutrankil.data.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.repository.EquityStyleMonthlyWritePort;
import com.zoutrankil.data.service.EquityStyleMonthlySource;
import com.zoutrankil.data.service.EquityStyleMonthlyJobService;
import com.zoutrankil.data.service.SyncJobRunner;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Shared CLI and durable evidence JSON must represent monthly periods explicitly. */
class JobDefinitionYearMonthJsonTest {
    @Test void yearMonthRoundTripsCanonicalPositiveFourDigitIsoWithoutAnOptionalTimeModule()throws Exception{
        var json=JobDefinitionJson.mapper();
        for(var month:List.of(YearMonth.of(1,1),YearMonth.of(2026,6),YearMonth.of(9999,12))){
            String encoded=json.writeValueAsString(month);assertEquals("\""+month+"\"",encoded);assertEquals(month,json.readValue(encoded,YearMonth.class));
        }
        for(var invalid:List.of(YearMonth.of(0,1),YearMonth.of(-1,12),YearMonth.of(10000,1)))
            assertThrows(JsonProcessingException.class,()->json.writeValueAsString(invalid));
    }
    @Test void yearMonthRejectsInvalidDatesNoncanonicalStringsAndNonstringCarriers(){
        var json=JobDefinitionJson.mapper();
        for(String encoded:List.of("\"202606\"","\"2026-6\"","\"2026-00\"","\"2026-13\"","\"2026-06-01\"","\" 2026-06\"","\"2026-06 \"","\"0000-01\"","\"+10000-01\"","\"-0001-01\"","202606","true","[]","{}"))
            assertThrows(JsonProcessingException.class,()->json.readValue(encoded,YearMonth.class),encoded);
    }
    @Test void actualD103SourceBatchNestedMonthlyRowsSerializeAndDeserializeExactly()throws Exception{
        var json=JobDefinitionJson.mapper();var batch=batch();String encoded=json.writeValueAsString(batch);
        var tree=json.readTree(encoded);assertEquals("2026-06",tree.path("rows").get(0).path("month").textValue());
        assertEquals(batch,json.readValue(encoded,EquityStyleMonthlySource.Batch.class));
        assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(json.readValue(encoded,EquityStyleMonthlySource.Batch.class).rows().getFirst().hs300Ret1m()));
    }
    @Test void actualD103MaterializationResultHasCanonicalNestedMonthAndStableRoundTrip()throws Exception{
        var json=JobDefinitionJson.mapper();var result=new EquityStyleMonthlyJobService.MaterializationResult(
                new SyncJobRunner.Result("d103-json-unit",SyncRunState.VERIFIED,1,1,null),16,batch(),
                new com.zoutrankil.data.domain.EquityStyleMonthlyTargetSnapshot("d103-"+"b".repeat(64),7,"java_d103_equity_style_monthly_json~7","c".repeat(64),1L,1L,1,1,0,0,false,1,1L),null);
        String encoded=json.writeValueAsString(result);var tree=json.readTree(encoded);
        assertEquals("VERIFIED",tree.path("result").path("state").textValue());assertEquals("2026-06",tree.path("source").path("rows").get(0).path("month").textValue());
        assertEquals(result,json.readValue(encoded,EquityStyleMonthlyJobService.MaterializationResult.class));
    }
    private static EquityStyleMonthlySource.Batch batch(){
        var values=new LinkedHashMap<String,Object>();values.put("month",YearMonth.of(2026,6).atDay(1));
        EquityStyleMonthlyDataset.STORAGE_COLUMNS.subList(1,30).forEach(field->values.put(field,null));values.put("hs300_ret_1m",-0.0);values.put("value_ret_1m",0.0641);
        var row=new EquityStyleMonthlyMapper().fromValues(values);
        return new EquityStyleMonthlySource.Batch(new EquityStyleMonthlySource.Snapshot("index_monthly",5,"index_monthly~5",1,1,"a".repeat(64)),"d".repeat(64),16,List.of(row));
    }
}
