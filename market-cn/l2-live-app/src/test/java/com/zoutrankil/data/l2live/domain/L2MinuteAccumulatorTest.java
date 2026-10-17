package com.zoutrankil.data.l2live.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2MinuteAccumulatorTest {
    private final AliyunL2WireParser parser=new AliyunL2WireParser();
    private final Clock clock=Clock.fixed(Instant.parse("2026-05-27T08:00:00Z"),ZoneOffset.UTC);
    private L2MinuteAccumulator accumulator(){return new L2MinuteAccumulator(Duration.ofSeconds(90),6,clock);}
    private L2CleanEvent event(long sequence,String channel,String payload){return parser.parse(AliyunL2WireParserTest.capture(sequence,channel+"/510500",payload,"2026-05-26T01:35:00Z"));}
    private L2CleanEvent trade(long sequence,String time,long price){return event(sequence,"trans","510500.SH,B,"+time+",999,"+price+",1000,0,1");}
    @Test void amountAndBookValuesArePartialDataWithNoTradingPolicy() {
        var a=accumulator();a.accept(event(1,"market",AliyunL2WireParserTest.market("93000000",15450,2000,1000)));
        var s=a.accept(trade(2,"93010000",15460)).orElseThrow();
        assertEquals(Instant.parse("2026-05-26T01:30:00Z"),s.minuteStart());assertEquals(clock.instant(),s.availableAt());
        assertEquals(new BigDecimal("1546.0000"),s.metrics().get("amount_1m"));assertEquals(1000L,s.metrics().get("volume_1m"));assertEquals(1L,s.metrics().get("trade_count_1m"));
        assertEquals(new BigDecimal("1.5450"),s.metrics().get("bid_price_1"));assertEquals(2009L,s.metrics().get("bid_vol_10"));
        assertEquals(true,s.metrics().get("partial"));assertEquals(L2MinuteAccumulator.METRIC_TYPES.keySet(),s.metrics().keySet());
        assertFalse(s.metrics().containsKey("micro_action"));assertFalse(s.metrics().containsKey("buy_support_score"));
    }
    @Test void outOfOrderPricesCannotRegressCloseAndOldSnapshotCannotRegressLatestBook() {
        var a=accumulator();a.accept(trade(1,"93020000",15500));
        var state=a.accept(trade(2,"93010000",15400)).orElseThrow();
        assertEquals(new BigDecimal("1.5400"),state.metrics().get("open_1m"));assertEquals(new BigDecimal("1.5500"),state.metrics().get("close_1m"));
        a.accept(event(3,"market",AliyunL2WireParserTest.market("93030000",15500,5000,1000)));
        state=a.accept(event(4,"market",AliyunL2WireParserTest.market("93005000",15400,2000,1000))).orElseThrow();
        assertEquals(5000L,state.metrics().get("bid_vol_1"));assertEquals("2026-05-26T01:30:30Z",state.metrics().get("book_snapshot_ts"));
        assertEquals(295000.0,state.metrics().get("data_delay_ms"));
    }
    @Test void orderBeforeAnAlreadyObservedFutureSnapshotDoesNotUseFutureBook() {
        var a=accumulator();a.accept(event(1,"market",AliyunL2WireParserTest.market("93030000",15450,2000,1000)));
        var state=a.accept(event(2,"order","510500.SH,20260526,93010000,0,1,A,B,15450,2000")).orElseThrow();
        assertEquals(BigDecimal.ZERO,state.metrics().get("near_bid_add_amount"));assertTrue(state.metrics().get("quality_flags").toString().contains("missing_causal_book"));
    }
    @Test void identicalPayloadsWithDistinctCapturesAreNotGuessedBusinessDuplicates() {
        var a=accumulator();a.accept(trade(1,"93010000",15460));var state=a.accept(trade(2,"93010000",15460)).orElseThrow();
        assertEquals(2L,state.metrics().get("trade_count_1m"));assertEquals(new BigDecimal("3092.0000"),state.metrics().get("amount_1m"));
        assertEquals(true,state.metrics().get("sequence_scope_unverified"));assertFalse(a.diagnosticCounters().containsKey("sequence_gaps"));
    }
    @Test void lateOldMinuteNeverEmitsAReplacementLatestMinute() {
        var a=accumulator();a.accept(trade(1,"93110000",15460));
        assertTrue(a.accept(trade(2,"93050000",15450)).isEmpty());
        assertEquals(1L,a.diagnosticCounters().get("older_minute_not_emitted"));
        assertTrue(a.accept(trade(3,"92800000",15450)).isEmpty());assertEquals(1L,a.diagnosticCounters().get("beyond_lateness_rejected"));
    }
    @Test void booksAndHistoryResetAcrossDaysAndRetentionIsBounded() {
        var a=new L2MinuteAccumulator(Duration.ZERO,3,clock);
        for(int minute=30;minute<40;minute++)a.accept(trade(minute,"9"+minute+"00000",15450));
        Map<?,?> symbol=(Map<?,?>)((List<?>)a.snapshot().get("symbols")).getFirst();assertEquals(3,((List<?>)symbol.get("buckets")).size());
        assertTrue(a.diagnosticCounters().get("minute_buckets_evicted")>=7);
        var next=parser.parse(AliyunL2WireParserTest.capture(100,"trans/510500","510500.SH,B,93000000,1,15460,1000,0,1","2026-05-27T01:30:01Z"));
        var state=a.accept(next).orElseThrow();assertEquals(1000L,state.metrics().get("volume_1m"));assertNull(state.metrics().get("ret_1m"));assertNull(state.metrics().get("bid_price_1"));
        assertEquals(1L,a.diagnosticCounters().get("daily_resets"));assertTrue(a.accept(trade(101,"93900000",15450)).isEmpty());
    }
    @Test void checkpointSurvivesGenericJsonStyleMapsWithoutDecimalRoundingAndDetachedMutation()throws Exception {
        var original=accumulator();original.accept(event(1,"market",AliyunL2WireParserTest.market("93000000",15450,2000,1000)));original.accept(trade(2,"93010000",15460));
        Map<String,Object> saved=original.snapshot();var json=new ObjectMapper();
        Map<String,Object> decoded=json.readValue(json.writeValueAsString(saved),new TypeReference<Map<String,Object>>(){});
        var restored=accumulator();restored.restore(decoded);
        var next=trade(3,"93020000",15470);assertEquals(original.accept(next),restored.accept(next));assertEquals(original.snapshot(),restored.snapshot());
        assertThrows(UnsupportedOperationException.class,()->saved.put("parser_version","corrupt"));
        Map<?,?> bucket=(Map<?,?>)((List<?>)((Map<?,?>)((List<?>)saved.get("symbols")).getFirst()).get("buckets")).getFirst();assertEquals("1546.0000",bucket.get("amount"));
        var changed=new LinkedHashMap<>(saved);changed.put("retained_minutes",99);var before=restored.snapshot();
        assertThrows(IllegalArgumentException.class,()->restored.restore(changed));assertEquals(before,restored.snapshot());
    }
    @Test void checkpointRejectsOversizedOrMalformedStateBeforeReplacingTheOldState() {
        var a=accumulator();a.accept(trade(1,"93010000",15460));var saved=a.snapshot();var changed=new LinkedHashMap<>(saved);
        changed.put("symbols",new ArrayList<>(java.util.Collections.nCopies(257,Map.of())));assertThrows(IllegalArgumentException.class,()->a.restore(changed));assertEquals(saved,a.snapshot());
        var bad=new LinkedHashMap<>(saved);bad.put("allowed_lateness_nanos",new BigDecimal("1.5"));
        assertThrows(IllegalArgumentException.class,()->a.restore(bad));assertEquals(saved,a.snapshot());
    }
    @Test void availableAtCannotBeInventedFromAReceiveInstantAheadOfTheProcessingClock() {
        var a=new L2MinuteAccumulator(Duration.ZERO,3,Clock.fixed(Instant.parse("2026-05-26T00:00:00Z"),ZoneOffset.UTC));
        assertThrows(IllegalStateException.class,()->a.accept(trade(1,"93010000",15460)));assertTrue(((List<?>)a.snapshot().get("symbols")).isEmpty());
    }
    @Test void futureClockToleranceRetainsRealAvailabilityWithoutPoisoningTheWatermark() {
        var fixed=Clock.fixed(Instant.parse("2026-05-26T01:30:01Z"),ZoneOffset.UTC);
        var a=new L2MinuteAccumulator(Duration.ofSeconds(30),3,fixed);
        var boundary=parser.parse(AliyunL2WireParserTest.capture(201,"trans/510500","510500.SH,B,93002000,1,15460,1000,0,1","2026-05-26T01:30:01Z"));
        var state=a.accept(boundary).orElseThrow();assertEquals(fixed.instant(),state.availableAt());
        assertTrue(state.metrics().get("quality_flags").toString().contains("exchange_clock_ahead_of_receive"));
        var tooFar=parser.parse(AliyunL2WireParserTest.capture(202,"trans/510500","510500.SH,B,93002001,2,15470,1000,0,1","2026-05-26T01:30:01Z"));
        assertTrue(a.accept(tooFar).isEmpty());assertEquals(1L,a.diagnosticCounters().get("future_event_time_rejected"));
        var after=parser.parse(AliyunL2WireParserTest.capture(203,"trans/510500","510500.SH,B,93001500,3,15450,1000,0,1","2026-05-26T01:30:01Z"));
        state=a.accept(after).orElseThrow();assertEquals(2L,state.metrics().get("trade_count_1m"));assertEquals(fixed.instant(),state.availableAt());assertEquals(-500.0,state.metrics().get("data_delay_ms"));
    }
}
