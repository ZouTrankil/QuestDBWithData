package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.batch.l2.L2BatchState.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual source/store interfaces. Controlled blocking proves authority, not an old late-write repro. */
class L2DailyFeatureBatchExecutionTest {
    @TempDir Path directory;
    static final LocalDate DAY=LocalDate.of(2026,9,24);

    @Test void closedGateRejectsResultDeleteAndCheckpointWithoutInvokingMutation() throws Exception {
        var gate=new L2PublicationGate();var calls=new AtomicInteger();gate.close();
        for(String operation:List.of("result","delete","checkpoint"))
            assertThrows(CancellationException.class,()->gate.publish(calls::incrementAndGet),operation);
        assertEquals(0,calls.get());
    }

    @Test void closeSerializesWithTheEntireInFlightPublication() throws Exception {
        var gate=new L2PublicationGate();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicBoolean();
        var failure=new AtomicReference<Throwable>();var values=new CopyOnWriteArrayList<String>();
        Thread publisher=new Thread(()->{try{gate.publish(()->{values.add("result");entered.countDown();await(release);values.add("checkpoint");});}catch(Throwable t){failure.set(t);}});
        Thread closer=new Thread(()->{gate.close();closed.set(true);});
        publisher.start();assertTrue(entered.await(5,TimeUnit.SECONDS));closer.start();
        waitFor(()->closer.getState()==Thread.State.BLOCKED);assertFalse(closed.get());
        release.countDown();publisher.join(5000);closer.join(5000);
        assertNull(failure.get());assertTrue(closed.get());assertEquals(List.of("result","checkpoint"),values);
        assertThrows(CancellationException.class,()->gate.publish(()->values.add("late")));
    }

    @Test void interruptRevokesWorkerPublicationRestoresFlagAndReleasesRootLockAfterCleanup() throws Exception {
        var h=new InterruptedRun(directory,null,false);h.run();
        assertInstanceOf(InterruptedException.class,h.failure.get());assertTrue(h.flag.get());
        assertEquals("sentinel",Files.readString(h.result));assertFalse(Files.exists(h.checkpoint));
        assertEquals("FAILED",new com.fasterxml.jackson.databind.ObjectMapper().readTree(h.output.resolve("20260924/manifest.json").toFile()).path("status").asText());
        try(var lock=new L2FileCheckpointStore().lock(h.output)){assertNotNull(lock);}
    }

    @Test void bookkeepingIoFailureRemainsSuppressedOnTheOriginalInterrupt() throws Exception {
        var h=new InterruptedRun(directory,"append",false);h.run();
        var interrupted=assertInstanceOf(InterruptedException.class,h.failure.get());assertTrue(h.flag.get());
        assertTrue(Arrays.stream(interrupted.getSuppressed()).anyMatch(t->t instanceof IOException&&t.getMessage().equals("append fault")));
        assertEquals("sentinel",Files.readString(h.result));assertFalse(Files.exists(h.checkpoint));
    }

    @Test void finalManifestIoFailureDoesNotLoseInterruptAuthority() throws Exception {
        var h=new InterruptedRun(directory,"manifest",false);h.run();
        var interrupted=assertInstanceOf(InterruptedException.class,h.failure.get());assertTrue(h.flag.get());
        assertTrue(Arrays.stream(interrupted.getSuppressed()).anyMatch(t->t instanceof IOException&&t.getMessage().equals("manifest fault")));
    }

    @Test void secondInterruptDuringCleanupIsRetainedAndDoesNotReopenPublication() throws Exception {
        var h=new InterruptedRun(directory,null,true);h.run();
        var interrupted=assertInstanceOf(InterruptedException.class,h.failure.get());assertTrue(h.flag.get());
        assertTrue(Arrays.stream(interrupted.getSuppressed()).anyMatch(t->t instanceof InterruptedException));
        assertEquals("sentinel",Files.readString(h.result));assertFalse(Files.exists(h.checkpoint));
    }

    @Test void outputLockExcludesAnotherOwnerAndCanBeReopened() throws Exception {
        var store=new L2FileCheckpointStore();store.createDirectory(directory);
        try(var lock=store.lock(directory)){assertThrows(OverlappingFileLockException.class,()->store.lock(directory));}
        try(var reopened=store.lock(directory)){assertNotNull(reopened);}
    }

