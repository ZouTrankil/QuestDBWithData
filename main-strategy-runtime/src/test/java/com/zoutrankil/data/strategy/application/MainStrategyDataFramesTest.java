package com.zoutrankil.data.strategy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.score.ScoreRow;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MainStrategyDataFramesTest {
    @Test void completeScoreFramesRetainExactOrderedValuesAndReplaceOnlyIdentityValues()throws Exception {
        var values=new LinkedHashMap<String,Object>();values.put("score",.625);values.put("trade_date","old");
        values.put("nullable",null);values.put("flag",false);values.put("ts_code","old");values.put("not_finite",Double.NaN);
        var day=LocalDate.of(2026,10,16);var row=new ScoreRow(day,"510300.SH",values);
        var expected=new LinkedHashMap<String,Object>(values);expected.put("trade_date",day);expected.put("ts_code",row.symbol());
        var frame=MainStrategyDataService.frames(List.of(row),true).getFirst();
        assertEquals(expected,frame);assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(frame.keySet()));
        assertEquals(new ObjectMapper().findAndRegisterModules().writeValueAsString(expected),new ObjectMapper().findAndRegisterModules().writeValueAsString(frame));
        assertEquals(row.values().size(),frame.size());assertEquals("old",row.values().get("trade_date"));
    }
    @Test void coverageFramesRetainEveryFieldAndAppendOnlyTheSignalDate() {
        var day=LocalDate.of(2026,10,16);var row=new ScoreRow(day,"MARKET",Map.of("count",243550L,"hit_rate",.9));
        var expected=new LinkedHashMap<String,Object>(row.values());expected.put("trade_date",day);
        var actual=MainStrategyDataService.frames(List.of(row),false).getFirst();
        assertEquals(expected,actual);assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.keySet()));assertFalse(actual.containsKey("ts_code"));
    }
}
