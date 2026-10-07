package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.policy.IndexMonthlyUniverse;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.IndexMonthlyStaging;
import com.zoutrankil.data.repository.IndexMonthlyWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** Executes one frozen monthly source window through non-DEDUP stage and table publication. */
public final class IndexMonthlySyncAdapter implements SyncJobRunner.Adapter<IndexMonthly,IndexMonthlyKey> {
    private final IndexMonthlySource source;
    private final IndexMonthlyWritePort port;
    private final Path evidenceRoot;
    private final String runId,table,logicalTarget;
    private final Path ledgerPath;
    private final JdbcTemplate jdbc;

    public IndexMonthlySyncAdapter(IndexMonthlySource source, IndexMonthlyWritePort port, Path evidenceRoot) {
        this(source,port,evidenceRoot,null,null,null,null,null);
    }
    public IndexMonthlySyncAdapter(IndexMonthlySource source, IndexMonthlyWritePort port, Path evidenceRoot,
            String runId, Path ledgerPath, String table, String logicalTarget, JdbcTemplate jdbc) {
        this.source=Objects.requireNonNull(source);this.port=Objects.requireNonNull(port);
        this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
        this.runId=runId;this.ledgerPath=ledgerPath==null?null:ledgerPath.toAbsolutePath().normalize();this.table=table;this.logicalTarget=logicalTarget;this.jdbc=jdbc;
        boolean complete=runId!=null&&this.ledgerPath!=null&&table!=null&&logicalTarget!=null&&jdbc!=null;
        boolean absent=runId==null&&this.ledgerPath==null&&table==null&&logicalTarget==null&&jdbc==null;
        if(!complete&&!absent)throw new IllegalArgumentException("Incomplete D022 publication context");
    }

