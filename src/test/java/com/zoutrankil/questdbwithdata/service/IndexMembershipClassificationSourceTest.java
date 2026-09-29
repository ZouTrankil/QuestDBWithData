package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipClassificationSourceTest {
    @TempDir Path folder;
    private IndexMembershipClassificationSource source(List<Map<String,JsonNode>> rows) {
        return new IndexMembershipClassificationSource(new TusharePageService(null) {
            @Override public PageExecutor.Completed execute(PageContract contract,Map<String,Object> params,
                    PageExecutor.Consumer consumer,PageExecutor.Validator validator,BooleanSupplier cancelled) throws Exception {
                assertEquals(Map.of("level","L2","src","SW2021"),params);
                return new PageExecutor().execute(contract,params,p->new PageExecutor.Page(rows,null,false,null),consumer,validator,cancelled);
            }
        },folder);
    }
    private Map<String,JsonNode> row() {
        var r=new LinkedHashMap<String,JsonNode>();
        Map.of("index_code","801011.SI","industry_name","林业Ⅱ","level","L2","src","SW2021",
                "industry_code","110300","parent_code","110000","is_pub","0").forEach((k,v)->r.put(k,JsonNodeFactory.instance.textNode(v)));
        return r;
    }
    private List<Map<String,JsonNode>> catalogRows() {
        var rows=new ArrayList<Map<String,JsonNode>>();rows.add(row());
        for(int i=0;i<IndexMembershipClassificationSource.SW2021_L2_COUNT-1;i++) {
            var other=new LinkedHashMap<>(row());
            other.put("index_code",JsonNodeFactory.instance.textNode("%06d.SI".formatted(810000+i)));
            rows.add(other);
        }
        return rows;
    }
    @Test void unpublishedIndustryRemainsSelectableAndSelectionIsFrozenAndBounded() throws Exception {
        var catalog=source(catalogRows()).fetch(()->false);
        var selected=catalog.select(List.of("801011.SI"),IndexMembershipSource.Selection.BOTH);
        assertEquals("林业Ⅱ",selected.getFirst().industryName());assertEquals("0",catalog.industries().getFirst().published());
        assertThrows(IllegalArgumentException.class,()->catalog.select(List.of("801012.SI"),IndexMembershipSource.Selection.CURRENT));
        assertThrows(IllegalArgumentException.class,()->catalog.select(List.of(),IndexMembershipSource.Selection.BOTH));
        assertThrows(IllegalArgumentException.class,()->catalog.select(List.of("801011.SI","801011.SI"),IndexMembershipSource.Selection.BOTH));
        assertTrue(Files.isRegularFile(Path.of(catalog.receipt())));
    }
    @Test void emptyDuplicateWrongVersionAndCancellationCannotCreateAcceptedCatalog() throws Exception {
        assertThrows(IllegalStateException.class,()->source(List.of()).fetch(()->false));
        assertThrows(IllegalStateException.class,()->source(catalogRows().subList(0,133)).fetch(()->false));
        assertThrows(PageExecutor.Incomplete.class,()->source(List.of(row(),row())).fetch(()->false));
        var wrong=row();wrong.put("src",JsonNodeFactory.instance.textNode("SW2014"));
        assertThrows(PageExecutor.Incomplete.class,()->source(List.of(wrong)).fetch(()->false));
        assertThrows(PageExecutor.Incomplete.class,()->source(List.of(row())).fetch(()->true));
        try(var files=Files.list(folder)) { assertEquals(0,files.count()); }
    }
}
