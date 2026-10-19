package com.zoutrankil.data.flow.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.application.MarginDetailSource;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real page execution, normalization and durable receipts; only the provider response is offline. */
class UpsertSourceContractTest {
    @TempDir Path temp;
    static final LocalDate DAY=LocalDate.of(2026,9,17);
    enum Family { MONEYFLOW, DC, THS, DETAIL }

    @ParameterizedTest @EnumSource(Family.class)
    void frozenFieldsParametersCanonicalOrderingAndReopenKeepActualReceiptBytes(Family family)throws Exception {
        var rows=List.of(raw(family,"600000.SH"),raw(family,"000001.SZ"));
        var provider=new Pages(rows);var page=fetch(family,provider,temp,()->false);
        assertEquals(2,page.rows().size());assertEquals("20260917",page.cursor());
        assertEquals(List.of(family==Family.DC?Map.of("trade_date","20260917","limit",2000,"offset",0L):Map.of("trade_date","20260917")),provider.requests);
        assertEquals(fields(family),provider.contract.fields());
        var path=Path.of(page.responseEvidence());byte[] bytes=Files.readAllBytes(path);
        assertEquals(FileEvidenceStore.sha256(bytes),page.sourceFingerprint());
        JsonNode proof=JobDefinitionJson.mapper().readTree(bytes);
        assertTrue(proof.path("sourceComplete").asBoolean());assertEquals(6000,proof.path("apiMaximumRows").asInt());
        assertEquals("000001.SZ",proof.path("rawRows").get(0).path("ts_code").asText());
        assertEquals("fixture-v1",proof.path("sourceVersion").asText());
        assertEquals(page.rows(),reopen(family,path,page.sourceFingerprint()).rows());
        assertArrayEquals(bytes,Files.readAllBytes(path));
        Files.writeString(path,"\n",StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class,()->reopen(family,path,page.sourceFingerprint()));
    }

    @ParameterizedTest @EnumSource(Family.class)
    void cancellationAndInvalidDateNeverCreateCompleteReceipts(Family family)throws Exception {
        var provider=new Pages(List.of(raw(family,"000001.SZ")));
        assertThrows(Exception.class,()->fetch(family,provider,temp.resolve("cancel"),()->true));
        assertEquals(0,provider.requests.size());
        var wrong=new LinkedHashMap<>(raw(family,"000001.SZ"));wrong.put("trade_date",JobDefinitionJson.mapper().valueToTree("20260916"));
        assertThrows(Exception.class,()->fetch(family,new Pages(List.of(wrong)),temp.resolve("wrong"),()->false));
        try(var paths=Files.walk(temp)) {for(var path:paths.filter(Files::isRegularFile).toList()) {
            assertTrue(path.getFileName().toString().startsWith("unverified-"));
            assertFalse(JobDefinitionJson.mapper().readTree(path.toFile()).path("sourceComplete").asBoolean());
        }}
    }

    @Test void dcV2AcceptsTenThousandRowsOnlyWithTheSixthShortTerminalPage()throws Exception {
        var rows=new ArrayList<Map<String,JsonNode>>();for(int i=0;i<10000;i++)rows.add(raw(Family.DC,String.format(Locale.ROOT,"%06d.SZ",i)));
        var provider=new Pages(rows);var page=fetch(Family.DC,provider,temp,()->false);
        assertEquals(10000,page.rows().size());assertEquals(6,provider.requests.size());
        assertEquals(List.of(0L,2000L,4000L,6000L,8000L,10000L),provider.requests.stream().map(p->p.get("offset")).toList());
        var proof=JobDefinitionJson.mapper().readTree(Path.of(page.responseEvidence()).toFile());
        assertEquals(2,proof.path("receiptVersion").asInt());assertEquals(10000,proof.path("dailyRowBound").asInt());
        assertEquals(0,proof.path("pageProofs").get(5).path("returnedRows").asInt());
        assertEquals(page.rows(),reopen(Family.DC,Path.of(page.responseEvidence()),page.sourceFingerprint()).rows());
    }

