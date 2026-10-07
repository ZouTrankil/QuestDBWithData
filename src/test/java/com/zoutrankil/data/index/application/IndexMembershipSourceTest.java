package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.config.TushareProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipSourceTest {
    @TempDir Path evidence;
    private IndexMembershipSource source(PageExecutor.Fetcher fetcher) {
        return new IndexMembershipSource(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                assertEquals(Set.of("l2_code","is_new"),params.keySet());
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        },evidence);
    }
    private IndexMembershipSource.Scope scope() { return new IndexMembershipSource.Scope("801011.SI","林业Ⅱ",IndexMembershipSource.Selection.BOTH); }
    private Map<String,JsonNode> row(String code,String flag) {
        var f=JsonNodeFactory.instance;var row=new LinkedHashMap<String,JsonNode>();
        IndexMembershipSource.FIELDS.forEach(k->row.put(k,f.nullNode()));
        for(var e:Map.of("l1_code","801010.SI","l2_code","801011.SI","l3_code","850131.SI",
                "ts_code",code,"in_date","20220729","is_new",flag).entrySet()) row.put(e.getKey(),f.textNode(e.getValue()));
        return row;
    }
    private PageExecutor.Page page(List<Map<String,JsonNode>> rows) { return new PageExecutor.Page(rows,null,false,null); }
    private long completedReceipts() throws Exception {
        try(var files=Files.list(evidence)) { return files.filter(p->p.getFileName().toString().startsWith("membership-complete-")).count(); }
    }
    @Test void explicitEmptyResponsesHaveOneCompleteSliceAndRespectStricterRate() throws Exception {
        var requests=new ArrayList<String>();
        var result=source(p->{requests.add((String)p.get("is_new"));return page(List.of());}).fetch(scope(),Instant.EPOCH,()->false);
        assertEquals(List.of("Y","N"),requests);assertTrue(result.rows().isEmpty());assertEquals(1,completedReceipts());
        var receipt=com.zoutrankil.data.domain.JobDefinitionJson.mapper()
                .readTree(Path.of(result.responseEvidence()).toFile());
        for(var part:receipt.path("requests"))
            assertDoesNotThrow(()->Instant.parse(part.path("response").path("capturedAt").asText()));
        var config=new TushareProperties();config.setEndpointPerMinute(10000);
        assertEquals(100,config.effectiveEndpointLimits().get("index_classify"));assertEquals(5000,config.effectiveEndpointLimits().get("index_member_all"));
        config.setEndpointLimits(Map.of("index_member_all",3));assertEquals(3,config.effectiveEndpointLimits().get("index_member_all"));
    }
    @Test void failureOfSecondRequestKeepsPartialReceiptButCannotComplete() throws Exception {
        assertThrows(PageExecutor.Incomplete.class,()->source(p->{
            if(p.get("is_new").equals("N")) throw new java.io.IOException("controlled source fault");
            return page(List.of(row("000663.SZ","Y")));
        }).fetch(scope(),Instant.EPOCH,()->false));
        assertEquals(0,completedReceipts());try(var files=Files.list(evidence)) { assertEquals(1,files.count()); }
    }
    @Test void contradictoryPeriodAcrossCurrentAndHistoricalResponsesIsRejected() throws Exception {
        assertThrows(IllegalStateException.class,()->source(p->page(List.of(row("000663.SZ",(String)p.get("is_new")))))
                .fetch(scope(),Instant.EPOCH,()->false));assertEquals(0,completedReceipts());
    }
    @Test void capHitWrongFlagAndPrecancelCannotComplete() throws Exception {
        var rows=new ArrayList<Map<String,JsonNode>>();for(int i=0;i<2000;i++) rows.add(row("%06d.SZ".formatted(i),"Y"));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(rows)).fetch(scope(),Instant.EPOCH,()->false));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(List.of(row("000663.SZ","N")))).fetch(scope(),Instant.EPOCH,()->false));
        assertThrows(java.util.concurrent.CancellationException.class,()->source(p->{throw new AssertionError("Cancelled fetch called");}).fetch(scope(),Instant.EPOCH,()->true));
        assertEquals(0,completedReceipts());
    }
}