    @Override public void preflight(SyncJobDefinition.FrozenRequest request) {
        if(request==null||!request.definition().equals(IndexMonthlySyncJobOwner.DEFINITION)||!"index_monthly".equals(request.definition().datasetId())
                ||request.definition().datasetVersion()!=IndexMonthlyDataset.DEFINITION.schemaVersion()||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode())
                ||request.from()==null||request.to()==null||request.to().isAfter(request.logicalDate())
                ||java.time.temporal.ChronoUnit.DAYS.between(request.from(),request.to())+1>IndexMonthlySource.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D022 request required");
        var p=request.parameters();Set<String> allowed=Set.of("targetId","physicalTargetId","tsCode","observedAt","checkpointAnchor","checkpointBefore","targetMinBefore","targetMaxBefore");
        if(!p.keySet().containsAll(Set.of("targetId","physicalTargetId","tsCode","observedAt"))||!allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException("Unexpected/missing D022 frozen parameters");
        if(!(p.get("targetId") instanceof String target)||!target.matches("static-v2-[0-9a-f]{64}")
                ||!(p.get("physicalTargetId") instanceof String physical)||!physical.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D022 logical and physical target identities required");
        if(!(p.get("tsCode") instanceof String code)||!IndexMonthlyUniverse.validProviderCode(code))throw new IllegalArgumentException("Frozen D022 monthly provider code required");
        if(!(p.get("observedAt") instanceof String observed))throw new IllegalArgumentException("Frozen D022 observedAt required");Instant timestamp;
        try{timestamp=Instant.parse(observed);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid D022 observedAt",invalid);}
        if(!timestamp.equals(timestamp.truncatedTo(ChronoUnit.MICROS)))throw new IllegalArgumentException("D022 observedAt must have microsecond precision");
        Object anchor=p.get("checkpointAnchor");if(request.mode()==Mode.INCREMENTAL){if(!(anchor instanceof java.time.LocalDate date)||date.isAfter(request.to())||request.from().isBefore(date))throw new IllegalArgumentException("D022 incremental needs frozen bootstrap anchor");}
        else if(anchor!=null||p.get("checkpointBefore")!=null)throw new IllegalArgumentException("Only D022 incremental requests may carry checkpoint metadata");
        for(String field:List.of("checkpointBefore","targetMinBefore","targetMaxBefore"))if(p.get(field)!=null&&!(p.get(field) instanceof java.time.LocalDate))throw new IllegalArgumentException("Invalid D022 date parameter "+field);
        if(runId!=null&&!logicalTarget.equals(target))throw new IllegalArgumentException("D022 logical target differs from frozen request");
        port.preflight();
    }

    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,
            SyncJobRunner.PageConsumer<IndexMonthly> consumer,BooleanSupplier cancelled)throws Exception {
        if(runId==null||ledgerPath==null||table==null||logicalTarget==null||jdbc==null)
            throw new IllegalStateException("D022 execution requires durable publication context");
        new IndexMonthlyPublication(jdbc,ledgerPath).requireNoPendingPublication();
        String code=(String)request.parameters().get("tsCode");Instant observed=Instant.parse((String)request.parameters().get("observedAt"));
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D022 source slice cancelled");
        var page=source.fetch(code,request.from(),request.to(),observed,cancelled);
        var keys=new HashSet<IndexMonthlyKey>();for(var row:page.rows())if(!keys.add(row.key()))throw new IllegalStateException("Duplicate D022 source identity");
        for(var existing:port.readRange(code,request.from(),request.to()))if(!keys.contains(existing.key()))
            throw new IllegalStateException("D022 source omitted an existing physical business key; fail closed for deletion review");

        String publicationId="no-op-empty-window";
        String[] stageReceiptHolder={null};
        if(page.rows().isEmpty()) {
            // Source omission guard above proves this interval has no physical keys, so no replacement is required.
            consumer.accept(page);
        } else {
            var publisher=new IndexMonthlyPublication(jdbc,ledgerPath);
            try(var operation=publisher.beginOperation()) {
                var staging=new IndexMonthlyStaging(jdbc);
                var prepared=staging.prepare(table,logicalTarget,(String)request.parameters().get("physicalTargetId"),runId,
                        request,code,request.from(),request.to(),page.responseEvidence(),page.sourceFingerprint(),page.rows().size(),
                        evidenceRoot.resolve("stage"),cancelled);
                port.useStagingTarget(prepared.stage(),prepared.stagePhysicalTarget());
                String[] publishedIdHolder={null};
                port.configureFinalPublication(page.rows(),()->{
                    var stage=staging.verify(prepared,page.rows(),page.sourceFingerprint(),page.responseEvidence(),evidenceRoot.resolve("stage"),cancelled);
                    stageReceiptHolder[0]=stage.receipt();
                    var published=publisher.publish(operation,runId,logicalTarget,
                            (String)request.parameters().get("physicalTargetId"),table,prepared,stage,cancelled);
                    publishedIdHolder[0]=published.entry().intent().id();
                    return com.zoutrankil.data.repository.IndexMonthlyStorage.physicalTargetId(jdbc,table,published.target().identity());
                });
                consumer.accept(page);
                if(!port.publicationComplete()||publishedIdHolder[0]==null)
                    throw new IllegalStateException("D022 source page did not reach full-row stage publication");
                publicationId=publishedIdHolder[0];
            }
        }

        Files.createDirectories(evidenceRoot);Path completion=evidenceRoot.resolve("complete-"+UUID.randomUUID()+".json");
        var proof=new LinkedHashMap<String,Object>();proof.put("endpoint","index_monthly");proof.put("tsCode",code);proof.put("from",request.from());proof.put("to",request.to());proof.put("observedAt",observed);
        proof.put("sourceRows",page.rows().size());proof.put("sourceEvidence",page.responseEvidence());proof.put("sourceComplete",true);
        proof.put("publicationId",publicationId);proof.put("stageReceipt",stageReceiptHolder[0]);proof.put("dedup",false);
        JobDefinitionJson.canonicalMapper().writeValue(completion.toFile(),proof);
        if(Files.size(completion)>IndexMonthlySource.MAX_EVIDENCE_BYTES)throw new IllegalArgumentException("D022 completion evidence exceeds 16 MiB");
        return new SyncJobRunner.SourceCompletion(1,page.rows().size(),true,completion.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<IndexMonthly,IndexMonthlyKey> codec(){return IndexMonthlyWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<IndexMonthly,IndexMonthlyKey> port(){return port;}

    /** Keep a stopped D022 run reconcilable whenever its stage or publication journal exists. */
    @Override public boolean recoveryRequired(String candidateRunId)throws Exception {
        if(runId==null||ledgerPath==null||table==null||logicalTarget==null||jdbc==null)return false;
        if(!runId.equals(candidateRunId))throw new IllegalStateException("D022 recovery check run identity changed");
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);
        var state=ledger.get(candidateRunId).state();
        if(state==SyncRunState.VERIFIED||state==SyncRunState.VERIFIED_EMPTY)return false;
        if(new IndexMonthlyPublication(jdbc,ledgerPath).findForRun(candidateRunId).isPresent())return true;
        return IndexMonthlyStaging.hasStageIntent(evidenceRoot);
    }
}
