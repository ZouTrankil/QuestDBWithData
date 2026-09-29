package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.StockDetailInfo;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockDetailInfoMergeTest {
    private StockDetailInfo row(String code,String name,long second) {
        return new StockDetailInfo(code,Instant.ofEpochSecond(second),code.substring(0,6),name,
                null,null,"L",LocalDate.of(2000,1,1),null,null,null,null,null,"CNY",null,null,null,null);
    }
    @Test void incrementalInsertAndRevisionPreserveUnrequestedStockAndProduceExactCounts() {
        var untouched=row("000001.SZ","untouched",10);var old=row("600000.SH","old",10);
        var revision=row("600000.SH","new",11);var added=row("000003.SZ","added",11);
        var result=StockDetailInfoMerge.merge(List.of(old,untouched),List.of(revision,added));
        assertEquals(List.of(untouched,added,revision),result.rows());
        assertEquals(2,result.sourceRows());assertEquals(1,result.insertedRows());assertEquals(1,result.updatedRows());
        assertEquals(0,result.unchangedRows());assertTrue(result.requiresPublication());
    }
    @Test void repeatedObservationAndEmptySourceDoNotPublishOrDelete() {
        var original=row("000001.SZ","name",10);
        var repeated=StockDetailInfoMerge.merge(List.of(original),List.of(row("000001.SZ","name",11)));
        assertEquals(List.of(original),repeated.rows());assertFalse(repeated.requiresPublication());
        assertEquals(1,repeated.unchangedRows());
        var empty=StockDetailInfoMerge.merge(List.of(original),List.of());
        assertEquals(List.of(original),empty.rows());assertFalse(empty.requiresPublication());
        assertEquals(0,empty.sourceRows());
    }
    @Test void duplicateIdentitiesAreRejectedButLegacyClockDoesNotSuppressBusinessRevision() {
        var original=row("000001.SZ","name",10);
        assertThrows(IllegalArgumentException.class,()->StockDetailInfoMerge.merge(List.of(original,original),List.of()));
        assertThrows(IllegalArgumentException.class,()->StockDetailInfoMerge.merge(List.of(),List.of(original,original)));
        for(long second:List.of(9L,10L)) {
            var changed=row("000001.SZ","different",second);
            var result=StockDetailInfoMerge.merge(List.of(original),List.of(changed));
            assertEquals(List.of(changed),result.rows());
            assertEquals(1,result.updatedRows());
        }
    }
}
