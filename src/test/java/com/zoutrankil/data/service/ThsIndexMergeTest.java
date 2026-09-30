package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.ThsIndex;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThsIndexMergeTest {
    private ThsIndex row(String code,int count,Instant observed) {
        return new ThsIndex(code,"index",count,"A",LocalDate.of(2006,12,29),"BB",observed);
    }
    @Test void partialRefreshChangesContentWithoutDeletingOthersOrUsingClockAsRevisionOrder() {
        var original=row("700001.TI",10,Instant.parse("2026-09-29T00:00:00Z"));
        var retained=row("700002.TI",20,original.observedAt());
        var unchanged=ThsIndexMerge.merge(List.of(original,retained),List.of(row(original.tsCode(),10,Instant.EPOCH)),
                new ThsIndexSource.Scope(original.tsCode(),null,null));
        assertFalse(unchanged.requiresWrite());assertEquals(original,unchanged.rows().getFirst());assertEquals(1,unchanged.retainedAbsent());
        var revised=ThsIndexMerge.merge(List.of(original,retained),List.of(row(original.tsCode(),11,Instant.EPOCH)),
                new ThsIndexSource.Scope(original.tsCode(),null,null));
        assertEquals(1,revised.revised());assertEquals(Instant.EPOCH,revised.changedRows().getFirst().observedAt());
        assertTrue(revised.rows().contains(retained));
        var extended=ThsIndexMerge.merge(revised.rows(),List.of(row("700003.TI",30,Instant.EPOCH)),new ThsIndexSource.Scope("700003.TI",null,null));
        assertEquals(1,extended.inserted());assertEquals(3,extended.rows().size());
    }
    @Test void emptyCompleteSourceCannotPublishAndDuplicateOutsideScopeOrLargeFullShrinkCannotComplete() {
        var a=row("700001.TI",10,Instant.EPOCH);var b=row("700002.TI",20,Instant.EPOCH);
        assertThrows(IllegalStateException.class,()->ThsIndexMerge.merge(List.of(a,b),List.of(),ThsIndexSource.Scope.all()));
        var emptyPartial=ThsIndexMerge.merge(List.of(a,b),List.of(),new ThsIndexSource.Scope(a.tsCode(),null,null));
        assertEquals(List.of(a,b),emptyPartial.rows());assertFalse(emptyPartial.requiresWrite());
        assertThrows(IllegalArgumentException.class,()->ThsIndexMerge.merge(List.of(a,a),List.of(),ThsIndexSource.Scope.all()));
        assertThrows(IllegalArgumentException.class,()->ThsIndexMerge.merge(List.of(a),List.of(b),new ThsIndexSource.Scope(a.tsCode(),null,null)));
        assertThrows(IllegalStateException.class,()->ThsIndexMerge.merge(List.of(a,b),List.of(a),ThsIndexSource.Scope.all()));
    }
    @Test void boundedCompleteShrinkRetainsAbsentCodeAndPreservesUnchangedObservation() {
        var older=Instant.parse("2026-09-01T00:00:00Z");
        var existing=new ArrayList<ThsIndex>();
        for(int i=0;i<100;i++) existing.add(row("%06d.TI".formatted(i),i,older));
        var incoming=existing.subList(0,99).stream().map(r->row(r.tsCode(),r.memberCount(),Instant.EPOCH)).toList();
        var merged=ThsIndexMerge.merge(existing,incoming,ThsIndexSource.Scope.all());
        assertEquals(0,merged.removed());assertEquals(1,merged.retainedAbsent());assertFalse(merged.requiresWrite());
        assertEquals(existing,merged.rows());
    }
}
