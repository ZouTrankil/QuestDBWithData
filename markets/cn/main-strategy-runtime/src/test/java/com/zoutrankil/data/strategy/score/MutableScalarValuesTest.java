package com.zoutrankil.data.strategy.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MutableScalarValuesTest {
    @Test void completeWideRowsMatchLinkedMapsThroughReplacementAppendRemovalAndNullableValues()throws Exception {
        var expected=new LinkedHashMap<String,Object>();
        for(int i=0;i<200;i++)expected.put("factor_"+i,i%7==0?null:i+.125);
        expected.put("name","中文ETF");expected.put("flag",true);expected.put("missing",Double.NaN);
        var actual=new MutableScalarValues(FrozenScalarValues.copyOf(expected));
        for(int i=0;i<80;i++)assertEquals(expected.put("derived_"+i,i*.03125),actual.put("derived_"+i,i*.03125));
        assertEquals(expected.put("factor_1",null),actual.put("factor_1",null));
        assertEquals(expected.remove("factor_8"),actual.remove("factor_8"));
        expected.put("factor_8",-0.0);actual.put("factor_8",-0.0);
        assertEquals(expected,actual);assertEquals(expected.hashCode(),actual.hashCode());
        assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.keySet()));
        assertTrue(actual.containsKey("factor_1"));assertNull(actual.get("factor_1"));assertFalse(actual.containsKey("absent"));
        var json=new ObjectMapper();assertArrayEquals(json.writeValueAsBytes(expected),json.writeValueAsBytes(actual));
    }
    @Test void mutableRowsAndFrozenSnapshotsCannotChangeOneAnothersCells() {
        var day=LocalDate.of(2026,10,16);var source=new ScoreRow(day,"510300.SH",Map.of("close",2.5,"amount",1000.));
        var row=new ScoreMath.MutableRow(source);row.put("close",3.);row.put("extra",null);
        var first=row.freeze();row.put("close",4.);row.values.remove("amount");
        var second=row.freeze();
        assertEquals(2.5,source.number("close"));assertEquals(3.,first.number("close"));assertEquals(4.,second.number("close"));
        assertTrue(first.values().containsKey("amount"));assertFalse(second.values().containsKey("amount"));assertTrue(first.values().containsKey("extra"));
        assertThrows(UnsupportedOperationException.class,()->first.values().put("close",9.));
    }
    @Test void entryViewsDefaultMapOperationsAndIndependentRowsPreserveTheirContracts() {
        var source=new LinkedHashMap<String,Object>();source.put("a",null);source.put("b",2.);
        var first=new MutableScalarValues(source);var second=new MutableScalarValues(source);
        first.putIfAbsent("a",1.);first.compute("b",(key,value)->3.);first.replaceAll((key,value)->((Number)value).doubleValue()+1);
        var iterator=first.entrySet().iterator();assertEquals("a",iterator.next().getKey());iterator.remove();
        var entry=iterator.next();assertEquals("b",entry.getKey());assertEquals(4.,entry.setValue(5.));
        assertEquals(Map.of("b",5.),first);assertEquals(source,second);
        source.clear();assertEquals(2,second.size());
        first.clear();first.put("c",null);assertEquals(List.of("c"),new ArrayList<>(first.keySet()));
        var stale=first.entrySet().iterator();first.put("d",1);assertThrows(ConcurrentModificationException.class,stale::next);
    }
}
