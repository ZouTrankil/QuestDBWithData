package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsYahooDailyBundleTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final LocalDate FROM=LocalDate.of(2026,1,2),THROUGH=LocalDate.of(2026,1,5);
    private static final String BUNDLE="broad-00000000-0000-0000-0000-000000000001";
    @Test void freezesAllRawResponsesAndOrdersInterleavedSymbolsByDateWithoutChangingTheirValues()throws Exception {
        var x=source("X",5);var a=source("A",10);byte[] frozen=UsYahooDailyBundle.freeze(BUNDLE,FROM,THROUGH,List.of(x,a));var result=UsYahooDailyBundle.parse(frozen);
        assertEquals(List.of("A","X","A","X"),result.rows().stream().map(r->r.get("symbol")).toList());assertEquals(List.of(10.,5.,11.,6.),result.rows().stream().map(r->r.get("close")).toList());
        assertEquals("2026-01-02T00:00:00Z",result.rows().getFirst().get("trade_date"));assertEquals("2026-01-05T00:00:00Z",result.rows().getLast().get("trade_date"));assertEquals(a.sha256(),result.rows().getFirst().get("source_sha256"));assertEquals(4.,result.rows().get(1).get("adj_close"));assertEquals(0.,result.rows().get(1).get("dividend"));assertEquals(1.,result.rows().get(1).get("split_factor"));assertEquals(2,result.members().size());
    }
    @Test void mismatchedRawDigestDuplicatedMembersAndChangedQueueCutoffAreRejected()throws Exception {
        var x=source("X",5);var changed=new UsYahooDailyBundle.Source(x.task(),x.symbol(),x.yahooSymbol(),x.sourceUrl(),"a".repeat(64),x.observedAt(),x.body());
        assertThrows(IllegalArgumentException.class,()->UsYahooDailyBundle.parse(UsYahooDailyBundle.freeze(BUNDLE,FROM,THROUGH,List.of(changed))));
        assertThrows(IllegalArgumentException.class,()->UsYahooDailyBundle.parse(UsYahooDailyBundle.freeze(BUNDLE,FROM,THROUGH,List.of(x,x))));
        assertThrows(IllegalArgumentException.class,()->UsYahooDailyBundle.parse(UsYahooDailyBundle.freeze(BUNDLE,FROM,THROUGH.plusDays(1),List.of(x))));
    }
    private static UsYahooDailyBundle.Source source(String symbol,int close)throws Exception {
        var metadata=Map.of("symbol",symbol,"currency","USD","exchangeTimezoneName","America/New_York","instrumentType","EQUITY");
        var quote=new HashMap<String,Object>();for(String f:List.of("open","high","low","close"))quote.put(f,List.of(close,close+1));quote.put("volume",List.of(100,200));
        var result=Map.of("meta",metadata,"timestamp",List.of(1767378600L,1767637800L),"indicators",Map.of("quote",List.of(quote),"adjclose",List.of(Map.of("adjclose",List.of(close-1,close)))));
        var chart=new HashMap<String,Object>();chart.put("error",null);chart.put("result",List.of(result));byte[] body=JSON.writeValueAsBytes(Map.of("chart",chart));
        String task=sha(("v1|DAILY|"+symbol+"|"+THROUGH+"|").getBytes(StandardCharsets.UTF_8));return new UsYahooDailyBundle.Source(task,symbol,symbol,"https://query1.finance.yahoo.com/v8/finance/chart/"+symbol,sha(body),Instant.parse("2026-10-19T00:00:00Z"),body);
    }
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
