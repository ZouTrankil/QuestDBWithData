package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.IndexCatalogEntry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexCatalogMergeTest {
    private IndexCatalogEntry row(String code,String name,Instant observed) {
        return new IndexCatalogEntry(code,name,"Full",LocalDate.of(2000,1,1),1000.0,"series",300.0,4500.0,-6.95,
                "stock",null,"CNY","no","yes","IOSCO","size",LocalDate.of(2005,1,1),observed);
    }
    @Test void unchangedTimestampIsPreservedAndMissingInputNeverDeletes() {
        var original=row("000300","A",Instant.EPOCH);var retained=row("H00999","B",Instant.EPOCH);
        var refreshed=row("000300","A",Instant.EPOCH.plusSeconds(10));
        var result=IndexCatalogMerge.merge(List.of(original,retained),List.of(refreshed));
        assertFalse(result.requiresWrite());assertEquals(List.of(original,retained),result.rows());
        assertEquals(1,result.unchanged());assertEquals(1,result.retainedAbsent());
        var empty=IndexCatalogMerge.merge(result.rows(),List.of());assertEquals(result.rows(),empty.rows());
        assertFalse(empty.requiresWrite());
    }
    @Test void contentRevisionUsesExplicitFileRatherThanOrderingObservationClocks() {
        var original=row("000300","A",Instant.EPOCH.plusSeconds(100));var changed=row("000300","B",Instant.EPOCH);
        var result=IndexCatalogMerge.merge(List.of(original),List.of(changed,row("H00999","C",Instant.EPOCH)));
        assertEquals(1,result.inserted());assertEquals(1,result.revised());assertEquals(changed,result.rows().getFirst());
        assertThrows(IllegalArgumentException.class,()->IndexCatalogMerge.merge(List.of(original,original),List.of()));
        assertThrows(IllegalArgumentException.class,()->IndexCatalogMerge.merge(List.of(),List.of(original,changed)));
    }
}
