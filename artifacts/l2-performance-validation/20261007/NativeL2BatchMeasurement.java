import com.zoutrankil.batch.l2.L2DailyFeatureBatchCli;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Diagnostic wrapper around the real resumable batch entry, including file hashing and commits. */
public final class NativeL2BatchMeasurement {
    public static void main(String[] args) throws Exception {
        if(args.length<2||!args[0].equals("--measurement-output"))throw new IllegalArgumentException("First option is --measurement-output path");
        Path metrics=Path.of(args[1]);
        String[] nativeArgs=Arrays.copyOfRange(args,2,args.length);
        var sampler=new NativeL2Benchmark.MemorySampler();
        var gcBefore=NativeL2Benchmark.gc();
        long cpuBefore=NativeL2Benchmark.OS.getProcessCpuTime(),wallBefore=System.nanoTime();
        Exception failure=null;
        sampler.start();
        try {L2DailyFeatureBatchCli.main(nativeArgs);}
        catch(Exception error){failure=error;}
        finally {sampler.close();}
        var gcAfter=NativeL2Benchmark.gc();
        var result=new LinkedHashMap<String,Object>();
        result.put("recordedAt",Instant.now().toString());
        result.put("status",failure==null?"SUCCESS":"FAILED");
        result.put("error",failure==null?null:failure.toString());
        result.put("wallSeconds",(System.nanoTime()-wallBefore)/1e9);
        result.put("processCpuSeconds",(NativeL2Benchmark.OS.getProcessCpuTime()-cpuBefore)/1e9);
        result.put("gcCount",gcAfter.count()-gcBefore.count());
        result.put("gcMillis",gcAfter.millis()-gcBefore.millis());
        result.put("heapPeakBytes",sampler.heapPeak.get());
        result.put("rssPeakBytes",sampler.rssPeak.get());
        result.put("jvmLifetimeRssPeakBytes",sampler.lifetimeRssPeak.get());
        result.put("heapMaxBytes",NativeL2Benchmark.MEMORY.getHeapMemoryUsage().getMax());
        result.put("jvmArgs",ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("nativeArgs",nativeArgs);
        result.put("includes",List.of("CSV parsing","P0-P13","input SHA256 before and after computation","per-symbol atomic fsync checkpoint","daily manifest and ordered JSONL aggregation"));
        Files.createDirectories(metrics.toAbsolutePath().getParent());
        Files.writeString(metrics,NativeL2Benchmark.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardCharsets.UTF_8);
        System.out.println("MEASURED "+NativeL2Benchmark.JSON.writeValueAsString(result));
        if(failure!=null)throw failure;
    }
}