    @Test void dcRejectsLegacyUnpagedReceiptsAndChangedPagingProofEvenWithRecomputedHash()throws Exception {
        var page=fetch(Family.DC,new Pages(List.of(raw(Family.DC,"000001.SZ"))),temp,()->false);
        var original=(ObjectNode)JobDefinitionJson.mapper().readTree(Path.of(page.responseEvidence()).toFile());
        for(boolean legacy:List.of(true,false)) {
            var altered=original.deepCopy();
            if(legacy)altered.remove(List.of("receiptVersion","pageProofs","dailyRowBound","pageSize"));
            else ((ObjectNode)altered.path("pageProofs").get(0)).put("offset",2000);
            byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(altered);Path path=temp.resolve("altered-"+legacy+".json");Files.write(path,bytes);
            assertThrows(IllegalStateException.class,()->reopen(Family.DC,path,FileEvidenceStore.sha256(bytes)));
        }
        assertEquals(2,MoneyflowDcSyncJobOwner.DEFINITION.version());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void detailRequiresBothMarketsAndKeepsTheDateFooterInRawEvidence(boolean legacy)throws Exception {
        var footer=new LinkedHashMap<String,JsonNode>();for(String f:fields(Family.DETAIL))footer.put(f,JobDefinitionJson.mapper().nullNode());
        footer.put("trade_date",JobDefinitionJson.mapper().valueToTree("20260917"));footer.put("ts_code",JobDefinitionJson.mapper().valueToTree("日期：2026-09-17.BJ"));
        var page=fetch(Family.DETAIL,new Pages(List.of(raw(Family.DETAIL,"000001.SZ"),footer,raw(Family.DETAIL,"600000.SH"))),temp,()->false);
        assertEquals(2,page.rows().size());var proof=(ObjectNode)JobDefinitionJson.mapper().readTree(Path.of(page.responseEvidence()).toFile());
        assertEquals(3,proof.path("returnedRows").asInt());assertEquals(1,proof.path("excludedDateFooters").asInt());
        assertEquals(1,proof.path("exchangeCounts").path("SH").asInt());assertEquals(1,proof.path("exchangeCounts").path("SZ").asInt());
        if(legacy) {
            proof.remove(List.of("exchangeCounts","marketCoveragePolicy","fullMarketCoverageVerified"));
            byte[] bytes=JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof);Path path=temp.resolve("legacy.json");Files.write(path,bytes);
            assertEquals(page.rows(),reopen(Family.DETAIL,path,FileEvidenceStore.sha256(bytes)).rows());
        }
        assertThrows(IllegalStateException.class,()->fetch(Family.DETAIL,new Pages(List.of(raw(Family.DETAIL,"600000.SH"))),temp.resolve("partial"),()->false));
    }

    static List<String> fields(Family f){return switch(f){case MONEYFLOW->MoneyflowSource.FIELDS;case DC->MoneyflowDcSource.FIELDS;case THS->MoneyflowThsSource.FIELDS;case DETAIL->MarginDetailSource.FIELDS;};}
    static Map<String,JsonNode> raw(Family f,String code){
        var result=new LinkedHashMap<String,JsonNode>();var json=JobDefinitionJson.mapper();
        for(String field:fields(f))result.put(field,json.valueToTree(1));
        result.put("ts_code",json.valueToTree(code));result.put("trade_date",json.valueToTree("20260917"));
        if(result.containsKey("name"))result.put("name",json.valueToTree("测试"));
        return result;
    }
    static SyncJobRunner.Page<?> fetch(Family f,TusharePageService pages,Path root,BooleanSupplier cancel)throws Exception {
        return switch(f){case MONEYFLOW->new MoneyflowSource(pages,root).fetch(DAY,cancel);case DC->new MoneyflowDcSource(pages,root).fetch(DAY,cancel);case THS->new MoneyflowThsSource(pages,root).fetch(DAY,cancel);case DETAIL->new MarginDetailSource(pages,root).fetch(DAY,cancel);};
    }
    static SyncJobRunner.Page<?> reopen(Family f,Path path,String hash)throws Exception {
        return switch(f){case MONEYFLOW->MoneyflowSource.reopen(path,hash,DAY);case DC->MoneyflowDcSource.reopen(path,hash,DAY);case THS->MoneyflowThsSource.reopen(path,hash,DAY);case DETAIL->MarginDetailSource.reopen(path,hash,DAY);};
    }
    static final class Pages extends TusharePageService {
        final List<Map<String,JsonNode>> rows;final List<Map<String,Object>> requests=new ArrayList<>();PageContract contract;
        Pages(List<Map<String,JsonNode>> rows){super(null);this.rows=rows;}
        @Override public PageExecutor.Fetcher fetcher(PageContract contract,BooleanSupplier cancel){this.contract=contract;return params->{
            requests.add(Map.copyOf(params));int from=((Number)params.getOrDefault("offset",0)).intValue();int limit=((Number)params.getOrDefault("limit",rows.size())).intValue();
            return new PageExecutor.Page(rows.subList(Math.min(from,rows.size()),Math.min(from+limit,rows.size())),null,false,"fixture-v1");
        };}
    }
}