    @Test void directTypedSingleRequestCannotBypassTheOriginalSymbolAndBudgetRules() {
        for(List<String> symbols:List.of(List.<String>of(),List.of("../outside"),List.of("510300.SZ","510300.SH"),List.of("159915.SZ","159915.sz")))
            assertThrows(IllegalArgumentException.class,()->new L2DailyFeatureSingleRequest(directory,DAY,symbols,directory.resolve("out"),1));
        assertThrows(IllegalArgumentException.class,()->new L2DailyFeatureSingleRequest(directory,DAY,List.of("159915.SZ"),directory.resolve("out"),0));
        var input=new ArrayList<>(List.of("510300.SZ","159915.SZ"));var request=new L2DailyFeatureSingleRequest(directory,DAY,input,directory.resolve("out"),1);
        input.clear();assertEquals(List.of("510300.SZ","159915.SZ"),request.symbols());
    }

    @Test void explicitFingerprintClosureIncludesEveryNewImplementationAndNestedPublicationTypes() throws Exception {
        var collect=L2ComputationFingerprint.class.getDeclaredMethod("collectClasses",Class.class,Map.class);collect.setAccessible(true);
        var types=List.of(L2DailyFeatureBatchService.class,L2DailyFeatureSingleService.class,L2DailyFeatureBatchRequest.class,L2DailyFeatureSingleRequest.class,
                L2DailyFeatureCli.class,L2DailyFeatureBatchCli.class,L2DailyFeatureSource.class,L2DailyFeatureFileSource.class,L2CheckpointStore.class,
                L2FileCheckpointStore.class,L2BatchState.class,L2FeatureFiles.class,L2BatchExecutionSession.class,L2PublicationGate.class,L2ComputationFingerprint.class);
        var expected=new TreeMap<String,Class<?>>();for(var type:types)collect.invoke(null,type,expected);
        assertTrue(expected.containsKey("com.zoutrankil.batch.l2.L2PublicationGate$Publication"));
        assertTrue(expected.containsKey("com.zoutrankil.batch.l2.L2FileCheckpointStore$Log"));
        String source=Files.readString(Path.of(System.getProperty("l2.preview.source-root","src/main/java")).resolve("com/zoutrankil/batch/l2/L2ComputationFingerprint.java"));
        for(var type:types)assertTrue(source.contains(type.getSimpleName()+".class"),type.getName());
        assertEquals(L2ComputationFingerprint.computeFingerprint(),L2ComputationFingerprint.computeFingerprint());
    }

