import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.batch.l2.*;
import com.zoutrankil.data.domain.*;
import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Diagnostic-only benchmark. No database writes, no retained parsed days between jobs. */
public final class NativeL2Benchmark {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ThreadMXBean THREAD = ManagementFactory.getThreadMXBean();
    static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    static final List<GarbageCollectorMXBean> GC = ManagementFactory.getGarbageCollectorMXBeans();
    static final com.sun.management.OperatingSystemMXBean OS =
            (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
    static final com.sun.management.ThreadMXBean ALLOCATION =
            THREAD instanceof com.sun.management.ThreadMXBean t ? t : null;
    static final WindowsMemory WINDOWS = WindowsMemory.create();
    static volatile long blackhole;

    record Job(String sourceSymbol, LocalDate date, Path source, long bytes) {}
    record Metric(String stage, double wallMs, double cpuMs, long allocatedBytes) {}
    record GcSnapshot(long count, long millis) {}
    record Stamp(long wall, long cpu, long allocated) {}
    record TaskResult(Map<String,Object> metrics, String dailyJson, List<Metric> stages) {}
    @FunctionalInterface interface CheckedSupplier<T> { T get() throws Exception; }

    public static void main(String[] args) throws Exception {
        Map<String,String> opts = options(args);
        if (!Set.of("--source-root","--date","--dates","--symbols","--jobs-file","--workers","--warmup",
                "--rounds","--output-dir","--max-bytes-per-file","--stage-profile","--jfr")
                .containsAll(opts.keySet())) throw new IllegalArgumentException("Unknown benchmark option");
        if (opts.containsKey("--jobs-file") && (opts.containsKey("--date") || opts.containsKey("--dates") || opts.containsKey("--symbols")))
            throw new IllegalArgumentException("--jobs-file cannot be combined with --date, --dates or --symbols");
        if (!opts.containsKey("--jobs-file") && opts.containsKey("--date") == opts.containsKey("--dates"))
            throw new IllegalArgumentException("Provide --jobs-file or exactly one of --date / --dates");
        int workers = integer(opts,"--workers",1,1,8), warmup=integer(opts,"--warmup",1,0,100),
                rounds=integer(opts,"--rounds",3,1,100);
        boolean stages = switch(opts.getOrDefault("--stage-profile","false")) {
            case "true" -> true; case "false" -> false;
            default -> throw new IllegalArgumentException("--stage-profile is true or false");
        };
        long maxBytes = Long.parseLong(opts.getOrDefault("--max-bytes-per-file","536870912"));
        if(maxBytes<1)throw new IllegalArgumentException("Positive source byte limit required");
        Path root = Path.of(required(opts,"--source-root")).toAbsolutePath().normalize();
        Path out = Path.of(required(opts,"--output-dir")).toAbsolutePath().normalize();
        List<Job> jobs = jobs(opts,root);
        Files.createDirectories(out);
        if(THREAD.isThreadCpuTimeSupported() && !THREAD.isThreadCpuTimeEnabled())THREAD.setThreadCpuTimeEnabled(true);
        if(ALLOCATION!=null && ALLOCATION.isThreadAllocatedMemorySupported() &&
                !ALLOCATION.isThreadAllocatedMemoryEnabled())ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        Recording recording=null;
        if(opts.containsKey("--jfr")) {
            Path jfr=Path.of(opts.get("--jfr")).toAbsolutePath().normalize();
            if(jfr.getParent()!=null)Files.createDirectories(jfr.getParent());
            recording=new Recording(Configuration.getConfiguration("profile"));
            recording.setDestination(jfr); recording.setDumpOnExit(true); recording.start();
        }
        ExecutorService executor=Executors.newFixedThreadPool(workers);
        List<TaskResult> all=new ArrayList<>();
        List<Map<String,Object>> roundReports=new ArrayList<>();
        try {
            for(int r=0;r<warmup+rounds;r++) {
                String phase=r<warmup?"warmup":"measure"; int ordinal=r<warmup?r+1:r-warmup+1;
                for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
                GcSnapshot gcBefore=gc(); long cpuBefore=OS.getProcessCpuTime();
                long started=System.nanoTime();
                MemorySampler sampler=new MemorySampler(); sampler.start();
                try {
                    ExecutorCompletionService<TaskResult> completion=new ExecutorCompletionService<>(executor);
                    for(Job job:jobs)completion.submit(()->run(job,phase,ordinal,maxBytes,stages));
                    for(int i=0;i<jobs.size();i++) {
                        TaskResult result=completion.take().get(); all.add(result);
                        System.out.printf(Locale.ROOT,"%s round=%d %s %s parse=%.3fs feature=%.3fs total=%.3fs sha256=%s%n",
                                phase,ordinal,result.metrics.get("date"),result.metrics.get("sourceSymbol"),
                                ((Number)result.metrics.get("parseWallMs")).doubleValue()/1000,
                                ((Number)result.metrics.get("featureWallMs")).doubleValue()/1000,
                                ((Number)result.metrics.get("wallMs")).doubleValue()/1000,
                                result.metrics.get("sha256"));
                    }
                } finally { sampler.close(); }
                long elapsed=System.nanoTime()-started; GcSnapshot gcAfter=gc();
                Map<String,Object> round=new LinkedHashMap<>();
                round.put("phase",phase);round.put("round",ordinal);round.put("jobs",jobs.size());
                round.put("wallMs",elapsed/1e6);round.put("processCpuMs",(OS.getProcessCpuTime()-cpuBefore)/1e6);
                round.put("gcCount",gcAfter.count-gcBefore.count);round.put("gcMillis",gcAfter.millis-gcBefore.millis);
                round.put("symbolsPerSecond",jobs.size()/(elapsed/1e9));
                round.put("sourceMiBPerSecond",jobs.stream().mapToLong(Job::bytes).sum()/1048576.0/(elapsed/1e9));
                round.put("sampledHeapPeakBytes",sampler.heapPeak.get());
                round.put("sampledRssPeakBytes",nullable(sampler.rssPeak.get()));
                round.put("windowsPeakWorkingSetJvmLifetimeBytes",nullable(sampler.lifetimeRssPeak.get()));
                round.put("memoryPoolPeaks",poolPeaks());roundReports.add(round);
                System.out.printf(Locale.ROOT,"ROUND %s %d workers=%d jobs=%d wall=%.3fs throughput=%.3f symbols/s heapPeak=%.1f MiB rssPeak=%.1f MiB GC=%d/%dms%n",
                        phase,ordinal,workers,jobs.size(),elapsed/1e9,jobs.size()/(elapsed/1e9),
                        sampler.heapPeak.get()/1048576.0,sampler.rssPeak.get()/1048576.0,
                        gcAfter.count-gcBefore.count,gcAfter.millis-gcBefore.millis);
                writeOutputs(out,opts,workers,jobs,all,roundReports,recording!=null);
            }
        } finally {
            executor.shutdownNow(); executor.awaitTermination(30,TimeUnit.SECONDS);
            if(recording!=null){recording.stop();recording.close();}
        }
        System.out.println("RESULTS "+out);
    }

    static TaskResult run(Job job,String phase,int round,long maxBytes,boolean profile) throws Exception {
        List<Metric> stages=new ArrayList<>(); GcSnapshot gcBefore=gc();
        long heapBefore=MEMORY.getHeapMemoryUsage().getUsed();Stamp start=stamp();
        DfcfCsvParser.ProductionDay parsed=DfcfCsvParser.readProductionDay(job.source,job.sourceSymbol,job.date,maxBytes);
        Stamp parsedAt=stamp();
        L2DailyFeatures row=profile?profile(parsed,stages):L2DailyFeaturePipeline.compute(parsed);
        Stamp featuredAt=stamp();
        String json=JSON.writeValueAsString(L2DailyFeaturePipeline.output(row));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        blackhole^=hash.hashCode();Stamp finish=stamp();GcSnapshot gcAfter=gc();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("phase",phase);result.put("round",round);result.put("date",job.date.toString().replace("-",""));
        result.put("sourceSymbol",job.sourceSymbol);result.put("symbol",row.symbol());result.put("sourceBytes",job.bytes);
        result.put("rawDealRows",parsed.rawDealRows());result.put("rawOrderRows",parsed.rawOrderRows());
        result.put("rawQuoteRows",parsed.rawQuoteRows());result.put("deals",parsed.deals().size());
        result.put("orders",parsed.orders().size());result.put("quotes",parsed.quotes().size());
        result.put("wallMs",(finish.wall-start.wall)/1e6);result.put("cpuMs",deltaMs(start.cpu,finish.cpu));
        result.put("parseWallMs",(parsedAt.wall-start.wall)/1e6);result.put("parseCpuMs",deltaMs(start.cpu,parsedAt.cpu));
        result.put("featureWallMs",(featuredAt.wall-parsedAt.wall)/1e6);result.put("featureCpuMs",deltaMs(parsedAt.cpu,featuredAt.cpu));
        result.put("outputWallMs",(finish.wall-featuredAt.wall)/1e6);result.put("outputCpuMs",deltaMs(featuredAt.cpu,finish.cpu));
        result.put("allocatedBytes",delta(start.allocated,finish.allocated));
        result.put("parseAllocatedBytes",delta(start.allocated,parsedAt.allocated));
        result.put("featureAllocatedBytes",delta(parsedAt.allocated,featuredAt.allocated));
        result.put("heapBeforeBytes",heapBefore);result.put("heapAfterBytes",MEMORY.getHeapMemoryUsage().getUsed());
        result.put("jvmGcCountDuringTask",gcAfter.count-gcBefore.count);
        result.put("jvmGcMillisDuringTask",gcAfter.millis-gcBefore.millis);result.put("sha256",hash);
        result.put("fieldCount",L2DailyFeaturePipeline.output(row).size());
        return new TaskResult(result,json,List.copyOf(stages));
    }

    /** Same native public builders, order, merge and typed normalization as production compute. */
    static L2DailyFeatures profile(DfcfCsvParser.ProductionDay day,List<Metric> stages) throws Exception {
        L2FeatureData data=measure("featureData",stages,()->L2DailyFeaturePipeline.featureData(day));
        L2WideFeatures.Result wide=measure("P0_P1_P2_P4_P5_wide",stages,()->L2WideFeatures.compute(data));
        Map<String,Object> features=new LinkedHashMap<>(wide.features());
        merge(features,measure("P3_spoofing",stages,()->L2SpoofingFeatures.compute(data)));
        merge(features,measure("P9_quality",stages,()->L2QualityFeatures.compute(data,wide.wide())));
        merge(features,measure("P10_lifecycle",stages,()->L2LifecycleFeatures.compute(data)));
        merge(features,measure("P11_intraday",stages,()->L2IntradayFeatures.compute(data)));
        merge(features,measure("P12_trade_sign",stages,()->L2TradeSignFeatures.compute(data)));
        merge(features,measure("P13_lob_transition",stages,()->L2LobTransitionFeatures.compute(data)));
        merge(features,measure("P6_microstructure",stages,()->L2MicrostructureFeatures.compute(data)));
        merge(features,measure("P7_gmm",stages,()->L2GmmFeatures.compute(data)));
        merge(features,measure("P8_intensity",stages,()->L2IntensityFeatures.compute(data,wide.wide())));
        return measure("typed_row",stages,()->{
            features.put("feature_version",L2DailyFeaturePipeline.FEATURE_VERSION);
            features.put("parser_version",L2DailyFeaturePipeline.PARSER_VERSION);
            EnumMap<L2DailyFeatureField,Object> typed=new EnumMap<>(L2DailyFeatureField.class);
            for(L2DailyFeatureField field:L2DailyFeatureField.values())
                if(!field.identity())typed.put(field,features.remove(field.fieldName()));
            if(!features.isEmpty())throw new IOException("Unknown daily feature fields: "+features.keySet());
            return new L2DailyFeatures(day.tradeDate(),day.symbol(),typed);
        });
    }
    static void merge(Map<String,Object> target,Map<String,Object> values) {
        for(var entry:values.entrySet()) {
            if(target.containsKey(entry.getKey()))throw new IllegalStateException("Duplicate owner: "+entry.getKey());
            target.put(entry.getKey(),entry.getValue());
        }
    }
    static <T>T measure(String stage,List<Metric> out,CheckedSupplier<T> work) throws Exception {
        Stamp before=stamp();T value=work.get();Stamp after=stamp();
        out.add(new Metric(stage,(after.wall-before.wall)/1e6,deltaMs(before.cpu,after.cpu),delta(before.allocated,after.allocated)));
        return value;
    }
    static Stamp stamp() {
        return new Stamp(System.nanoTime(),THREAD.isCurrentThreadCpuTimeSupported()?THREAD.getCurrentThreadCpuTime():-1,
                ALLOCATION!=null && ALLOCATION.isThreadAllocatedMemorySupported()?ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId()):-1);
    }
    static long delta(long before,long after){return before<0 || after<0?-1:after-before;}
    static double deltaMs(long before,long after){long diff=delta(before,after);return diff<0?-1:diff/1e6;}
    static Long nullable(long value){return value<0?null:value;}
    static GcSnapshot gc(){long count=0,time=0;for(var collector:GC){count+=Math.max(0,collector.getCollectionCount());time+=Math.max(0,collector.getCollectionTime());}return new GcSnapshot(count,time);}
    static Map<String,Object> poolPeaks(){Map<String,Object> pools=new LinkedHashMap<>();for(var pool:ManagementFactory.getMemoryPoolMXBeans()){var peak=pool.getPeakUsage();pools.put(pool.getName(),Map.of("type",pool.getType().toString(),"usedBytes",peak.getUsed(),"committedBytes",peak.getCommitted(),"maxBytes",peak.getMax()));}return pools;}

