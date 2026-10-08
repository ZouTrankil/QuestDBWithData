package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.mapper.*;
import com.zoutrankil.data.l2.storage.L2DatasetManifestWritePort;
import com.zoutrankil.data.l2.storage.L2DailyFeaturesWritePort;
import com.zoutrankil.data.l2.application.L2DailyFeaturesParquetSource;
import com.zoutrankil.data.l2.application.L2DatasetManifestJobService;
import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Offline synthetic compatibility capture: no writer/source instance, DB or process helper. */
class L2ManifestDailyGoldenContractTest {
    private static final ObjectMapper JSON = JobDefinitionJson.mapper();
    private final List<org.junit.jupiter.api.DynamicTest> checks = new ArrayList<>();
    private void same(String name,Object expected,Object actual) {
        checks.add(org.junit.jupiter.api.DynamicTest.dynamicTest(name,()->org.junit.jupiter.api.Assertions.assertEquals(expected,actual)));
    }
    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String text(JsonNode node,String name) {return node.path(name).isNull()?null:node.path(name).asText();}
    private static Boolean bool(JsonNode node,String name) {return node.path(name).isNull()?null:node.path(name).booleanValue();}
    private static List<String> strings(JsonNode array) {
        var result=new ArrayList<String>();for(var item:array)result.add(item.asText());return result;
    }
    // Only declared Set fields have unspecified cross-JVM order. Lists and canonical bytes remain exact.
    private static JsonNode normalizeSet(JsonNode definition,String field) {
        com.fasterxml.jackson.databind.node.ObjectNode copy=definition.deepCopy();
        var values=strings(copy.path(field));values.sort(String::compareTo);
        copy.set(field,JSON.valueToTree(values));return copy;
    }
    @org.junit.jupiter.api.TestFactory
    List<org.junit.jupiter.api.DynamicTest> syntheticOriginalClassGoldens() throws Exception {
        var golden=JSON.readTree(getClass().getResourceAsStream("/l2/d085-d086-independent-goldens.json"));
        var captured=new ArrayList<Map<String,Object>>();
        for(var test:golden.path("cases")) {
            String name=test.path("family").asText()+"/"+test.path("name").asText();var in=test.path("input");
            var item=new LinkedHashMap<String,Object>();item.put("name",name);item.put("input",in);
            if(test.path("family").asText().equals("D085")) {
                var date=LocalDate.parse(in.path("tradeDate").asText());
                var row=new L2DatasetManifest(date,text(in,"symbol"),text(in,"market"),text(in,"board"),
                        text(in,"sourceRoot"),text(in,"outputRoot"),text(in,"featureVersion"),bool(in,"dailyFeatureOk"),bool(in,"t0Ok"),
                        text(in,"rawRowCounts"),text(in,"outputPaths"),text(in,"costConfig"),text(in,"horizonsMin"),text(in,"errors"),
                        in.path("batchId").longValue(),LocalDate.parse(in.path("tradeDateTs").asText()));
                var codec=L2DatasetManifestWritePort.CODEC;byte[] bytes=codec.canonicalBytes(row);var mapper=new L2DatasetManifestMapper();
                same(name+"/binary",test.path("canonicalHex").asText(),HexFormat.of().formatHex(bytes));
                same(name+"/sha",test.path("canonicalSha256").asText(),hash(bytes));
                same(name+"/transport",test.path("transportBytes").asInt(),codec.estimatedTransportBytes(row,bytes));
                same(name+"/key",new L2DatasetManifestKey(date,row.symbol(),row.batchId()),codec.key(row));
                same(name+"/values-roundtrip",row,mapper.fromValues(mapper.values(row)));
                same(name+"/storage-roundtrip",row,mapper.fromStorage(mapper.toStorage(row)));
                same(name+"/carrier",Instant.parse("1969-12-31T00:00:00Z"),mapper.partitionCarrier(row));
                item.put("canonicalHex",HexFormat.of().formatHex(bytes));item.put("key",row.key());item.put("values",mapper.values(row));item.put("storage",mapper.toStorage(row));
            } else {
                var date=LocalDate.parse(in.path("ts").asText());var features=new EnumMap<L2DailyFeatureField,Object>(L2DailyFeatureField.class);
                for(var field:L2DailyFeatureField.values())if(!field.identity()){
                    var value=in.path(field.fieldName());Object v=null;
                    if(!value.isNull())v=switch(field.storageType()){
                        case BOOLEAN->value.booleanValue();case LONG->value.longValue();case DOUBLE->value.doubleValue();case STRING->value.textValue();
                        default->throw new AssertionError(field);
                    };features.put(field,v);
                }
                var row=new L2DailyFeatures(date,in.path("symbol").asText(),features);var codec=L2DailyFeaturesWritePort.CODEC;
                byte[] bytes=codec.canonicalBytes(row);var mapper=new L2DailyFeaturesMapper();
                same(name+"/ordered-json",test.path("canonicalUtf8").asText(),new String(bytes,StandardCharsets.UTF_8));
                same(name+"/sha",test.path("canonicalSha256").asText(),hash(bytes));
                same(name+"/transport",test.path("transportBytes").asInt(),codec.estimatedTransportBytes(row,bytes));
                same(name+"/key",new L2DailyFeaturesKey(date,row.symbol()),codec.key(row));
                same(name+"/values-roundtrip",row,mapper.fromValues(mapper.values(row)));
                same(name+"/column-order",strings(golden.path("columnOrder")),new ArrayList<>(mapper.values(row).columns()));
                var page=L2DailyFeaturesParquetSource.class.getDeclaredMethod("pageFingerprint",String.class,String.class,List.class);page.setAccessible(true);
                Object fingerprint=page.invoke(null,test.path("pageSourceFingerprint").asText(),test.path("pageCursor").asText(),List.of(row));
                same(name+"/page-frame",test.path("pageFingerprint").asText(),fingerprint);
                item.put("canonicalUtf8",new String(bytes,StandardCharsets.UTF_8));item.put("key",row.key());item.put("values",mapper.values(row));item.put("pageFingerprint",fingerprint);
            }
            captured.add(item);
        }
        var manifest=L2DatasetManifestDataset.DEFINITION;var daily=L2DailyFeaturesDataset.DEFINITION;
        same("D085/columns",16,manifest.columns().size());same("D086/columns",110,daily.columns().size());
        same("D085/business-key",List.of("trade_date_ts","symbol","batch_id"),manifest.businessKey());
        same("D085/dedup-key",List.of("symbol","batch_id","trade_date_ts"),manifest.dedupKey());
        same("D086/business-key",List.of("ts","symbol"),daily.businessKey());same("D086/dedup-key",List.of("ts","symbol"),daily.dedupKey());
        same("D085/partition",DatasetDefinition.Partition.DAY,manifest.partition());same("D086/partition",DatasetDefinition.Partition.DAY,daily.partition());
        same("D085/wal",true,manifest.wal());same("D086/wal",true,daily.wal());
        same("D085/schema-version",1,manifest.schemaVersion());same("D086/schema-version",1,daily.schemaVersion());
        var old=JSON.readTree(getClass().getResourceAsStream("/l2/d085-d086-old-definitions.json"));
        same("D085/dataset-json",normalizeSet(old.path("datasetDefinitions").get(0),"capabilities"),normalizeSet(JSON.valueToTree(manifest),"capabilities"));
        same("D086/dataset-json",normalizeSet(old.path("datasetDefinitions").get(1),"capabilities"),normalizeSet(JSON.valueToTree(daily),"capabilities"));
        same("D085/job-json",normalizeSet(old.path("jobDefinitions").get(0),"supportedModes"),normalizeSet(JSON.valueToTree(L2DatasetManifestJobService.definition()),"supportedModes"));
        same("D086/job-json",normalizeSet(old.path("jobDefinitions").get(1),"supportedModes"),normalizeSet(JSON.valueToTree(L2DailyFeaturesJobService.definition()),"supportedModes"));
        return List.copyOf(checks);
    }
}
