package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockDetailInfoDiscovery;
import com.zoutrankil.data.stock.application.StockDetailInfoSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class StockDetailInfoDiscoveryTest {
    @TempDir Path root;

    private StockDetailInfoDiscovery discovery(PageExecutor.Fetcher fetcher) {
        return new StockDetailInfoDiscovery(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract, Map<String,Object> params,
                    PageExecutor.Consumer consumer, PageExecutor.Validator validator, BooleanSupplier cancelled)
                    throws Exception {
                assertEquals(6000,contract.sourceRowCap());
                assertEquals(java.util.Set.of("list_status","exchange"),params.keySet());
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        }, root);
    }

    private static Map<String,JsonNode> row(String code, String status, String exchange) {
        var result = new LinkedHashMap<String,JsonNode>();
        var json = JsonNodeFactory.instance;
        StockDetailInfoSource.FIELDS.forEach(field -> result.put(field,json.nullNode()));
        result.put("ts_code",json.textNode(code));
        result.put("list_status",json.textNode(status));
        result.put("exchange",json.textNode(exchange));
        return result;
    }

    @Test void nineFiniteSlicesFindNewIdentityAndRetainReceipts() throws Exception {
        var called = new ArrayList<String>();
        var source = discovery(params -> {
            var status=(String)params.get("list_status");
            var exchange=(String)params.get("exchange");
            called.add(status+":"+exchange);
            var rows = status.equals("L") && exchange.equals("SZSE")
                    ? List.of(row("000001.SZ",status,exchange)) : List.<Map<String,JsonNode>>of();
            return new PageExecutor.Page(rows,null,false,null);
        });
        var result=source.fetch(Instant.EPOCH,()->false);
        assertEquals(9,called.size());
        assertEquals(9,result.receipts().size());
        assertEquals(1,result.rows().size());
        assertEquals("000001.SZ",result.rows().getFirst().tsCode());
        assertEquals(1,result.sliceCounts().get("L:SZSE"));
        assertTrue(result.receipts().stream().allMatch(java.nio.file.Files::isRegularFile));
    }

    @Test void capSizedSliceAndCrossSliceDuplicateCannotClaimCompletion() {
        var capped=discovery(params -> {
            var rows=new ArrayList<Map<String,JsonNode>>();
            for(int i=0;i<6000;i++) rows.add(row(String.format("%06d.SZ",i),
                    (String)params.get("list_status"),(String)params.get("exchange")));
            return new PageExecutor.Page(rows,null,false,null);
        });
        assertThrows(PageExecutor.Truncated.class,()->capped.fetch(Instant.EPOCH,()->false));
        var duplicated=discovery(params -> {
            var status=(String)params.get("list_status");
            var exchange=(String)params.get("exchange");
            var rows=exchange.equals("SZSE") && (status.equals("L") || status.equals("D"))
                    ? List.of(row("000001.SZ",status,exchange)) : List.<Map<String,JsonNode>>of();
            return new PageExecutor.Page(rows,null,false,null);
        });
        assertThrows(IllegalStateException.class,()->duplicated.fetch(Instant.EPOCH,()->false));
    }

    @Test void allEmptyOrWrongExchangeIsNotACompleteStockSnapshot() {
        var empty=discovery(params -> new PageExecutor.Page(List.of(),null,false,null));
        assertThrows(IllegalStateException.class,()->empty.fetch(Instant.EPOCH,()->false));
        var wrong=discovery(params -> new PageExecutor.Page(List.of(row("000001.SZ",
                (String)params.get("list_status"),"WRONG")),null,false,null));
        assertThrows(PageExecutor.Incomplete.class,()->wrong.fetch(Instant.EPOCH,()->false));
    }
}
