package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockDetailInfoSource;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.config.TushareProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class StockDetailInfoSourceTest {
    @TempDir Path root;
    private StockDetailInfoSource source(PageExecutor.Fetcher fetcher) {
        return new StockDetailInfoSource(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                assertEquals(Set.of("ts_code","list_status"),params.keySet());
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        },root);
    }
    private Map<String,JsonNode> row(String code,String status) {
        var values=new LinkedHashMap<String,JsonNode>();var json=JsonNodeFactory.instance;
        StockDetailInfoSource.FIELDS.forEach(field->values.put(field,json.nullNode()));
        values.put("ts_code",json.textNode(code));values.put("list_status",json.textNode(status));return values;
    }
    @Test void absentCodeIsExplicitEmptyButFailuresNeverBecomeEmpty() throws Exception {
        var calls=new ArrayList<String>();
        var empty=source(params->{calls.add((String)params.get("list_status"));return new PageExecutor.Page(List.of(),null,false,null);});
        assertTrue(empty.fetch("000001.SZ",Instant.EPOCH,()->false).rows().isEmpty());
        assertEquals(List.of("L","D","P"),calls);
        var failed=source(params->{throw new java.io.IOException("provider failure");});
        assertThrows(PageExecutor.Incomplete.class,()->failed.fetch("000001.SZ",Instant.EPOCH,()->false));
        var cancelled=source(params->{throw new AssertionError("Cancelled source invoked");});
        assertThrows(PageExecutor.Incomplete.class,()->cancelled.fetch("000001.SZ",Instant.EPOCH,()->true));
    }
    @Test void wrongIdentityAndChangingStatusCannotYieldPartialAcceptedPage() {
        var wrong=source(params->new PageExecutor.Page(List.of(row("600000.SH","L")),null,false,null));
        assertThrows(PageExecutor.Incomplete.class,()->wrong.fetch("000001.SZ",Instant.EPOCH,()->false));
        var changing=source(params->new PageExecutor.Page(List.of(row("000001.SZ",(String)params.get("list_status"))),null,false,null));
        assertThrows(IllegalStateException.class,()->changing.fetch("000001.SZ",Instant.EPOCH,()->false));
    }
    @Test void stockBasicCeilingPreservesStricterConfiguredBudget() {
        var properties=new TushareProperties();properties.setEndpointPerMinute(500);
        assertEquals(50,properties.effectiveEndpointLimits().get("stock_basic"));
        properties.setEndpointLimits(Map.of("stock_basic",5));
        assertEquals(5,properties.effectiveEndpointLimits().get("stock_basic"));
    }
}
