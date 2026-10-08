package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.L2IntradayBarFeatures;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.zoutrankil.data.l2.application.L2IntradayBarFeaturesFixtures.*;

/** Reads temporary JSONL through the existing private parser; never launches Python. */
class L2IntradayBarFeaturesSourceContractTest {
    @TempDir Path temp;

    @Test void completeReceiptProducesExactGoldenPageAndCompletion()throws Exception{
        var pages=new ArrayList<SyncJobRunner.Page<L2IntradayBarFeatures>>();
        var completion=consume(List.of(header(),page(),completion()),pages,()->false);
        assertEquals(1,completion.pages());assertEquals(2,completion.rows());assertTrue(completion.complete());
        assertEquals(1,pages.size());assertEquals(rows(),pages.getFirst().rows());
        assertEquals(PAGE_HASH,pages.getFirst().sourceFingerprint());assertEquals(CURSOR,pages.getFirst().cursor());
        assertEquals("c".repeat(64),JSON.readTree(pages.getFirst().responseEvidence()).path("manifestReceiptFingerprint").asText());
    }

    @ParameterizedTest @ValueSource(strings={"root_drift","missing_completion","trailing_record","bad_count","repeated_cursor","repeated_key","bad_part_path"})
    void malformedOrIncompleteReceiptFailsWithoutInventingCompletion(String kind)throws Exception{
        var lines=new ArrayList<JsonNode>(List.of(header(),page(),completion()));
        switch(kind){
            case "root_drift"->((ObjectNode)lines.getFirst()).put("rootIdentity","f".repeat(64));
            case "missing_completion"->lines.removeLast();
            case "trailing_record"->lines.add(page());
            case "bad_count"->((ObjectNode)lines.getLast()).put("returnedRows",3);
            case "repeated_cursor"->lines.add(2,page());
            case "repeated_key"->{var duplicate=page();duplicate.put("cursor","20260921:3:2");((ObjectNode)duplicate.path("responseEvidence")).put("sourceRowOffset",2);lines.add(2,duplicate);}
            case "bad_part_path"->((ObjectNode)lines.get(1).path("responseEvidence").path("featureParts").get(0)).put("path","../outside.parquet");
            default->throw new AssertionError(kind);
        }
        assertThrows(IOException.class,()->consume(lines,new ArrayList<>(),()->false));
    }

    @Test void cancellationPrecedesFirstPageCallback()throws Exception{
        var pages=new ArrayList<SyncJobRunner.Page<L2IntradayBarFeatures>>();
        assertThrows(CancellationException.class,()->consume(List.of(header(),page(),completion()),pages,()->true));assertTrue(pages.isEmpty());
    }

    private SyncJobRunner.SourceCompletion consume(List<JsonNode> lines,List<SyncJobRunner.Page<L2IntradayBarFeatures>> pages,BooleanSupplier cancelled)throws Exception{
        Path output=temp.resolve("input.jsonl");Files.writeString(output,String.join("\n",lines.stream().map(JsonNode::toString).toList())+"\n");
        var source=new L2IntradayBarFeaturesParquetSource(temp,temp.resolve("unlaunched.py"),"unlaunched-python");
        var method=L2IntradayBarFeaturesParquetSource.class.getDeclaredMethod("consumeOutput",Path.class,L2IntradayBarFeaturesParquetSource.Inspection.class,List.class,SyncJobRunner.PageConsumer.class,BooleanSupplier.class);
        method.setAccessible(true);
        try{return (SyncJobRunner.SourceCompletion)method.invoke(source,output,inspection(),List.of("000001.SZ","600001.SH"),(SyncJobRunner.PageConsumer<L2IntradayBarFeatures>)pages::add,cancelled);}
        catch(InvocationTargetException invocation){if(invocation.getCause() instanceof Exception failure)throw failure;throw (Error)invocation.getCause();}
    }
    private ObjectNode header(){
        var header=JSON.createObjectNode();header.put("kind","header");header.put("from","20260921");header.put("to","20260921");
        header.putArray("dates").add("20260921");header.put("sourceRows",2);header.put("selectedRows",2);header.put("files",1);header.put("pages",1);header.put("sourceBytes",100);
        header.put("sourceFingerprint",FINGERPRINT);header.put("schemaFingerprint","b".repeat(64));header.put("parserVersion","l2-intraday-bar-features-parquet-v1");
        header.put("rootIdentity","e".repeat(64));header.put("completeForSelectedSymbols",true);return header;
    }
    private ObjectNode page()throws Exception{
        var page=JSON.createObjectNode();page.put("kind","page");page.put("sourceFingerprint",FINGERPRINT);page.put("cursor",CURSOR);page.set("rows",sourceRows());
        var evidence=page.putObject("responseEvidence");evidence.put("date","20260921");evidence.put("batchId",3);evidence.put("sourceRowOffset",0);evidence.put("page",0);
        evidence.put("manifestReceiptFingerprint","c".repeat(64));var part=evidence.putArray("featureParts").addObject();part.put("path","l2_intraday_bar_features/20260921/part.parquet");part.put("sha256","d".repeat(64));return page;
    }
    private ObjectNode completion(){
        var completion=JSON.createObjectNode();completion.put("kind","completion");completion.put("complete",true);completion.put("completeForSelectedSymbols",true);
        completion.put("sourceFingerprint",FINGERPRINT);completion.put("files",1);completion.put("sourceRows",2);completion.put("returnedRows",2);completion.put("pages",1);return completion;
    }
}
