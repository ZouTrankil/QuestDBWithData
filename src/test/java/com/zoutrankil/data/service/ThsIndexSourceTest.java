package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ThsIndexSourceTest {
    @TempDir Path root;
    private final ThsIndexSource.Scope scope=new ThsIndexSource.Scope(null,"A","BB");
    private ThsIndexSource source(PageExecutor.Fetcher fetcher) {
        return new ThsIndexSource(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                assertEquals(scope.parameters(),params);assertEquals(PageContract.Paging.NONE,contract.paging());
                assertFalse(params.containsKey("offset"));assertFalse(params.containsKey("limit"));
                return new PageExecutor().execute(contract,params,fetcher,consumer,validator,cancelled);
            }
        },root);
    }
    private Map<String,JsonNode> row(String code) {
        var json=JsonNodeFactory.instance;var row=new LinkedHashMap<String,JsonNode>();
        ThsIndexSource.FIELDS.forEach(field->row.put(field,json.nullNode()));
        row.put("ts_code",json.textNode(code));row.put("exchange",json.textNode("A"));
        row.put("type",json.textNode("BB"));return row;
    }
    private PageExecutor.Page page(List<Map<String,JsonNode>> rows) { return new PageExecutor.Page(rows,null,false,null); }
    @Test void explicitEmptyHasReceiptButFailureAndCancellationCannotBecomeEmpty() throws Exception {
        assertTrue(source(p->page(List.of())).fetch(scope,Instant.EPOCH,()->false).rows().isEmpty());
        var failed=source(p->{throw new java.io.IOException("source failed");});
        assertThrows(PageExecutor.Incomplete.class,()->failed.fetch(scope,Instant.EPOCH,()->false));
        var cancelled=source(p->{throw new AssertionError("Cancelled source invoked");});
        assertThrows(PageExecutor.Incomplete.class,()->cancelled.fetch(scope,Instant.EPOCH,()->true));
        try(var files=Files.list(root)) { assertEquals(1,files.count()); }
    }
    @Test void capHitAndDuplicateIdentityAreIncompleteAndNeverSaveAcceptedReceipt() throws Exception {
        var rows=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<5000;i++) rows.add(row(String.format("%06d.TI",i)));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(rows)).fetch(scope,Instant.EPOCH,()->false));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(List.of(row("700001.TI"),row("700001.TI"))))
                .fetch(scope,Instant.EPOCH,()->false));
        try(var files=Files.list(root)) { assertEquals(0,files.count()); }
    }
    @Test void wrongScopeFractionalCountAndInvalidDateRejectWholeObservation() {
        var wrong=row("700001.TI");wrong.put("exchange",JsonNodeFactory.instance.textNode("HK"));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(List.of(wrong))).fetch(scope,Instant.EPOCH,()->false));
        var fractional=row("700001.TI");fractional.put("count",JsonNodeFactory.instance.numberNode(2.5));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(List.of(fractional))).fetch(scope,Instant.EPOCH,()->false));
        var invalidDate=row("700001.TI");invalidDate.put("list_date",JsonNodeFactory.instance.textNode("20260229"));
        assertThrows(PageExecutor.Incomplete.class,()->source(p->page(List.of(invalidDate))).fetch(scope,Instant.EPOCH,()->false));
    }
    @Test void limitsPreserveStricterBudgetAndScopesCannotBeImplicitOrAmbiguous() {
        var properties=new TushareProperties();properties.setEndpointPerMinute(500);
        assertEquals(200,properties.effectiveEndpointLimits().get("ths_index"));
        properties.setEndpointLimits(Map.of("ths_index",3));assertEquals(3,properties.effectiveEndpointLimits().get("ths_index"));
        assertTrue(ThsIndexSource.Scope.all().parameters().isEmpty());
        assertThrows(IllegalArgumentException.class,()->new ThsIndexSource.Scope("700001.TI","A","BB"));
    }
}