    static List<Job> jobs(Map<String,String> opts,Path root) throws IOException {
        List<Map.Entry<LocalDate,String>> keys=new ArrayList<>();
        if(opts.containsKey("--jobs-file")) {
            var array=JSON.readTree(Path.of(required(opts,"--jobs-file")).toFile());
            if(!array.isArray() || array.isEmpty())throw new IllegalArgumentException("--jobs-file must contain a nonempty JSON array");
            for(var node:array) {
                if(!node.hasNonNull("date") || !node.hasNonNull("symbol"))throw new IllegalArgumentException("Each job requires date and symbol");
                keys.add(Map.entry(date(node.get("date").asText()),node.get("symbol").asText().strip().toUpperCase(Locale.ROOT)));
            }
        } else {
            List<String> symbols=Arrays.stream(required(opts,"--symbols").split(",",-1))
                    .map(String::strip).map(x->x.toUpperCase(Locale.ROOT)).toList();
            if(symbols.isEmpty() || symbols.size()>1000 || new HashSet<>(symbols).size()!=symbols.size())
                throw new IllegalArgumentException("Unique explicit bounded symbol list required");
            List<LocalDate> dates=Arrays.stream(opts.getOrDefault("--date",opts.get("--dates")).split(",",-1)).map(NativeL2Benchmark::date).toList();
            if(dates.isEmpty() || dates.size()>366 || new HashSet<>(dates).size()!=dates.size())
                throw new IllegalArgumentException("Unique bounded dates required");
            for(LocalDate day:dates)for(String symbol:symbols)keys.add(Map.entry(day,symbol));
        }
        if(keys.size()>10000)throw new IllegalArgumentException("Benchmark job count exceeds 10000; use representative samples");
        Set<String> canonical=new HashSet<>();List<Job> jobs=new ArrayList<>();
        for(var key:keys) {
            LocalDate day=key.getKey();String symbol=key.getValue();
            if(!symbol.matches("\\d{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("Invalid symbol: "+symbol);
            if(!canonical.add(day+"/"+DfcfCsvParser.normalizeSymbol(symbol)))throw new IllegalArgumentException("Duplicate canonical job: "+day+"/"+symbol);
            String text=day.toString().replace("-","");
            Path dayRoot=text.equals(String.valueOf(root.getFileName()))?root:root.resolve(text);
            Path source=dayRoot.resolve(symbol);if(!Files.isDirectory(source))throw new IllegalArgumentException("Missing source directory: "+source);
            long bytes=0;for(String name:List.of("逐笔成交.csv","逐笔委托.csv","行情.csv")){Path csv=source.resolve(name);if(Files.isRegularFile(csv))bytes+=Files.size(csv);}
            if(bytes==0)throw new IllegalArgumentException("Empty source directory: "+source);
            jobs.add(new Job(symbol,day,source,bytes));
        }
        return List.copyOf(jobs);
    }

    static final class MemorySampler implements AutoCloseable {
        final AtomicLong heapPeak=new AtomicLong(),rssPeak=new AtomicLong(-1),lifetimeRssPeak=new AtomicLong(-1);
        final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"l2-memory-probe");t.setDaemon(true);return t;});
        void sample(){heapPeak.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(),Math::max);long[] rss=WINDOWS==null?linuxRss():WINDOWS.read();rssPeak.accumulateAndGet(rss[0],Math::max);lifetimeRssPeak.accumulateAndGet(rss[1],Math::max);}
        void start(){sample();executor.scheduleAtFixedRate(this::sample,20,20,TimeUnit.MILLISECONDS);}
        public void close(){sample();executor.shutdownNow();try{executor.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}}
    }
    static long[] linuxRss(){
        Path status=Path.of("/proc/self/status");if(!Files.isRegularFile(status))return new long[]{-1,-1};
        try {long rss=-1,peak=-1;for(String line:Files.readAllLines(status)){if(line.startsWith("VmRSS:"))rss=Long.parseLong(line.replaceAll("[^0-9]",""))*1024;if(line.startsWith("VmHWM:"))peak=Long.parseLong(line.replaceAll("[^0-9]",""))*1024;}return new long[]{rss,peak};}
        catch(Exception ex){return new long[]{-1,-1};}
    }
    static final class WindowsMemory {
        final MethodHandle read;
        WindowsMemory(MethodHandle read){this.read=read;}
        static WindowsMemory create(){
            if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))return null;
            if(ValueLayout.ADDRESS.byteSize()!=8)return null;
            try {
                SymbolLookup lib=SymbolLookup.libraryLookup("psapi.dll",Arena.global());
                return new WindowsMemory(Linker.nativeLinker().downcallHandle(lib.find("GetProcessMemoryInfo").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT)));
            } catch(Throwable ex){System.err.println("RSS probe unavailable: "+ex);return null;}
        }
        long[] read(){
            try(Arena arena=Arena.ofConfined()) {
                MemorySegment result=arena.allocate(80,8);result.set(ValueLayout.JAVA_INT,0,80);
                int success=(int)read.invokeExact(MemorySegment.ofAddress(-1L),result,80);
                if(success==0)return new long[]{-1,-1};
                return new long[]{result.get(ValueLayout.JAVA_LONG,16),result.get(ValueLayout.JAVA_LONG,8)};
            } catch(Throwable ex){return new long[]{-1,-1};}
        }
    }

    static void writeOutputs(Path out,Map<String,String> opts,int workers,List<Job> jobs,
                             List<TaskResult> tasks,List<Map<String,Object>> rounds,boolean jfr) throws IOException {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("generatedAt",Instant.now().toString());report.put("pid",ProcessHandle.current().pid());
        report.put("java",System.getProperty("java.runtime.version"));report.put("jvmArgs",ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("os",System.getProperty("os.name"));report.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        report.put("heapMaxBytes",MEMORY.getHeapMemoryUsage().getMax());report.put("workers",workers);
        report.put("options",opts);report.put("jobs",jobs.stream().map(j->Map.of("date",j.date.toString(),"sourceSymbol",j.sourceSymbol,"source",j.source.toString(),"sourceBytes",j.bytes)).toList());
        report.put("rssMethod",WINDOWS!=null?"Windows GetProcessMemoryInfo WorkingSetSize / PeakWorkingSetSize":"Linux proc status or unavailable");
        report.put("jfrEnabled",jfr);report.put("rounds",rounds);report.put("tasks",tasks.stream().map(TaskResult::metrics).toList());
        report.put("notes",List.of("Warmup rounds are separate from measurement rounds.",
                "Source files may already be in OS cache: report is warm filesystem throughput unless a separate cold-cache run is arranged.",
                "Per-task JVM GC deltas and heap snapshots are process-wide and overlap across workers; use round GC totals for aggregate analysis.",
                "Thread allocation and CPU counters cover the worker, excluding GC/compiler threads; process CPU covers the JVM.",
                "P0/P1/P2/P4/P5 share one public native builder and are profiled as a combined stage.",
                "20 ms sampled RSS/heap may miss a very short peak; memory-pool peak and Windows lifetime working-set peak supplement these.",
                "Checksum/output hashing and JSON serialization are timed separately from parse/features.",
                "Stage profile duplicates production orchestration using the same native builder calls; non-profile uses production compute directly."));
        List<Map<String,Object>> detailed=new ArrayList<>();
        for(TaskResult task:tasks)for(Metric stage:task.stages){Map<String,Object> row=new LinkedHashMap<>();for(String key:List.of("phase","round","date","sourceSymbol","symbol"))row.put(key,task.metrics.get(key));row.put("stage",stage.stage);row.put("wallMs",stage.wallMs);row.put("cpuMs",stage.cpuMs);row.put("allocatedBytes",stage.allocatedBytes);detailed.add(row);}
        report.put("stages",detailed);
        writeAtomic(out.resolve("benchmark.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        StringBuilder csv=new StringBuilder();
        if(!tasks.isEmpty()){List<String> headers=new ArrayList<>(tasks.getFirst().metrics.keySet());csv.append(String.join(",",headers)).append('\n');for(TaskResult task:tasks){for(int i=0;i<headers.size();i++){if(i>0)csv.append(',');csv.append(csv(task.metrics.get(headers.get(i))));}csv.append('\n');}}
        writeAtomic(out.resolve("tasks.csv"),csv.toString());
        StringBuilder roundCsv=new StringBuilder("phase,round,jobs,wallMs,processCpuMs,gcCount,gcMillis,symbolsPerSecond,sourceMiBPerSecond,sampledHeapPeakBytes,sampledRssPeakBytes,windowsPeakWorkingSetJvmLifetimeBytes\n");
        for(var round:rounds){boolean first=true;for(String field:List.of("phase","round","jobs","wallMs","processCpuMs","gcCount","gcMillis","symbolsPerSecond","sourceMiBPerSecond","sampledHeapPeakBytes","sampledRssPeakBytes","windowsPeakWorkingSetJvmLifetimeBytes")){if(!first)roundCsv.append(',');first=false;roundCsv.append(csv(round.get(field)));}roundCsv.append('\n');}
        writeAtomic(out.resolve("rounds.csv"),roundCsv.toString());
        StringBuilder stageCsv=new StringBuilder("phase,round,date,sourceSymbol,symbol,stage,wallMs,cpuMs,allocatedBytes\n");
        for(var row:detailed){boolean first=true;for(Object value:row.values()){if(!first)stageCsv.append(',');first=false;stageCsv.append(csv(value));}stageCsv.append('\n');}
        writeAtomic(out.resolve("stages.csv"),stageCsv.toString());
        int finalRound=rounds.stream().filter(r->"measure".equals(r.get("phase"))).mapToInt(r->((Number)r.get("round")).intValue()).max().orElse(-1);
        StringBuilder rows=new StringBuilder();for(TaskResult task:tasks)if("measure".equals(task.metrics.get("phase")) && ((Number)task.metrics.get("round")).intValue()==finalRound)rows.append(task.dailyJson).append('\n');
        writeAtomic(out.resolve("daily-features.jsonl"),rows.toString());
    }
    static String csv(Object value){if(value==null)return "";String text=value.toString();return text.indexOf(',')>=0 || text.indexOf('"')>=0 || text.indexOf('\n')>=0?'"'+text.replace("\"","\"\"")+'"':text;}
    static void writeAtomic(Path output,String text) throws IOException {Path temporary=output.resolveSibling(output.getFileName()+".tmp-"+UUID.randomUUID());try{Files.writeString(temporary,text,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}}
    static Map<String,String> options(String[] args){Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2){if(i+1>=args.length || !args[i].startsWith("--"))throw new IllegalArgumentException("Expected --option value pairs");if(result.put(args[i],args[i+1])!=null)throw new IllegalArgumentException("Duplicate option "+args[i]);}return result;}
    static String required(Map<String,String> opts,String key){String value=opts.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException("Required "+key);return value;}
    static int integer(Map<String,String> opts,String key,int def,int min,int max){int value=Integer.parseInt(opts.getOrDefault(key,Integer.toString(def)));if(value<min || value>max)throw new IllegalArgumentException(key+" must be "+min+".."+max);return value;}
    static LocalDate date(String text){text=text.strip();return LocalDate.parse(text.length()==8?text.substring(0,4)+"-"+text.substring(4,6)+"-"+text.substring(6,8):text);}
}
