package com.zoutrankil.data.l2live.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AliyunL2WireParserTest {
    static RawCapture capture(long sequence,String topic,String payload,String recv) {
        Instant received=Instant.parse(recv);
        return new RawCapture("capture-"+sequence,sequence,"fixture-session",topic,
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),received,
                received.atZone(RawCapture.SHANGHAI).toLocalDate(),0,false,false);
    }
    static String market(String time,long price,long bidSize,long askSize) {
        var f=new ArrayList<>(List.of("510500.SH",time,""+price,"951200","1469604","63","15450","15450","15450","15430"));
        for(int i=0;i<10;i++)f.add(""+(15460+i*10));for(int i=0;i<10;i++)f.add(""+(askSize+i));
        for(int i=0;i<10;i++)f.add(""+(15450-i*10));for(int i=0;i<10;i++)f.add(""+(bidSize+i));
        f.addAll(List.of("15870","15150","4175384","5570300","16970","13890"));return String.join(",",f);
    }
    @Test void realWireShapePreservesShanghaiInstantAndAllTenBookLevels() {
        var row=new AliyunL2WireParser().parse(capture(1,"market/510500",market("93000000",15450,2000,1000),"2026-05-26T01:30:01Z"));
        assertTrue(row.isValid());assertEquals(Instant.parse("2026-05-26T01:30:00Z"),row.exchangeTs());
        assertEquals(new BigDecimal("1.5450"),row.fields().get("price"));assertEquals(15450L,row.fields().get("price_scaled"));
        assertEquals(new BigDecimal("1.5360"),row.fields().get("bid_price_10"));assertEquals(1009L,row.fields().get("ask_volume_10"));
        assertEquals(new BigDecimal("1.5870"),row.fields().get("weighted_ask_price"));
        assertEquals(1469604L,row.fields().get("total_amount"));assertEquals(true,row.fields().get("sequence_scope_unverified"));
        assertEquals("v2",row.fields().get("parser_version"));assertEquals(AliyunL2WireParser.FIELD_TYPES.keySet(),row.fields().keySet());
        assertThrows(UnsupportedOperationException.class,()->row.fields().put("price",BigDecimal.ZERO));
    }
    @Test void idsBeyondDoubleIntegerPrecisionRemainExactAndLongBoundaryIsChecked() {
        var parser=new AliyunL2WireParser();
        var row=parser.parse(capture(2,"trans/510500","510500.SH,B,93000000,9007199254740993,15460,1000,9007199254740995,9223372036854775808","2026-05-26T01:30:01Z"));
        assertTrue(row.isValid());assertEquals(9007199254740993L,row.fields().get("sub_seq"));
        assertEquals("9007199254740995",row.fields().get("sell_order_id"));assertEquals("9223372036854775808",row.fields().get("buy_order_id"));
        assertEquals(new BigDecimal("1546.0000"),row.fields().get("amount"));
        assertFalse(parser.parse(capture(3,"trans/510500","510500.SH,B,93000000,9223372036854775808,15460,1000,0,1","2026-05-26T01:30:01Z")).isValid());
    }
    @Test void shResidualAndSzOriginalOrderSizeAreDifferentDeclaredSemantics() {
        var parser=new AliyunL2WireParser();
        var sh=parser.parse(capture(4,"order/600000","600000.SH,20260526,93000000,0,46177,D,S,98500,8400","2026-05-26T01:30:01Z"));
        var sz=parser.parse(capture(5,"order/159920","159920.SZ,20260526,93000000,0,46177,0,S,15460,8400","2026-05-26T01:30:01Z"));
        assertEquals("cancel",sh.fields().get("event_type"));assertEquals(-11,sh.fields().get("kuake_order_type"));
        assertEquals("remaining_volume_after_match",sh.fields().get("volume_semantics"));
        assertEquals("add",sz.fields().get("event_type"));assertEquals(11,sz.fields().get("kuake_order_type"));
        assertEquals("original_order_volume",sz.fields().get("volume_semantics"));
    }
    @Test void malformedFieldsOrSymbolsAreRejectedWhileRawIdentityRemainsAvailable() {
        var parser=new AliyunL2WireParser();
        for(String payload:List.of("159920.SZ,B,93000000,1,15460,1000,0,1","510500.SH,B,93000000,1,bad,1000,0,1",
                "510500.SH,B,93000000,1,15460,-1,0,1","510500.SH,B,93000000,1,15460.5,1000,0,1",
                "510500.SH,X,93000000,1,15460,1000,0,1","510500.SH,B,256000000,1,15460,1000,0,1",
                "510500.SH,B,930000,1,15460,1000,0,1","510500.SH,B,93000000,1,15460,1000,0,1,extra")) {
            var raw=capture(6,"trans/510500",payload,"2026-05-26T01:30:01Z");var row=parser.parse(raw);
            assertFalse(row.isValid(),payload);assertSame(raw,row.capture());assertNull(row.exchangeTs());assertFalse(row.parseError().isBlank());
        }
    }
    @Test void rawBase64AndUtf8ValidationDoNotSilentlyReplaceWireBytes() {
        var raw=capture(7,"trans/510500","","2026-05-26T01:30:01Z");
        assertThrows(IllegalArgumentException.class,()->new RawCapture("c",0,"s","trans/510500","YQ",raw.recvTs(),raw.captureDate(),0,false,false));
        var invalid=new RawCapture("c",0,"s","trans/510500",Base64.getEncoder().encodeToString(new byte[]{(byte)0xc3,0x28}),raw.recvTs(),raw.captureDate(),0,false,false);
        assertEquals("invalid_utf8",new AliyunL2WireParser().parse(invalid).parseStatus());
        assertArrayEquals(new byte[]{(byte)0xc3,0x28},invalid.payloadBytes());
    }
    @Test void delayedOrderKeepsExplicitWireDateInsteadOfInventingTheReceiveDate() {
        var row=new AliyunL2WireParser().parse(capture(8,"order/510500","510500.SH,20260525,145700000,0,00123,A,B,15450,2000","2026-05-26T01:30:01Z"));
        assertTrue(row.isValid());assertEquals("00123",row.fields().get("order_id"));
        assertEquals(Instant.parse("2026-05-25T06:57:00Z"),row.exchangeTs());assertEquals(false,row.fields().get("wire_date_inferred"));
    }
    @Test void oversizedWireRetainsOriginalCaptureAndFieldCardWhileExactByteLimitRemainsAdmitted() {
        var parser=new AliyunL2WireParser();String wire="510500.SH,B,93000000,1,15460,1000,0,1";
        String atLimit=wire+" ".repeat(AliyunL2WireParser.MAX_WIRE_PAYLOAD_BYTES-wire.length());
        assertTrue(parser.parse(capture(9,"trans/510500",atLimit,"2026-05-26T01:30:01Z")).isValid());
        for(String payload:List.of("x".repeat(RawCapture.MAX_PAYLOAD_BYTES),"é".repeat(AliyunL2WireParser.MAX_WIRE_PAYLOAD_BYTES/2+1))) {
            var raw=capture(10,"trans/510500",payload,"2026-05-26T01:30:01Z");
            var row=parser.parse(raw);
            assertEquals("oversized_wire_payload",row.parseStatus());assertFalse(row.isValid());assertSame(raw,row.capture());
            assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8),row.capture().payloadBytes());
            assertEquals(AliyunL2WireParser.FIELD_TYPES.keySet(),row.fields().keySet());assertEquals("v2",row.fields().get("parser_version"));
            assertNull(row.exchangeTs());assertNotNull(row.parseError());
        }
    }
    @Test void bothSidesTopFiveAndTopTenOverflowBecomeInvalidEventsWithoutBlockingNextObservation() {
        var parser=new AliyunL2WireParser();
        var accumulator=new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.fixed(Instant.parse("2026-05-26T01:30:01Z"),ZoneOffset.UTC));
        for(String side:List.of("bid","ask"))for(int overflowLevel:new int[]{2,6}) {
            var fields=new ArrayList<>(List.of(market("93000000",15450,0,0).split(",",-1)));
            for(int level=1;level<=10;level++){fields.set(19+level,"0");fields.set(39+level,"0");}
            int first=side.equals("bid")?40:20;fields.set(first,Long.toString(Long.MAX_VALUE));fields.set(first+overflowLevel-1,"1");
            var raw=capture(11,"market/510500",String.join(",",fields),"2026-05-26T01:30:01Z");
            var rejected=parser.parse(raw);
            assertEquals("invalid_numeric",rejected.parseStatus());assertTrue(rejected.parseError().contains(side+" top"+(overflowLevel<=5?5:10)));
            assertSame(raw,rejected.capture());assertEquals(AliyunL2WireParser.FIELD_TYPES.keySet(),rejected.fields().keySet());
            assertTrue(accumulator.accept(rejected).isEmpty());
        }
        var normal=parser.parse(capture(12,"market/510500",market("93000000",15450,2000,1000),"2026-05-26T01:30:01Z"));
        assertTrue(normal.isValid());assertEquals(1L,accumulator.accept(normal).orElseThrow().metrics().get("event_count"));
        assertEquals(4L,accumulator.diagnosticCounters().get("invalid_events"));
        var boundary=new ArrayList<>(List.of(market("93000000",15450,0,0).split(",",-1)));
        for(int level=1;level<=10;level++){boundary.set(19+level,"0");boundary.set(39+level,level==1?Long.toString(Long.MAX_VALUE-9):"1");}
        var exact=parser.parse(capture(13,"market/510500",String.join(",",boundary),"2026-05-26T01:30:01Z"));
        assertTrue(exact.isValid());assertEquals(Long.MAX_VALUE,accumulator.accept(exact).orElseThrow().metrics().get("depth_bid_top10"));
    }
    @Test void subscriptionProofSurvivesJsonWhileAbsentHistoricalProofIsNeverInvented() throws Exception {
        var fixture=capture(14,"trans/510500","510500.SH,B,93000000,1,15460,1000,0,1","2026-05-26T01:30:01Z");
        assertTrue(fixture.subscriptionAuthorized());
        var unconfirmed=new RawCapture(fixture.captureId(),fixture.sequence(),fixture.sessionId(),fixture.topic(),fixture.payloadBase64(),
                fixture.recvTs(),fixture.captureDate(),fixture.qos(),fixture.retained(),fixture.duplicate(),false);
        var json=new ObjectMapper().registerModule(new JavaTimeModule());
        for(var capture:List.of(fixture,unconfirmed)) {
            String frozen=json.writeValueAsString(capture);
            assertEquals(capture.subscriptionAuthorized(),json.readTree(frozen).path("subscriptionAuthorized").booleanValue());
            assertEquals(capture,json.readValue(frozen,RawCapture.class));
        }
        ObjectNode historical=json.valueToTree(fixture);historical.remove("subscriptionAuthorized");
        assertFalse(json.treeToValue(historical,RawCapture.class).subscriptionAuthorized());
        var event=new AliyunL2WireParser().parse(unconfirmed);
        assertTrue(event.isValid());assertSame(unconfirmed,event.capture());assertFalse(event.capture().subscriptionAuthorized());
    }
}
