package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.IndexMembership;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipMergeTest {
    private IndexMembership row(LocalDate start,LocalDate end,String flag,Instant observed,String code,Double weight) {
        return new IndexMembership("801011.SI","000663.SZ",observed,"林业Ⅱ",code,"永安林业",start,end,flag,weight,"L2","农林牧渔","林业Ⅱ","林业Ⅲ");
    }
    @Test void exitUpdatesOnePeriodWhileReentryAddsAnotherAndPreservesUnsupportedFields() {
        var first=LocalDate.of(1996,12,6);var old=row(first,null,"Y",Instant.parse("2026-09-29T00:00:00Z"),"legacy",2.5);
        var exit=row(first,LocalDate.of(2016,6,30),"N",Instant.EPOCH,null,null);
        var reentry=row(LocalDate.of(2022,7,29),null,"Y",Instant.EPOCH,null,null);
        var merged=IndexMembershipMerge.mergeSource(List.of(old),List.of(exit,reentry),"801011.SI");
        assertEquals(1,merged.revised());assertEquals(1,merged.inserted());assertEquals(2,merged.rows().size());
        assertEquals("legacy",merged.rows().getFirst().constituentCode());assertEquals(2.5,merged.rows().getFirst().weight());
        assertEquals(exit.membershipEndDate(),merged.rows().getFirst().membershipEndDate());
        var repeat=IndexMembershipMerge.mergeSource(merged.rows(),List.of(exit,reentry),"801011.SI");
        assertFalse(repeat.requiresWrite());assertEquals(2,repeat.unchanged());assertEquals(merged.rows(),repeat.rows());
        var absent=IndexMembershipMerge.mergeSource(merged.rows(),List.of(reentry),"801011.SI");
        assertFalse(absent.requiresWrite());assertEquals(1,absent.retainedAbsent());assertEquals(merged.rows(),absent.rows());
    }
    @Test void duplicatePeriodsScopeDriftAndInventedSourceFieldsAreRejected() {
        var row=row(LocalDate.of(2022,7,29),null,"Y",Instant.EPOCH,null,null);
        assertThrows(IllegalArgumentException.class,()->IndexMembershipMerge.mergeSource(List.of(),List.of(row,row),"801011.SI"));
        assertThrows(IllegalArgumentException.class,()->IndexMembershipMerge.mergeSource(List.of(),List.of(row),"801012.SI"));
        var forged=row(row.membershipStartDate(),null,"Y",Instant.EPOCH,null,1.0);
        assertThrows(IllegalArgumentException.class,()->IndexMembershipMerge.mergeSource(List.of(),List.of(forged),"801011.SI"));
        var empty=IndexMembershipMerge.mergeSource(List.of(row),List.of(),"801011.SI");
        assertFalse(empty.requiresWrite());assertEquals(List.of(row),empty.rows());
    }
    @Test void aSingleL2SliceDoesNotCountOtherIndustriesAsAbsent() {
        var row=row(LocalDate.of(2022,7,29),null,"Y",Instant.EPOCH,null,null);
        var other=new IndexMembership("801012.SI",row.tsCode(),row.observedAt(),"农产品加工",
                null,row.constituentName(),row.membershipStartDate(),null,"Y",null,"L2",
                row.l1Name(),"农产品加工",row.l3Name());
        var merged=IndexMembershipMerge.mergeSource(List.of(row,other),List.of(row),"801011.SI");
        assertEquals(0,merged.retainedAbsent());
        assertEquals(2,merged.rows().size());
    }
    @Test void preparedRowsOwnObservationAndLegacyFieldsWhileSourceDoesNot() {
        var start=LocalDate.of(2022,7,29);
        var old=row(start,null,"Y",Instant.parse("2026-09-29T00:00:00Z"),"legacy",2.5);
        var prepared=row(start,null,"Y",Instant.parse("2026-09-29T00:01:00Z"),"new-code",3.5);
        var revised=IndexMembershipMerge.mergePrepared(List.of(old),List.of(prepared),"801011.SI");
        assertEquals(1,revised.revised());assertEquals(List.of(prepared),revised.rows());
        assertFalse(IndexMembershipMerge.mergePrepared(revised.rows(),List.of(prepared),"801011.SI").requiresWrite());
        var source=row(start,null,"Y",Instant.parse("2026-09-29T00:02:00Z"),null,null);
        var sourceMerged=IndexMembershipMerge.mergeSource(List.of(old),List.of(source),"801011.SI");
        assertFalse(sourceMerged.requiresWrite());assertEquals(List.of(old),sourceMerged.rows());
    }
}
