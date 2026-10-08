package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractsTest {
    static RunRequest request(String id) {
        var date=LocalDate.of(2026,9,29);
        return new RunRequest(id,"post_close",date,date,date,"v1","0",null,null,"source-v1","cal-v1",
                "Asia/Shanghai",Instant.parse("2026-09-29T10:30:00Z"),Instant.parse("2026-09-29T10:30:01Z"));
    }
    static CompletionEvidence evidence(RunRequest r,String stage) {
        return new CompletionEvidence(1,"test-only",r.instanceId(),stage,"b1",r.logicalDate(),r.rangeStart(),r.rangeEnd(),
            r.rangeStart(),r.rangeEnd(),r.definitionVersion(),"source-v1",r.inputFingerprint(),1,1,
            true,true,true,true,true,false,Instant.parse("2026-09-29T10:30:00Z"),BusinessState.VERIFIED,"fixture://test",null);
    }
    @Test void clockAndRequestIdDoNotCreateNewInstance() {
        var r=request("one");
        var retry=new RunRequest("two",r.job(),r.logicalDate(),r.rangeStart(),r.rangeEnd(),r.definitionVersion(),r.revision(),
                null,null,r.inputFingerprint(),r.calendarVersion(),r.zone(),r.scheduledAt(),Instant.parse("2026-09-30T01:00:00Z"));
        assertEquals(r.instanceId(),retry.instanceId());
        assertEquals(r,Json.read(Json.write(r),RunRequest.class));
    }
    @Test void revisionRequiresReasonAndPredecessor() {
        var r=request("one");
        assertThrows(IllegalArgumentException.class,() -> new RunRequest("two",r.job(),r.logicalDate(),r.rangeStart(),r.rangeEnd(),
                "v1","1",null,null,"source-v2","cal-v1",r.zone(),r.scheduledAt(),r.triggeredAt()));
    }
    @Test void sourceEntityScopeSeparatesBusinessInstancesButLateInputKeepsItsIdentity() {
        var date=LocalDate.of(2026,9,28);var at=Instant.parse("2026-09-29T10:30:00Z");
        var scope=RunRequest.hash("index_daily_market","core-index-v1","000300.SH\n000905.SH");
        var otherScope=RunRequest.hash("index_daily_market","core-index-v1","000300.SH");
        var first=new RunRequest("first","source_index_daily_market",date,date,date,"index_daily_market-v1","0",null,null,
                "input-a","calendar-v1","Asia/Shanghai",at,at,scope);
        var late=new RunRequest("late","source_index_daily_market",date,date,date,"index_daily_market-v1","0",null,null,
                "input-b","calendar-v1","Asia/Shanghai",at,at,scope);
        var narrower=late.withScopeIdentity(otherScope);
        assertEquals(first.instanceId(),late.instanceId());
        assertNotEquals(first.instanceId(),narrower.instanceId());
        assertEquals(first,Json.read(Json.write(first),RunRequest.class));
    }
    @Test void unknownOrRunningChildIsNeverReadyAndStaleEvidenceRejected() {
        var r=request("one");
        assertEquals(BusinessState.BLOCKED,ExternalComputation.validate(r,"FactorReady",ExternalComputation.disconnected().observe(r,"FactorReady")));
        assertEquals(BusinessState.IN_DOUBT,ExternalComputation.validate(r,"FactorReady",new ExternalComputation.Observation("child",false,null,null,"lost")));
        assertEquals(BusinessState.RUNNING,ExternalComputation.validate(r,"FactorReady",new ExternalComputation.Observation("child",true,null,evidence(r,"FactorReady"),null)));
        assertEquals(BusinessState.BLOCKED,ExternalComputation.validate(r,"FactorReady",new ExternalComputation.Observation("child",false,null,evidence(r,"StrategyPublished"),null)));
    }
    @Test void cannotCertifyHttpEmptyAsReady() {
        var r=request("one");
        assertThrows(IllegalArgumentException.class,() -> new CompletionEvidence(1,"source",r.instanceId(),"DataReady",null,
                r.logicalDate(),r.rangeStart(),r.rangeEnd(),r.rangeStart(),r.rangeEnd(),"v1","v1",r.inputFingerprint(),
                0,0,true,true,true,true,false,true,r.triggeredAt(),BusinessState.VERIFIED_EMPTY,"fixture",null));
    }
    @Test void calendarMidnightHolidayAndMonthEndAreExplicit() {
        var calendar=new TradingCalendar("v1",LocalDate.of(2026,9,1),LocalDate.of(2026,10,31),
                new TreeSet<>(List.of(LocalDate.of(2026,9,28),LocalDate.of(2026,9,29),LocalDate.of(2026,9,30),LocalDate.of(2026,10,8))));
        assertEquals(LocalDate.of(2026,9,29),RecoveryPolicy.nightLogicalDate(Instant.parse("2026-09-29T16:05:00Z"),calendar));
        assertTrue(calendar.isMonthEnd(LocalDate.of(2026,9,30)));
        assertFalse(calendar.isOpen(LocalDate.of(2026,10,1)));
        assertThrows(IllegalArgumentException.class,() -> calendar.isOpen(LocalDate.of(2027,1,1)));
        assertTrue(RecoveryPolicy.mayProbe(request("one"),Instant.parse("2026-09-29T15:55:00Z"),20,null,calendar));
        assertFalse(RecoveryPolicy.mayProbe(request("one"),Instant.parse("2026-09-29T15:59:00Z"),20,null,calendar));
    }
    @Test void watermarkDoesNotJumpOverMissingPartition() {
        var r=request("one"); var dates=List.of(r.logicalDate().minusDays(1),r.logicalDate());
        assertNull(RecoveryPolicy.contiguousWatermark(dates,Map.of(r.logicalDate(),evidence(r,"DataReady")),"v1",r.inputFingerprint()));
    }
    @Test void monthlyBackfillPartitionsEveryCalendarMonthWithoutTradingDayFiltering() {
        assertEquals(List.of(LocalDate.of(2026,5,1),LocalDate.of(2026,6,1),LocalDate.of(2026,7,1)),
                RecoveryPolicy.monthlyBackfill(LocalDate.of(2026,5,1),LocalDate.of(2026,7,1),3));
        assertThrows(IllegalArgumentException.class,() -> RecoveryPolicy.monthlyBackfill(LocalDate.of(2026,5,2),LocalDate.of(2026,7,1),3));
        assertThrows(IllegalArgumentException.class,() -> RecoveryPolicy.monthlyBackfill(LocalDate.of(2026,5,1),LocalDate.of(2026,7,1),2));
        var history=RecoveryPolicy.monthlyBackfill(LocalDate.of(1990,1,1),LocalDate.of(2026,7,1),500);
        assertEquals(439,history.size());assertEquals(LocalDate.of(2026,7,1),history.getLast());
    }
    @Test void dedicatedMetadataDatabaseRequired() {
        assertThrows(IllegalArgumentException.class,() -> RuntimeConfiguration.validateMetadataUrl("jdbc:postgresql://100.97.201.8:8812/qdb"));
        assertThrows(IllegalArgumentException.class,() -> RuntimeConfiguration.validateMetadataUrl("jdbc:sqlite::memory:"));
        RuntimeConfiguration.validateMetadataUrl("jdbc:sqlite:./var/jdb-test.sqlite");
    }
}
