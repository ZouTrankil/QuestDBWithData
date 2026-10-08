package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class L2QuestDbTablePortTest {
    @Test void encodesDealAndOrderEventsWithNanosecondPrecisionAndNullOptionalFields() {
        String table="jdb_test_l2_0123456789abcdef_deals";
        var deal=new LinkedHashMap<String,Object>();deal.put("event_ts","2026-09-29T01:30:00.123Z");deal.put("ts_code","000001.SZ");deal.put("source_row_number",1L);deal.put("trade_date","2026-09-29");deal.put("raw_time",93000123);deal.put("deal_id","100");deal.put("bs_flag","B");deal.put("buy_order_id","200");deal.put("sell_order_id","");deal.put("price_cny",12.3456d);deal.put("volume",100L);deal.put("side","BUY");
        String line=new String(L2QuestDbTablePort.encode(L2QuestDbTablePort.Product.DEALS,table,List.of(deal)),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(line.startsWith(table+",ts_code=000001.SZ,side=BUY source_row_number=1i,trade_date=\"2026-09-29\",raw_time=93000123i,deal_id=\"100\""),line);
        assertTrue(line.contains("price_cny=12.3456,volume=100i "));
        assertTrue(line.endsWith("1790645400123000000\n"));
    }

    @Test void schemaIsFixedAndOnlyArchiveScopedTestTargetsAreAccepted() {
        assertEquals(12,L2QuestDbTablePort.columns(L2QuestDbTablePort.Product.DEALS).size());
        assertEquals(13,L2QuestDbTablePort.columns(L2QuestDbTablePort.Product.ORDERS).size());
        assertEquals(55,L2QuestDbTablePort.columns(L2QuestDbTablePort.Product.QUOTES).size());
        var row=new LinkedHashMap<String,Object>();row.put("event_ts",Instant.parse("2026-09-29T01:30:00Z").toString());row.put("ts_code","000001.SZ");row.put("source_row_number",1L);
        assertThrows(IllegalArgumentException.class,()->new L2QuestDbTablePort(URI.create("http://127.0.0.1:9000"),null,
                L2QuestDbTablePort.Product.DEALS,"deals",List.of(row)));
        assertThrows(IllegalArgumentException.class,()->L2QuestDbTablePort.encode(L2QuestDbTablePort.Product.DEALS,
                "jdb_test_live_business_table",List.of()));
    }
}