    @Test void realOldClassCheckpointIsInvalidatedThenNewCheckpointIsReused() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode old;try(var in=getClass().getResourceAsStream("/l2-native-batch/original-checkpoints.json")){old=json.readTree(Objects.requireNonNull(in));}
        Path source=directory.resolve("input/20260924"),output=directory.resolve("output"),dayOutput=output.resolve("20260924");
        Files.createDirectories(dayOutput.resolve("state"));Files.createDirectories(dayOutput.resolve("features"));
        for(String symbol:List.of("159915.SZ","510300.SZ")) {
            Files.createDirectories(source.resolve(symbol));
            for(String file:List.of("逐笔成交.csv","逐笔委托.csv","行情.csv"))try(var in=getClass().getResourceAsStream("/l2-daily-pipeline/20260924/"+symbol+"/"+file)) {
                Files.copy(Objects.requireNonNull(in),source.resolve(symbol).resolve(file));
            }
        }
        var fields=old.path("checkpoints").fields();while(fields.hasNext()) {
            var entry=fields.next();Files.writeString(dayOutput.resolve("state").resolve(entry.getKey()),json.writeValueAsString(entry.getValue()));
            try(var in=getClass().getResourceAsStream("/l2-native-batch/features/"+entry.getKey())){Files.copy(Objects.requireNonNull(in),dayOutput.resolve("features").resolve(entry.getKey()));}
        }
        var actual=new L2DailyFeatureFileSource();var parses=new AtomicInteger();
        L2DailyFeatureSource counting=new L2DailyFeatureSource(){
            public List<Job> discover(Path p,List<String> ignored)throws IOException{return actual.discover(p,ignored);}
            public SourceFingerprint fingerprint(Path p,long max)throws IOException{return actual.fingerprint(p,max);}
            public DfcfCsvParser.ProductionDay parse(Path p,String symbol,LocalDate date,long max)throws IOException{parses.incrementAndGet();return actual.parse(p,symbol,date,max);}
        };
        var service=new L2DailyFeatureBatchService(counting,new L2FileCheckpointStore());
        var request=new L2DailyFeatureBatchRequest(source,List.of(DAY),output,2,1000000,true,null);
        service.execute(request);assertEquals(2,parses.get());
        var first=json.readTree(dayOutput.resolve("manifest.json").toFile());assertEquals(0,first.path("counts").path("resumed").asInt());
        assertNotEquals(old.path("computeFingerprint").asText(),first.path("computeFingerprint").asText());
        assertEquals(old.path("aggregateSha256").asText(),L2FeatureFiles.sha256(dayOutput.resolve("l2_daily_features.jsonl")));
        service.execute(request);assertEquals(2,parses.get());
        assertEquals(2,json.readTree(dayOutput.resolve("manifest.json").toFile()).path("counts").path("resumed").asInt());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("controlled publication timeout");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
    }
    private static void waitFor(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!ready.getAsBoolean()){if(System.nanoTime()>deadline)fail("controlled state was not reached");Thread.sleep(1);}
    }
    private static final class InterruptedRun {
        final Path output,result,checkpoint;final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicBoolean flag=new AtomicBoolean();
        final CountDownLatch parsing=new CountDownLatch(1),workerInterrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        final Thread coordinator;final boolean second;
        InterruptedRun(Path base,String fault,boolean second) throws Exception {
            this.second=second;Path root=base.resolve("input");Files.createDirectories(root.resolve("20260924/000001.SZ"));
            output=base.resolve("output");result=output.resolve("20260924/features/000001.SZ.json");checkpoint=output.resolve("20260924/state/000001.SZ.json");
            Files.createDirectories(result.getParent());Files.writeString(result,"sentinel");
            var physicalSource=new L2DailyFeatureFileSource();
            L2DailyFeatureSource source=new L2DailyFeatureSource(){
                public List<Job> discover(Path p,List<String> ignored)throws IOException{return physicalSource.discover(p,ignored);}
                public SourceFingerprint fingerprint(Path p,long max)throws IOException{return physicalSource.fingerprint(p,max);}
                public DfcfCsvParser.ProductionDay parse(Path p,String symbol,LocalDate date,long max)throws IOException{
                    parsing.countDown();boolean done=false;
                    while(!done)try{done=release.await(5,TimeUnit.SECONDS);if(!done)throw new IOException("controlled source timeout");}
                    catch(InterruptedException ignored){workerInterrupted.countDown();}
                    return DfcfCsvParser.normalizeProductionDay(symbol,date,List.of(),List.of(),List.of());
                }
            };
            L2CheckpointStore actual=new L2FileCheckpointStore();
            L2CheckpointStore store=(L2CheckpointStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{L2CheckpointStore.class},(proxy,method,args)->{
                if("append".equals(fault)&&method.getName().equals("append"))throw new IOException("append fault");
                if("manifest".equals(fault)&&method.getName().equals("json")&&args[1] instanceof Map<?,?> map&&"FAILED".equals(map.get("status")))throw new IOException("manifest fault");
                try{return method.invoke(actual,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
            var service=new L2DailyFeatureBatchService(source,store);var request=new L2DailyFeatureBatchRequest(root,List.of(DAY),output,1,1000000,false,null);
            coordinator=new Thread(()->{try{service.execute(request);}catch(Throwable t){failure.set(t);}finally{flag.set(Thread.currentThread().isInterrupted());}},"controlled-l2-coordinator");
        }
        void run() throws Exception {
            coordinator.start();
            try {
                assertTrue(parsing.await(5,TimeUnit.SECONDS));
                waitFor(()->Arrays.stream(coordinator.getStackTrace()).anyMatch(s->s.getClassName().equals("java.util.concurrent.ExecutorCompletionService")&&s.getMethodName().equals("take")));
                coordinator.interrupt();assertTrue(workerInterrupted.await(5,TimeUnit.SECONDS));
                assertThrows(OverlappingFileLockException.class,()->new L2FileCheckpointStore().lock(output));
                if(second){waitFor(()->Arrays.stream(coordinator.getStackTrace()).anyMatch(s->s.getClassName().equals("java.util.concurrent.ThreadPoolExecutor")&&s.getMethodName().equals("awaitTermination")));coordinator.interrupt();}
            }finally{release.countDown();coordinator.join(10000);}
            assertFalse(coordinator.isAlive());
        }
    }
}
