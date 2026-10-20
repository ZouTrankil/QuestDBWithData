package com.zoutrankil.data.strategy.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrozenScalarValuesTest {
    @Test void scalarTypesNullsColumnOrderAndJsonRemainIdentical()throws Exception {
        var original=new LinkedHashMap<String,Object>();original.put("name","ETF");original.put("missing",null);
        original.put("price",2.71);original.put("rows",7935L);original.put("eligible",true);
        var frozen=FrozenScalarValues.copyOf(original);
        assertEquals(original,frozen);assertEquals(original.hashCode(),frozen.hashCode());
        assertEquals(new ArrayList<>(original.keySet()),new ArrayList<>(frozen.keySet()));
        assertTrue(frozen.containsKey("missing"));assertNull(frozen.get("missing"));assertFalse(frozen.containsKey("absent"));
        var json=new ObjectMapper();assertEquals(json.writeValueAsString(original),json.writeValueAsString(frozen));
        assertSame(frozen,FrozenScalarValues.copyOf(frozen));
    }
    @Test void sharedLayoutsDoNotPermitSourceArraysOrMapsToMutateRows() {
        var columns=new ArrayList<>(List.of("a","b"));var layout=FrozenScalarValues.layout(columns);
        Object[] values={1.0,null};var first=FrozenScalarValues.row(layout,values);values[0]=2.0;columns.set(0,"changed");
        var second=FrozenScalarValues.row(layout,new Object[]{3.0,"text"});
        assertEquals(1.0,first.get("a"));assertEquals(3.0,second.get("a"));assertNull(first.get("b"));
        assertThrows(UnsupportedOperationException.class,()->first.put("a",4.0));
        assertThrows(UnsupportedOperationException.class,()->first.entrySet().iterator().next().setValue(4.0));
        var source=new HashMap<String,Object>();source.put("x",1L);var copied=FrozenScalarValues.copyOf(source);source.put("x",2L);
        assertEquals(1L,copied.get("x"));
    }
    @Test void ambiguousColumnsAndWrongWidthsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->FrozenScalarValues.layout(List.of("price","price")));
        assertThrows(IllegalArgumentException.class,()->FrozenScalarValues.row(FrozenScalarValues.layout(List.of("price")),new Object[0]));
    }
    @Test void scoreProjectionSharesOnlyImmutableCellsAndPreservesNullsAndJson()throws Exception {
        var source=new LinkedHashMap<String,Object>();source.put("trade_date","2026-10-16");source.put("ts_code","510300.SH");source.put("price",2.71);source.put("missing",null);
        var projected=FrozenScalarValues.excluding(source,Set.of("trade_date","ts_code","absent"));
        source.put("price",9.);
        var expected=new LinkedHashMap<String,Object>();expected.put("price",2.71);expected.put("missing",null);
        assertEquals(expected,projected);assertEquals(2,projected.size());assertFalse(projected.containsKey("ts_code"));
        assertTrue(projected.containsKey("missing"));assertNull(projected.get("missing"));
        assertEquals(new ObjectMapper().writeValueAsString(expected),new ObjectMapper().writeValueAsString(projected));
        assertSame(projected,new ScoreRow(java.time.LocalDate.of(2026,10,16),"510300.SH",projected).values());
        assertThrows(UnsupportedOperationException.class,()->projected.put("price",3.));
    }
    @Test void mergedPublicationValuesPreserveOrderedOverwriteNullAndSnapshotSemantics()throws Exception {
        var source=new LinkedHashMap<String,Object>();source.put("price",2.71);source.put("trade_date","old");source.put("missing",null);
        var additions=new LinkedHashMap<String,Object>();additions.put("trade_date","2026-10-16");additions.put("ts_code","510300.SH");additions.put("price",null);
        var expected=new LinkedHashMap<String,Object>(source);expected.putAll(additions);
        var actual=FrozenScalarValues.merging(source,additions);
        source.put("missing",8.0);additions.put("ts_code","changed");
        assertEquals(expected,actual);assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.keySet()));
        var observed=new LinkedHashMap<String,Object>();actual.forEach(observed::put);assertEquals(expected,observed);
        var json=new ObjectMapper();assertEquals(json.writeValueAsString(expected),json.writeValueAsString(actual));
        assertTrue(actual.containsKey("price"));assertNull(actual.get("price"));
        assertThrows(UnsupportedOperationException.class,()->actual.put("price",3.));
    }
}
