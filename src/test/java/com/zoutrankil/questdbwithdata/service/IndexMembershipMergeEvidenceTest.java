package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.client.dto.TushareIndexMembershipDto;
import com.zoutrankil.questdbwithdata.mapper.IndexMembershipMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Saved real inputs only; this is merge evidence, not a database write acceptance. */
class IndexMembershipMergeEvidenceTest {
    @Test void boundedRealIndustrySourceMergesWithRetainedActualDatabaseReadback() throws Exception {
        var json=JobDefinitionJson.mapper();var mapper=new IndexMembershipMapper();
        Path base=Path.of("artifacts/java-migration/D005");
        var before=json.convertValue(json.readTree(base.resolve("read-live.json").toFile()).path("rows"),
                new com.fasterxml.jackson.core.type.TypeReference<List<IndexMembership>>() {});
        var source=new ArrayList<IndexMembership>();var receipts=new ArrayList<String>();
        for(String flag:List.of("Y","N")) {
            var path=base.resolve("source-preflight-e26420e4-9eda-41a7-9a91-2329ce57419f").resolve(flag+"-response.json");
            receipts.add(path.toString());var proof=json.readTree(path.toFile());
            var observed=Instant.parse(proof.path("observedAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for(var r:proof.path("rows")) source.add(mapper.fromSource(new TushareIndexMembershipDto(
                    value(r,"l1_code"),value(r,"l1_name"),value(r,"l2_code"),value(r,"l2_name"),value(r,"l3_code"),value(r,"l3_name"),
                    value(r,"ts_code"),value(r,"name"),value(r,"in_date"),value(r,"out_date"),value(r,"is_new")),"801011.SI","林业Ⅱ",observed));
        }
        var merged=IndexMembershipMerge.mergeSource(before,source,"801011.SI");
        assertEquals(before.size()+merged.inserted(),merged.rows().size());
        var indexed=new HashMap<IndexMembership.Key,IndexMembership>();
        for(var row:merged.rows()) assertNull(indexed.put(row.key(),row));
        for(var row:source) assertTrue(IndexMembershipMerge.sameBusinessValues(row,indexed.get(row.key())));
        for(var row:before) if(!row.indexCode().equals("801011.SI")) assertEquals(row,indexed.get(row.key()));
        var repeated=IndexMembershipMerge.mergeSource(merged.rows(),source,"801011.SI");
        assertFalse(repeated.requiresWrite());assertEquals(merged.rows(),repeated.rows());
        json.writerWithDefaultPrettyPrinter().writeValue(base.resolve("merge-evidence.json").toFile(),Map.of(
                "beforeRows",before.size(),"sourceRows",source.size(),"mergedRows",merged.rows().size(),
                "inserted",merged.inserted(),"revised",merged.revised(),"unchanged",merged.unchanged(),
                "retainedAbsent",merged.retainedAbsent(),"repeatUnchanged",repeated.unchanged(),
                "sourceReceipts",receipts,"questdbWrites",0));
    }
    private static String value(JsonNode row,String field) { return row.path(field).isNull()?null:row.path(field).asText(); }
}
