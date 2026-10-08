package com.zoutrankil.batch;

import com.zoutrankil.data.service.PageExecutor;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Native Java collection, normalization and immutable source retention; never calls Python. */
public final class SourceCollector {
    @FunctionalInterface public interface ContractFetcher {
        PageExecutor.Page fetch(com.zoutrankil.data.domain.PageContract providerContract,
                                Map<String,Object> params) throws Exception;
    }
    public record Request(String dataset,LocalDate logicalDate,Set<String> expectedCodes,String universeVersion,String tsCode,
                          LocalDate rangeStart,LocalDate rangeEnd) {
        public Request(String dataset,LocalDate logicalDate,Set<String> expectedCodes,String universeVersion,String tsCode) {
            this(dataset,logicalDate,expectedCodes,universeVersion,tsCode,logicalDate,logicalDate);
        }
        public Request {
            var contract=SourceContract.load(dataset);
            expectedCodes=SourceStrategies.require(dataset).request().validate(contract,logicalDate,expectedCodes,
                    universeVersion,tsCode,rangeStart,rangeEnd);
        }
    }
    public record Frozen(int schemaVersion,String dataset,String definitionVersion,LocalDate logicalDate,LocalDate rangeStart,LocalDate rangeEnd,
                         Set<String> expectedCodes,String universeVersion,String sourceDocumentation,
                         List<Map<String,Object>> rows,int sourcePages,boolean completeCoverage) {
        public Frozen {
            if((schemaVersion!=1&&schemaVersion!=2) || sourcePages<1) throw new IllegalArgumentException("Unknown frozen-source schema or missing probe evidence");
            // Schema v1 archives represented exactly one logical date and remain valid recovery inputs.
            if(rangeStart==null) rangeStart=logicalDate;
            if(rangeEnd==null) rangeEnd=logicalDate;
            new Request(dataset,logicalDate,expectedCodes,universeVersion,null,rangeStart,rangeEnd);
            expectedCodes=Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
            rows=rows.stream().map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
        }
    }
    public record Collected(String fingerprint,String artifact,int rows,int pages,boolean completeCoverage,
                            String scopeIdentity,BusinessState state) {}
    private final Path root;
    public SourceCollector(Path archiveRoot) { root=archiveRoot.toAbsolutePath().normalize().resolve("sources"); }
    public Collected collect(Request request,PageExecutor.Fetcher source) throws Exception {
        return collect(request,(providerContract,params) -> source.fetch(params));
    }
    public Collected collect(Request request,ContractFetcher source) throws Exception {
        var contract=SourceContract.load(request.dataset()); var normalized=new ArrayList<Map<String,Object>>();
        var strategy=SourceStrategies.require(contract.dataset());
        var session=strategy.request().openSession(contract,request,strategy.rows());
        final int[] bytes={0},rawBytes={0},rawRows={0};var rawArtifacts=new ArrayList<String>();
        final int[] completedPages={0},completedRows={0};
        PageExecutor.Fetcher retainedSource=pageParams -> {
            String providerCode=(String)pageParams.get("ts_code");
            var page=source.fetch(contract.providerContract(providerCode),pageParams);
            if(page==null) return null;
            session.validateResponse(pageParams,page);
            if(page.rows().size()>contract.pageSize()) throw new IllegalArgumentException("Raw page row bound exceeded");
            rawRows[0]=Math.addExact(rawRows[0],page.rows().size());
            byte[] raw=Json.MAPPER.writeValueAsBytes(page);
            rawBytes[0]=Math.addExact(rawBytes[0],raw.length);
            if(rawBytes[0]>contract.maxBytes()) throw new IllegalArgumentException("Raw source byte budget exceeded");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            Path artifact=root.resolve("raw").resolve(digest+".json");retain(artifact,raw);rawArtifacts.add(artifact.toString());
            var prepared=session.prepareRows(providerCode,page.rows());
            return new PageExecutor.Page(prepared,
                    page.nextCursor(),page.explicitEnd(),page.sourceVersion());
        };
        for(var queryParams:strategy.request().queries(contract,request)) {
            var completed=new PageExecutor().execute(contract.pageContract(),queryParams,retainedSource,(page,receipt) ->
                session.acceptPage(page,value -> {
                    bytes[0]=Math.addExact(bytes[0],Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    if(bytes[0]>contract.maxBytes()) throw new IllegalArgumentException("Frozen source byte budget exceeded");
                    normalized.add(value);
                }),session::validateRow,() -> Thread.currentThread().isInterrupted());
            completedPages[0]=Math.addExact(completedPages[0],completed.pages());
            completedRows[0]=Math.addExact(completedRows[0],completed.rows());
        }
        session.finish(normalized);
        if(normalized.size()>1) {
            record KeyedRow(String key,Map<String,Object> row) {}
            var keyedRows=new ArrayList<KeyedRow>(normalized.size());
            for(var row:normalized) keyedRows.add(new KeyedRow(contract.key(row),row));
            keyedRows.sort(Comparator.comparing(KeyedRow::key));
            for(int i=0;i<keyedRows.size();i++) normalized.set(i,keyedRows.get(i).row());
        }
        boolean coverage=strategy.coverage().coversRange(contract,normalized,request.expectedCodes(),request.rangeStart(),request.rangeEnd());
        var frozen=new Frozen(2,contract.dataset(),contract.version(),request.logicalDate(),request.rangeStart(),request.rangeEnd(),request.expectedCodes(),request.universeVersion(),
                contract.sourceDocumentation(),normalized,completedPages[0],coverage);
        // Observation time is in the probe receipt, not the content identity. Repeating equal input is stable.
        byte[] content=Json.MAPPER.writeValueAsBytes(frozen);
        if(content.length>contract.maxBytes()) throw new IllegalArgumentException("Frozen manifest byte budget exceeded");
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path target=root.resolve(fingerprint+".json");retain(target,content);
        Files.writeString(root.resolve(fingerprint+".probe-"+UUID.randomUUID()+".json"),Json.write(Map.of("observedAt",Instant.now(),"rows",normalized.size(),"sourceRows",rawRows[0],"filteredFooterRows",completedRows[0]-normalized.size(),"collapsedOrFilteredEventRows",rawRows[0]-completedRows[0],"rawArtifacts",rawArtifacts,"pages",completedPages[0],"sourceProbeComplete",true)),StandardOpenOption.CREATE_NEW);
        return new Collected(fingerprint,target.toString(),normalized.size(),completedPages[0],coverage,scopeIdentity(frozen),
                normalized.isEmpty()&&!contract.emptyAllowed()?BusinessState.WAITING_SOURCE:!coverage?BusinessState.PARTIAL:
                    SourceQuality.failures(contract.dataset(),normalized).isEmpty()?BusinessState.VERIFYING:BusinessState.BLOCKED);
    }
    public static String scopeIdentity(Frozen frozen) {
        return RunRequest.hash(frozen.dataset(),frozen.universeVersion(),String.join("\n",frozen.expectedCodes()));
    }
    static boolean coversMonths(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        return SourcePeriods.coversMonths(rows,start,end);
    }
    static boolean coversQuarters(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        return SourcePeriods.coversQuarters(rows,start,end);
    }
    static String quarter(LocalDate date) { return SourcePeriods.quarter(date); }
    static boolean isQuarterEnd(LocalDate date) { return SourcePeriods.isQuarterEnd(date); }
    static long quartersBetween(LocalDate start,LocalDate end) { return SourcePeriods.quartersBetween(start,end); }
    private static void retain(Path target,byte[] content) throws java.io.IOException {
        Files.createDirectories(target.getParent());
        if(Files.exists(target)) {
            if(!Arrays.equals(Files.readAllBytes(target),content)) throw new IllegalArgumentException("Retained content changed");
            return;
        }
        Path temp=Files.createTempFile(target.getParent(),"collect-",".partial");
        try {
            try(var file=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(content);while(buffer.hasRemaining()) file.write(buffer);file.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
    public boolean sameScope(RunRequest previous,RunRequest next) {
        try {
            Frozen left=read(previous.inputFingerprint()),right=read(next.inputFingerprint());
            return left.dataset().equals(right.dataset()) && left.logicalDate().equals(right.logicalDate()) && left.rangeStart().equals(right.rangeStart()) && left.rangeEnd().equals(right.rangeEnd())
                    && left.definitionVersion().equals(right.definitionVersion()) && left.expectedCodes().equals(right.expectedCodes())
                    && left.universeVersion().equals(right.universeVersion());
        } catch(Exception invalid) { return false; }
    }
    public Frozen read(String fingerprint) throws Exception {
        if(fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Source SHA-256 required");
        Path path=root.resolve(fingerprint+".json");
        if(Files.size(path)>64L*1024*1024) throw new IllegalArgumentException("Frozen source exceeds memory budget");
        byte[] content=Files.readAllBytes(path);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(fingerprint))
            throw new IllegalArgumentException("Frozen source content changed");
        return Json.MAPPER.readValue(content,Frozen.class);
    }
    public Path path(String fingerprint) {
        if(fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Source SHA-256 required");
        return root.resolve(fingerprint+".json");
    }
}
