package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipJobPlanTest {
    @TempDir Path folder;
    private static final Path REAL=Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json");
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @org.junit.jupiter.api.Tag("retained-parity")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_RETAINED_PARITY", matches="1")
    @Test void savedRealCatalogFreezesExplicitScopesAndRejectsDrift() throws Exception {
        assertTrue(Files.isRegularFile(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json")), "Retained parity explicitly requested but original input is missing: artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json");
        Path receipt=folder.resolve("catalog.json");Files.copy(REAL,receipt);
        var catalog=IndexMembershipClassificationSource.reopen(receipt,hash(Files.readAllBytes(receipt)));
        assertEquals(134,catalog.industries().size());
        var request=IndexMembershipJobPlan.freeze(catalog,List.of("801011.SI","801217.SI"),IndexMembershipSource.Selection.BOTH,LocalDate.of(2026,9,29));
        var scopes=IndexMembershipJobPlan.scopes(request);assertEquals(2,scopes.size());
        assertEquals("林业Ⅱ",scopes.getFirst().industryName());assertEquals("801217.SI",scopes.getLast().l2Code());
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL,request.mode());
        assertThrows(IllegalArgumentException.class,()->IndexMembershipJobPlan.freeze(catalog,List.of("999999.SI"),IndexMembershipSource.Selection.CURRENT,request.logicalDate()));
        assertThrows(IllegalArgumentException.class,()->IndexMembershipJobPlan.freeze(new IndexMembershipClassificationSource.Catalog(
                catalog.industries().subList(0,1),catalog.fingerprint(),catalog.receipt()),List.of("801011.SI"),IndexMembershipSource.Selection.BOTH,request.logicalDate()));
        Files.writeString(receipt," ",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,()->IndexMembershipJobPlan.scopes(request));
    }
    @org.junit.jupiter.api.Tag("retained-parity")
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_RETAINED_PARITY", matches="1")
    @Test void matchingHashDoesNotExcuseInvalidOrIncompleteCatalog() throws Exception {
        assertTrue(Files.isRegularFile(Path.of("artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json")), "Retained parity explicitly requested but original input is missing: artifacts/java-migration/D005/discovery-8303c5c1-b6ec-4472-9a0d-0d9e2486ea66/classification-8b4429b7-1613-49cd-9692-e6ce0422602a.json");
        var json=JobDefinitionJson.mapper();var original=json.readTree(REAL.toFile());
        for(String defect:List.of("endpoint","scope","count","short","duplicate")) {
            var bad=original.deepCopy();
            switch(defect) {
                case "endpoint"->((com.fasterxml.jackson.databind.node.ObjectNode)bad).put("endpoint","index_member_all");
                case "scope"->((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("parameters")).put("src","SW2014");
                case "count"->((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("completion")).put("rows",1);
                case "short"->{
                    ((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("rows")).remove(133);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("completion")).put("rows",133);
                }
                case "duplicate"->((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("rows")).set(1,bad.path("rows").get(0));
            }
            byte[] bytes=json.writeValueAsBytes(bad);Path receipt=folder.resolve(defect+".json");Files.write(receipt,bytes);
            assertThrows(IllegalArgumentException.class,()->IndexMembershipClassificationSource.reopen(receipt,hash(bytes)),defect);
        }
        Path large=folder.resolve("large.json");byte[] bytes=new byte[1024*1024+1];Files.write(large,bytes);
        assertThrows(IllegalArgumentException.class,()->IndexMembershipClassificationSource.reopen(large,hash(bytes)));
    }
}
