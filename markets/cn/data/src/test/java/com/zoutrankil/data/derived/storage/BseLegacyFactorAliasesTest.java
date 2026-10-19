package com.zoutrankil.data.derived.storage;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.derived.storage.BseLegacyFactorAliases.*;

class BseLegacyFactorAliasesTest {
    private static final LocalDate DAY=LocalDate.of(2025,7,1);
    private static final Key OLD=new Key(DAY,"839729.BJ"),CURRENT=new Key(DAY,"920729.BJ");
    private static final Values VALUES=new Values(12.3,12.1,456.7);
    private static Factor row(Key key){return new Factor(key,VALUES);}

    @Test void onlyExactAliasOutsideCanonicalDailyUniverseIsExcluded() {
        var scope=prepare(List.of(row(OLD),row(CURRENT)),Set.of(CURRENT));
        assertTrue(scope.excludes(DAY,OLD.symbol()));assertFalse(scope.excludes(DAY,CURRENT.symbol()));
        assertFalse(scope.excludes(DAY.plusDays(1),OLD.symbol()));
        assertEquals(1,scope.evidence().get("verifiedDuplicateAliasRows"));
        assertEquals(true,scope.evidence().get("rawSourceRowsPreserved"));
        assertTrue(identity().get("mappingSha256").matches("[a-f0-9]{64}"));
    }
    @Test void oldCodeRemainsWhenItIsTheAuthoritativeHistoricalKey() {
        assertTrue(prepare(List.of(row(OLD)),Set.of(OLD)).excluded().isEmpty());
    }
    @Test void unmappedOrphanRemainsForTheExistingCompletenessGuard() {
        var unknown=new Key(DAY,"430000.BJ");
        assertFalse(prepare(List.of(row(unknown)),Set.of()).excludes(DAY,unknown.symbol()));
    }
    @Test void mappingCannotSubstituteForMissingCanonicalFactorValues() {
        assertThrows(IllegalStateException.class,()->prepare(List.of(row(OLD)),Set.of(CURRENT)));
    }
    @Test void pairedMissingValueRemainsMissingInTheRetainedCanonicalRow() {
        var missing=new Values(12.3,Double.NaN,456.7);
        var scope=prepare(List.of(new Factor(OLD,missing),new Factor(CURRENT,missing)),Set.of(CURRENT));
        assertTrue(scope.excludes(DAY,OLD.symbol()));assertFalse(scope.excludes(DAY,CURRENT.symbol()));
        assertTrue(Double.isNaN(missing.preClose()));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void anyConsumedValueDifferenceOrMissingValuePreventsExclusion(int field) {
        var different=switch(field){
            case 0->new Values(12.4,12.1,456.7);
            case 1->new Values(12.3,12.2,456.7);
            case 2->new Values(12.3,12.1,456.8);
            default->new Values(Double.NaN,12.1,456.7);
        };
        assertThrows(IllegalStateException.class,()->prepare(List.of(row(OLD),new Factor(CURRENT,different)),Set.of(CURRENT)));
    }
    @Test void ambiguousAuthoritativeIdentityOrDuplicateRawKeysFailClosed() {
        assertThrows(IllegalStateException.class,()->prepare(List.of(row(OLD),row(CURRENT)),Set.of(OLD,CURRENT)));
        assertThrows(IllegalStateException.class,()->prepare(List.of(row(OLD),row(OLD)),Set.of(CURRENT)));
    }
}
