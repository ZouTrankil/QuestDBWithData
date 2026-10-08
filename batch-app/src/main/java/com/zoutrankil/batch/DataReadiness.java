package com.zoutrankil.batch;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Builds DataReady only from current, date-aligned certificates for every required core producer. */
public final class DataReadiness implements StageExecutor {
    private record VerifiedEvidence(CompletionEvidence evidence,String certificateSha256,String sourceArtifactSha256) {}
    public static final Set<String> REQUIRED_SOURCES;
    static {
        var sources=new TreeSet<>(PostCloseGraph.CORE_TABLES);
        sources.add("exchange_calendar");
        REQUIRED_SOURCES=Collections.unmodifiableSet(sources);
    }
    private final SqliteLedger ledger;
    private final Path archiveRoot;
    private final StageExecutor otherStages;

    public DataReadiness(SqliteLedger ledger,Path archiveRoot,StageExecutor otherStages) {
        this.ledger=Objects.requireNonNull(ledger);
        this.archiveRoot=archiveRoot.toAbsolutePath().normalize();
        this.otherStages=Objects.requireNonNull(otherStages);
    }

    @Override public Result execute(RunRequest request,PostCloseGraph.Stage stage) {
        if(!stage.id().equals("DataReady")) return otherStages.execute(request,stage);
        if(!request.job().equals("post_close") || !request.rangeStart().equals(request.logicalDate())
                || !request.rangeEnd().equals(request.logicalDate()))
            return new Result(BusinessState.BLOCKED,null,"data-ready-requires-one-post-close-logical-date");
        var latest=ledger.latestSourceCertificates(request.logicalDate());
        var missing=new TreeSet<String>();
        var sources=new TreeMap<String,VerifiedEvidence>();
        for(String dataset:REQUIRED_SOURCES) {
            var observed=latest.get(dataset);
            if(observed==null || !observed.state().ready() || observed.evidenceJson()==null || observed.requestJson()==null) {
                missing.add(dataset+":"+(observed==null?"missing":observed.state()));
                continue;
            }
            try {
                var sourceRequest=Json.read(observed.requestJson(),RunRequest.class);
                var evidence=Json.read(observed.evidenceJson(),CompletionEvidence.class);
                var contract=SourceContract.load(dataset);
                Path artifact=Path.of(evidence.artifact()).toAbsolutePath().normalize();
                Path archiveReal=archiveRoot.toRealPath();
                if(!artifact.startsWith(archiveRoot) || !Files.isRegularFile(artifact)
                        || !artifact.toRealPath().startsWith(archiveReal) || Files.size(artifact)>1024*1024) {
                    missing.add(dataset+":certificate-outside-archive-root");
                    continue;
                }
                var certificate=Json.MAPPER.readTree(Files.readString(artifact));
                String expectedTarget="jdb_test_"+ledger.environmentNamespace()+"_"+dataset+"_"+sourceRequest.instanceId();
                Path sourceArtifact=Path.of(certificate.path("sourceArtifact").asText()).toAbsolutePath().normalize();
                boolean sourceArtifactValid=sourceArtifact.startsWith(archiveRoot)&&Files.isRegularFile(sourceArtifact)
                        &&sourceArtifact.toRealPath().startsWith(archiveReal);
                String certificateDigest=sha256(artifact,1024*1024),sourceArtifactDigest=sourceArtifactValid?sha256(sourceArtifact,contract.maxBytes()):"";
                if(!observed.job().equals("source_"+dataset)
                        || !evidence.matches(sourceRequest,"Source:"+dataset)
                        || evidence.state()!=observed.state()
                        || !sourceRequest.logicalDate().equals(request.logicalDate())
                        || !sourceRequest.rangeStart().equals(request.logicalDate())
                        || !sourceRequest.rangeEnd().equals(request.logicalDate())
                        || !sourceRequest.calendarVersion().equals(request.calendarVersion())
                        || !sourceRequest.zone().equals(request.zone())
                        || !sourceRequest.definitionVersion().equals(contract.version())
                        || !sourceRequest.scopeIdentity().matches("[a-f0-9]{64}")
                        || !sourceRequest.inputFingerprint().matches("[a-f0-9]{64}")
                        || !evidence.actualStart().equals(request.logicalDate())
                        || !evidence.actualEnd().equals(request.logicalDate())
                        || evidence.state()==BusinessState.VERIFIED_EMPTY&&(!contract.emptyAllowed()||!evidence.emptyAllowed())
                        || !certificate.path("sourceFingerprint").asText().equals(sourceRequest.inputFingerprint())
                        || !certificate.path("definitionVersion").asText().equals(evidence.definitionVersion())
                        || !certificate.path("target").asText().equals(expectedTarget)
                        || certificate.path("verifiedRows").asLong(-1)!=evidence.writtenRows()
                        || !certificate.path("batches").isArray() || certificate.path("batches").isEmpty()
                        || !sourceArtifactValid || !sourceArtifactDigest.equals(sourceRequest.inputFingerprint())) {
                    missing.add(dataset+":certificate-scope-or-artifact-mismatch");
                    continue;
                }
                sources.put(dataset,new VerifiedEvidence(evidence,certificateDigest,sourceArtifactDigest));
            } catch(Exception invalid) {
                missing.add(dataset+":invalid-certificate");
            }
        }
        if(!missing.isEmpty()) return new Result(BusinessState.BLOCKED,null,"data-ready-required-sources:"+String.join(",",missing));
        long readRows=sources.values().stream().mapToLong(source -> source.evidence().readRows()).sum();
        long writtenRows=sources.values().stream().mapToLong(source -> source.evidence().writtenRows()).sum();
        if(writtenRows==0) return new Result(BusinessState.BLOCKED,null,"data-ready-no-core-data-rows");
        try {
            var identities=new TreeMap<String,Object>();
            sources.forEach((name,source)-> {
                var evidence=source.evidence();
                identities.put(name,Map.of("instanceId",evidence.instanceId(),"batchId",evidence.batchId(),
                        "definitionVersion",evidence.definitionVersion(),"sourceVersion",evidence.sourceVersion(),
                        "inputFingerprint",evidence.inputFingerprint(),"readRows",evidence.readRows(),"writtenRows",evidence.writtenRows(),
                        "artifact",evidence.artifact(),"certificateSha256",source.certificateSha256(),
                        "sourceArtifactSha256",source.sourceArtifactSha256()));
            });
            String sourceIdentity=Json.write(identities);
            String fingerprint=RunRequest.hash(sourceIdentity);
            String certificateId=RunRequest.hash(request.instanceId(),fingerprint);
            Path directory=archiveRoot.resolve("readiness");Files.createDirectories(directory);
            Path target=directory.resolve(request.instanceId()+"-"+fingerprint+"-data-ready.json");
            var report=Map.ofEntries(Map.entry("schemaVersion",1),Map.entry("state","VERIFIED"),Map.entry("stage","DataReady"),
                    Map.entry("logicalDate",request.logicalDate()),Map.entry("definitionVersion","data-ready-v1"),
                    Map.entry("calendarVersion",request.calendarVersion()),Map.entry("sourceIdentity",fingerprint),
                    Map.entry("requiredSources",REQUIRED_SOURCES),Map.entry("sourceCertificates",identities),
                    Map.entry("readRows",readRows),Map.entry("writtenRows",writtenRows));
            Path temporary=Files.createTempFile(directory,"data-ready-",".partial");
            try {
                Files.writeString(temporary,Json.write(report),StandardOpenOption.TRUNCATE_EXISTING);
                if(Files.exists(target)) {
                    if(!Files.readString(target).equals(Json.write(report))) throw new java.io.IOException("Immutable DataReady certificate collision");
                    Files.deleteIfExists(temporary);
                } else Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            Instant availableAt=sources.values().stream().map(source -> source.evidence().availableAt()).max(Comparator.naturalOrder()).orElseThrow();
            String sourceVersion=RunRequest.hash(sources.values().stream().map(source -> source.evidence().sourceVersion()).toArray(String[]::new));
            var evidence=new CompletionEvidence(1,"java-data-readiness",request.instanceId(),"DataReady",certificateId,
                    request.logicalDate(),request.rangeStart(),request.rangeEnd(),request.logicalDate(),request.logicalDate(),
                    request.definitionVersion(),sourceVersion,request.inputFingerprint(),readRows,writtenRows,true,true,true,true,true,false,
                    availableAt,BusinessState.VERIFIED,target.toString(),null);
            return new Result(BusinessState.VERIFIED,evidence,null);
        } catch(Exception failure) {
            return new Result(BusinessState.BLOCKED,null,"data-ready-certificate-write-failed:"+failure.getClass().getSimpleName());
        }
    }

    private static String sha256(Path path,long maxBytes) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[65536];
        try(var input=Files.newInputStream(path)) {
            int read;
            while((read=input.read(buffer))!=-1) {
                total=Math.addExact(total,read);
                if(total>maxBytes) throw new java.io.IOException("Evidence artifact exceeds the registered byte limit");
                digest.update(buffer,0,read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
