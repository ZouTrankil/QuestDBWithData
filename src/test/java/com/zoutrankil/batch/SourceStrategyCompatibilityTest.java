package com.zoutrankil.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.service.PageExecutor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/** Captured expectations come from the pre-strategy implementation, not the replacement policies. */
class SourceStrategyCompatibilityTest {
    @TempDir Path archive;

    @TestFactory Stream<DynamicTest> everyRegisteredContractRetainsMetadataAndPhysicalProjection() throws Exception {
        return fixtures().stream().map(fixture -> DynamicTest.dynamicTest(fixture.path("dataset").asText(), () -> {
            String dataset=fixture.path("dataset").asText();var c=SourceContract.load(dataset);var expected=fixture.path("expected");
            assertEquals(expected.get("contract"),tree(c),"serialized record and derived properties");
            assertEquals(expected.get("pageContract"),page(c.pageContract()));
            String code=fixture.path("codes").isEmpty()?"000001.SZ":fixture.path("codes").get(0).asText();
            assertEquals(expected.get("providerContract"),page(c.providerContract(code)));
            assertEquals(expected.get("businessColumns"),tree(c.businessColumns()));
            assertEquals(expected.path("logicalDateColumn").asText(),c.logicalDateColumn());
            assertEquals(expected.path("writeBatchSize").asInt(),c.writeBatchSize());
            assertEquals(expected.path("ddl").asText(),c.createTableSql("jdb_test_baseline"));
            for(var entry:expected.path("validCodes").properties()) assertEquals(entry.getValue().asBoolean(),c.validCode(entry.getKey()),entry.getKey());
            var rows=Json.MAPPER.convertValue(expected.get("rows"),new TypeReference<List<Map<String,Object>>>() {});
            var physical=new ArrayList<Object>();var keys=new ArrayList<String>();
            for(var row:rows) {
                physical.add(c.physicalRow(row,Instant.parse("2026-10-01T01:02:03.123456789Z")));
                keys.add(c.key(row));
                assertEquals(tree(row),tree(c.validateNormalized(row,date(fixture),codes(fixture))),"frozen row validation");
            }
            assertEquals(expected.get("physical"),tree(physical));
            assertEquals(expected.get("keys"),tree(keys));
            assertEquals(expected.get("observedCodes"),tree(c.observedCodes(rows)));
            assertEquals(expected.path("coverage").asBoolean(),c.covers(rows,codes(fixture)));
            var prepared=c.prepareRows(rawRows(fixture),date(fixture),codes(fixture),code);
            // ChinaBond expands an unordered tenor map; the collector sorts its final business keys.
            assertTrue(StreamSupport.stream(tree(prepared).spliterator(),false)
                    .anyMatch(fixture.get("preparedRow")::equals),"captured prepared row");
        }));
    }

    @TestFactory Stream<DynamicTest> allCollectorsRetainExactRequestsFrozenBytesAndFingerprints() throws Exception {
        return fixtures().stream().map(fixture -> DynamicTest.dynamicTest(fixture.path("dataset").asText(), () -> {
            String dataset=fixture.path("dataset").asText();var expected=fixture.path("expected");
            var requests=new ArrayList<Map<String,Object>>();var rows=rawRows(fixture);
            var collector=new SourceCollector(archive.resolve(dataset));
            var result=collector.collect(new SourceCollector.Request(dataset,date(fixture),codes(fixture),"baseline-universe-v1",null),(provider,params)->{
                var call=new LinkedHashMap<String,Object>();call.put("contract",page(provider));call.put("params",new LinkedHashMap<>(params));requests.add(call);
                var response=rows;
                if(dataset.equals("fina_mainbz")) {
                    var changed=new LinkedHashMap<>(rows.getFirst());
                    changed.put("bz_code",Json.MAPPER.valueToTree(params.get("type")));
                    changed.put("bz_item",Json.MAPPER.valueToTree("baseline-"+params.get("type")));
                    response=List.of(changed);
                }
                return new PageExecutor.Page(response,null,true,"baseline-provider-v1");
            });
            assertArrayEquals(expected.path("frozen").asText().getBytes(StandardCharsets.UTF_8),Files.readAllBytes(Path.of(result.artifact())));
            assertEquals(expected.path("fingerprint").asText(),result.fingerprint());
            assertEquals(expected.path("state").asText(),result.state().name());
            assertEquals(expected.path("coverage").asBoolean(),result.completeCoverage());
            assertEquals(expected.get("requests"),tree(requests));
            assertEquals(expected.get("rows"),tree(collector.read(result.fingerprint()).rows()));
        }));
    }

    @TestFactory Stream<DynamicTest> validMissingNullWrongTypeDateAndUniverseOutcomesMatchTheOldImplementation() throws Exception {
        return fixtures().stream().flatMap(fixture -> StreamSupport.stream(fixture.path("normalizationCases").spliterator(),false).map(sample ->
                DynamicTest.dynamicTest(fixture.path("dataset").asText()+"/"+sample.path("name").asText(), () -> {
                    var c=SourceContract.load(fixture.path("dataset").asText());
                    var row=Json.MAPPER.convertValue(fixture.get("preparedRow"),new TypeReference<LinkedHashMap<String,JsonNode>>() {});
                    String name=sample.path("name").asText();
                    if(name.startsWith("missing-")) row.remove(name.substring(8));
                    if(name.startsWith("null-")) row.put(name.substring(5),Json.MAPPER.nullNode());
                    if(name.startsWith("type-")) row.put(name.substring(5),Json.MAPPER.valueToTree(false));
                    LocalDate date=date(sample);Set<String> codes=codes(sample);
                    if(sample.has("error")) {
                        var failure=assertThrows(Exception.class,()->c.normalize(row,date,codes));
                        assertEquals(sample.path("error").path("type").asText(),failure.getClass().getName());
                        assertEquals(sample.path("error").path("message").asText(),Objects.toString(failure.getMessage(),""));
                    } else assertEquals(sample.path("normalized").asText(),Json.write(c.normalize(row,date,codes)));
                })));
    }

    private static List<JsonNode> fixtures() throws Exception {
        try(var input=SourceStrategyCompatibilityTest.class.getResourceAsStream("/source-strategies-t13/sources-before-t13.json")) {
            assertNotNull(input);var root=Json.MAPPER.readTree(input);
            var result=new ArrayList<JsonNode>();root.forEach(result::add);
            assertEquals(SourceContract.SUPPORTED,new TreeSet<>(result.stream().map(n->n.path("dataset").asText()).toList()));
            assertEquals(41,result.size());return List.copyOf(result);
        }
    }
    private static LocalDate date(JsonNode node) {return LocalDate.parse(node.path("date").asText());}
    private static Set<String> codes(JsonNode node) {
        var result=new TreeSet<String>();node.path("codes").forEach(code->result.add(code.asText()));return Set.copyOf(result);
    }
    private static List<Map<String,JsonNode>> rawRows(JsonNode fixture) {
        return Json.MAPPER.convertValue(fixture.get("providerRows"),new TypeReference<List<Map<String,JsonNode>>>() {});
    }
    private static JsonNode tree(Object value) throws Exception {
        return Json.MAPPER.readTree(Json.MAPPER.writeValueAsBytes(value));
    }
    private static JsonNode page(PageContract contract) {
        ObjectNode result=Json.MAPPER.valueToTree(contract);
        result.set("allowedParameters",Json.MAPPER.valueToTree(new TreeSet<>(contract.allowedParameters())));return result;
    }
}
