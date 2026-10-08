package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.service.SyncJobRunner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.zoutrankil.data.l2.application.L2EventAndT0Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class L2EventAndT0ParquetProtocolTest {
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(Family.class)
    void genuineParserConsumesSyntheticProtocolAndDoesNotReuseCallState(Family family) throws Exception {
        var source=family.source(directory,directory.resolve("unused.py"),"unused-python");Object expected=family.inspection(1,1);
        Path file=write(List.of(header(family),page(family),completion()));
        for(int pass=0;pass<2;pass++) {
            var pages=new ArrayList<SyncJobRunner.Page<Object>>();
            var done=consume(family,source,file,expected,pages,()->false);
            assertTrue(done.complete());assertEquals(1,done.pages());assertEquals(1,done.rows());
            assertEquals(1,pages.size());assertEquals(family.row(family.input()),pages.getFirst().rows().getFirst());
            assertEquals(family.pageFingerprint(pages.getFirst().rows()),pages.getFirst().sourceFingerprint());
            assertEquals("20260921:1:0",pages.getFirst().cursor());
            assertEquals(page(family).path("responseEvidence"),JSON.readTree(pages.getFirst().responseEvidence()));
        }
        Files.delete(file); // reader has closed even though the same source instance was reused.
    }

    @TestFactory List<DynamicTest> damagedProtocolFailsAtOriginalBoundary() {
        var tests=new ArrayList<DynamicTest>();
        for(Family family:Family.values()) for(String fault:List.of("header","fingerprint","receipt","partPath","partHash","duplicateKey","duplicateCursor","completion","trailing")) {
            tests.add(DynamicTest.dynamicTest(family+"/"+fault,()->{
                var head=header(family);var page=page(family);var end=completion();
                var records=new ArrayList<JsonNode>(List.of(head,page,end));int consumed=0;
                switch(fault) {
                    case "header" -> head.put("rootIdentity","d".repeat(64));
                    case "fingerprint" -> page.put("sourceFingerprint","d".repeat(64));
                    case "receipt" -> ((ObjectNode)page.path("responseEvidence")).put("manifestReceiptFingerprint","short");
                    case "partPath" -> ((ObjectNode)page.path("responseEvidence").path("featureParts").get(0)).put("path",family.dataset+"/../escape");
                    case "partHash" -> ((ObjectNode)page.path("responseEvidence").path("featureParts").get(0)).put("sha256","short");
                    case "duplicateKey" -> { var again=page.deepCopy();again.put("cursor","20260921:1:1");((ObjectNode)again.path("responseEvidence")).put("sourceRowOffset",1);records.add(2,again);consumed=1; }
                    case "duplicateCursor" -> { records.add(2,page.deepCopy());consumed=1; }
                    case "completion" -> { end.put("returnedRows",2);consumed=1; }
                    case "trailing" -> { records.add(JSON.createObjectNode());consumed=1; }
                }
                Path file=write(records);var pages=new ArrayList<SyncJobRunner.Page<Object>>();
                assertThrows(IOException.class,()->consume(family,family.source(directory,directory.resolve("unused"),"unused"),file,family.inspection(1,1),pages,()->false));
                assertEquals(consumed,pages.size());Files.delete(file);
            }));
        }
        return tests;
    }

    @ParameterizedTest @EnumSource(Family.class)
    void parserCancellationStopsBeforeFirstCallbackAndRetainsInterrupt(Family family) throws Exception {
        Path file=write(List.of(header(family),page(family),completion()));var pages=new ArrayList<SyncJobRunner.Page<Object>>();Object source=family.source(directory,directory.resolve("none"),"unused");
        assertThrows(CancellationException.class,()->consume(family,source,file,family.inspection(1,1),pages,()->true));assertTrue(pages.isEmpty());
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class,()->consume(family,source,file,family.inspection(1,1),pages,()->false));assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertTrue(pages.isEmpty());Files.delete(file);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void commandKeepsArgumentOrderAndOriginalSourceBudgetWithoutLaunching(Family family) throws Exception {
        Path root=Files.createDirectory(directory.resolve(family.code));Path helper=Files.writeString(directory.resolve(family.code+".py"),"not executed");Path python=Files.writeString(directory.resolve(family.code+".exe"),"not executed");
        Object source=family.source(root,helper,python.toString());
        var command=call(family.sourceType(),source,"command",new Class<?>[]{java.time.LocalDate.class,java.time.LocalDate.class,List.class,int.class,int.class,String.class,String.class},DAY,DAY,List.of(SYMBOL),300000,10000,"--stream",SOURCE);
        assertEquals(List.of(python.toString(),helper.toString(),"--dataset-root",root.toString(),"--from-date","20260921","--to-date","20260921","--page-rows","200","--max-files","10000","--max-rows","300000","--max-bytes","67108864","--max-output-bytes","67108864","--symbol",SYMBOL,"--stream","--expected-fingerprint",SOURCE),command);
        String identity=(String)call(family.sourceType(),source,"sourceRootIdentity",new Class<?>[0]);
        assertEquals(com.zoutrankil.data.repository.FileEvidenceStore.sha256(root.toRealPath().toString().getBytes(StandardCharsets.UTF_8)),identity);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void processTransportFiltersEnvironmentClosesStdinAndSanitizesFailure(Family family) throws Exception {
        Path out=Files.writeString(directory.resolve(family.code+".out"),"");Path err=Files.writeString(directory.resolve(family.code+".err"),"ValueError: private path and payload\nsecret next line");
        var process=mock(Process.class);when(process.waitFor(200,TimeUnit.MILLISECONDS)).thenReturn(true);when(process.exitValue()).thenReturn(1);when(process.isAlive()).thenReturn(false);
        var closes=new AtomicInteger();when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream(){@Override public void close(){closes.incrementAndGet();}});
        var env=new HashMap<>(Map.of("QUESTDB_HOST","secret","APP_QUESTDB_PASSWORD","secret","PREFIX_TUSHARE_TOKEN","secret","PATH","safe"));
        try(var builders=mockConstruction(ProcessBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->{assertEquals(List.of("fake-command"),context.arguments().getFirst());when(builder.environment()).thenReturn(env);when(builder.start()).thenReturn(process);})) {
            var failure=assertThrows(IOException.class,()->runProcess(family,out,err,process,()->false));assertEquals(family.code+" Parquet reader failed: ValueError",failure.getMessage());
            assertEquals(Map.of("PATH","safe"),env);assertEquals(2,closes.get());assertEquals(1,builders.constructed().size());
            verify(builders.constructed().getFirst()).redirectOutput(out.toFile());verify(builders.constructed().getFirst()).redirectError(err.toFile());verify(process,never()).destroy();
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void processCancellationDestroysAndForcesOnlyTheStillAliveProcess(Family family) throws Exception {
        Path out=Files.writeString(directory.resolve(family.code+"-cancel.out"),"");Path err=Files.writeString(directory.resolve(family.code+"-cancel.err"),"");var process=mock(Process.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());when(process.waitFor(200,TimeUnit.MILLISECONDS)).thenReturn(false);when(process.isAlive()).thenReturn(true);when(process.waitFor(2,TimeUnit.SECONDS)).thenReturn(false,true);
        try(var builders=mockConstruction(ProcessBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->{when(builder.environment()).thenReturn(new HashMap<>());when(builder.start()).thenReturn(process);})) {
            assertThrows(CancellationException.class,()->runProcess(family,out,err,process,()->true));
            var order=inOrder(process);order.verify(process).destroy();order.verify(process).waitFor(2,TimeUnit.SECONDS);order.verify(process).destroyForcibly();order.verify(process).waitFor(2,TimeUnit.SECONDS);
        }
        when(process.waitFor(2,TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try { call(family.sourceType(),null,"terminate",new Class<?>[]{Process.class},process);assertTrue(Thread.currentThread().isInterrupted()); }
        finally {Thread.interrupted();}
    }

    @Test void t0InspectionCopiesBothMapLevelsAndPreservesAllSixHorizonOrders() throws Exception {
        var coverage=coverage(1);var expected=(L2T0TrainingLabelsParquetSource.Inspection)Family.T0.inspection(1,1);
        var actual=new L2T0TrainingLabelsParquetSource.Inspection(DAY,DAY,new ArrayList<>(List.of(DAY)),1,1,1,1,1,SOURCE,SCHEMA,"l2-t0-training-labels-parquet-v1",ROOT,true,coverage);
        coverage.get("1m").put("rows",0L);coverage.remove("30m");assertEquals(expected,actual);
        assertEquals(HORIZONS,new ArrayList<>(actual.outcomeCoverageByHorizon().keySet()));
        assertThrows(UnsupportedOperationException.class,()->actual.dates().clear());
        assertThrows(UnsupportedOperationException.class,()->actual.outcomeCoverageByHorizon().clear());
        assertThrows(UnsupportedOperationException.class,()->actual.outcomeCoverageByHorizon().get("1m").put("rows",0L));
        for(String horizon:HORIZONS) {
            var head=header(Family.T0);((ObjectNode)head.path("outcomeCoverageByHorizon").path(horizon)).put("rows",-1);
            assertThrows(IOException.class,()->call(Family.T0.sourceType(),null,"decodeInspection",new Class<?>[]{JsonNode.class,String.class},head,"header"));
        }
    }

    private static ObjectNode header(Family family) {
        ObjectNode node=JSON.valueToTree(family.inspection(1,1));node.put("kind","header");node.put("from","20260921");node.put("to","20260921");node.set("dates",JSON.valueToTree(List.of("20260921")));return node;
    }
    private static ObjectNode page(Family family) throws Exception {
        var receipt=JSON.createObjectNode().put("date","20260921").put("batchId",1).put("sourceRowOffset",0).put("page",0).put("manifestReceiptFingerprint","d".repeat(64));
        receipt.putArray("featureParts").addObject().put("path",family.dataset+"/20260921/part.parquet").put("sha256","e".repeat(64));
        var page=JSON.createObjectNode().put("kind","page").put("sourceFingerprint",SOURCE).put("cursor","20260921:1:0");page.set("responseEvidence",receipt);page.putArray("rows").add(family.input());return page;
    }
    private static ObjectNode completion() { return JSON.createObjectNode().put("kind","completion").put("sourceFingerprint",SOURCE).put("complete",true).put("completeForSelectedSymbols",true).put("files",1).put("sourceRows",1).put("returnedRows",1).put("pages",1); }
    private Path write(List<? extends JsonNode> records) throws Exception {Path file=Files.createTempFile(directory,"protocol-",".jsonl");var text=new StringBuilder();for(var record:records)text.append(JSON.writeValueAsString(record)).append('\n');return Files.writeString(file,text);}
    @SuppressWarnings("unchecked") private static SyncJobRunner.SourceCompletion consume(Family family,Object source,Path file,Object expected,List<SyncJobRunner.Page<Object>> pages,BooleanSupplier cancelled) throws Exception {
        return (SyncJobRunner.SourceCompletion)call(family.sourceType(),source,"consumeOutput",new Class<?>[]{Path.class,expected.getClass(),List.class,SyncJobRunner.PageConsumer.class,BooleanSupplier.class},file,expected,List.of(SYMBOL),(SyncJobRunner.PageConsumer<Object>)pages::add,cancelled);
    }
    private static void runProcess(Family family,Path out,Path err,Process process,BooleanSupplier cancelled) throws Exception {call(family.sourceType(),null,"runProcess",new Class<?>[]{List.class,Path.class,Path.class,long.class,Duration.class,BooleanSupplier.class},List.of("fake-command"),out,err,1024L,Duration.ofMinutes(1),cancelled);}
}
