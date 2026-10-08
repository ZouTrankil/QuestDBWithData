package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import static com.zoutrankil.data.l2.application.L2EventAndT0Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class L2EventAndT0GoldenCompatibilityTest {
    @TestFactory List<DynamicTest> oldClassRowsErrorsAndPageFramesRemainExact() throws Exception {
        var tests=new ArrayList<DynamicTest>();
        for(Family family:Family.values()) {
            JsonNode golden=family.golden();
            for(JsonNode sample:golden.path("cases")) tests.add(DynamicTest.dynamicTest(family+"/"+sample.path("variant").asText(),()->{
                if(sample.has("errorType")) {
                    Exception failure=assertThrows(Exception.class,()->family.row(sample.path("input")));
                    assertEquals(sample.path("errorType").asText(),failure.getClass().getName());
                    assertEquals(sample.path("errorMessage").asText(),failure.getMessage());
                    return;
                }
                Object row=family.row(sample.path("input"));
                byte[] canonical=family.canonical(row);
                assertEquals(sample.path("row"),JSON.readTree(JSON.writeValueAsBytes(row)));
                assertEquals(sample.path("values"),JSON.readTree(JSON.writeValueAsBytes(family.values(row))));
                assertArrayEquals(Base64.getDecoder().decode(sample.path("canonicalBase64").asText()),canonical);
                assertEquals(sample.path("canonicalUtf8").asText(),new String(canonical,StandardCharsets.UTF_8));
                assertEquals(sample.path("canonicalSha256").asText(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)));
                assertArrayEquals(canonical,family.codec().canonicalBytes(row));
                assertEquals(sample.path("estimatedTransportBytes").asInt(),family.codec().estimatedTransportBytes(row,canonical));
                assertEquals(sample.path("pageFingerprint").asText(),family.pageFingerprint(List.of(row)));
            }));
            tests.add(DynamicTest.dynamicTest(family+"/definition-and-actual-budget",()->{
                ObjectNode actual=JSON.valueToTree(family.definition());
                ObjectNode expected=(ObjectNode)golden.path("definition").deepCopy();
                assertEquals(asSet(expected.remove("supportedModes")),asSet(actual.remove("supportedModes")));
                assertEquals(expected,actual);
                assertEquals(300_000,family.definition().budget().maxRows());
                assertEquals(10_000,family.definition().budget().maxSlices());
                assertEquals(25_000,family.definition().budget().maxPages());
            }));
        }
        return tests;
    }
    private static Set<String> asSet(JsonNode node) { var values=new HashSet<String>();node.forEach(value->values.add(value.asText()));return values; }
}
