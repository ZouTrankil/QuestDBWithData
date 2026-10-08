package com.zoutrankil.batch;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.TransactionDefinition;

public final class BatchJobs {
    private BatchJobs() {}
    public static Job postClose(JobRepository repository, PlatformTransactionManager transactionManager,
                                SqliteLedger ledger, StageExecutor executor) {
        var steps=PostCloseGraph.STAGES.stream().map(stage -> step(repository,transactionManager,ledger,executor,stage)).toList();
        var builder=new JobBuilder("post_close", repository).start(steps.getFirst());
        for (int i=1;i<steps.size();i++) builder.next(steps.get(i));
        var verification = new StepBuilder("VerifyPostClose",repository).tasklet((contribution,context) -> {
            String id=(String)context.getStepContext().getJobParameters().get("instance");
            if (ledger.stages(id).values().stream().anyMatch(s -> !s.ready())) {
                ledger.state(id,BusinessState.PARTIAL,contribution.getStepExecution().getJobExecutionId(),"Optional stage warning; inspect steps");
                throw new IllegalStateException("Optional stages require recovery");
            }
            return RepeatStatus.FINISHED;
        },transactionManager).build();
        return builder.next(verification).build();
    }
    public static Job source(JobRepository repository, PlatformTransactionManager transactionManager,
                             SqliteLedger ledger, SourceProductRunner runner, String dataset) {
        SourceContract.load(dataset);
        var transaction=new DefaultTransactionAttribute();
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step=new StepBuilder("Source:"+dataset,repository).tasklet((contribution,context) -> {
            String id=(String)context.getStepContext().getJobParameters().get("instance");
            RunRequest request=ledger.request(id);
            StageExecutor.Result result=runner.execute(request,dataset);
            ledger.completeSource(request,result,contribution.getStepExecution().getJobExecutionId());
            if(!result.state().ready()) throw new IllegalStateException("Source product is "+result.state());
            return RepeatStatus.FINISHED;
        },transactionManager).transactionAttribute(transaction).build();
        return new JobBuilder("source_"+dataset,repository).start(step).build();
    }
    public static Job l2ArchiveIntegrity(JobRepository repository,PlatformTransactionManager transactionManager,L2ArchiveAdmission archives,L2ArchiveMaterializer materializer,L2ArchiveQuestDbIngestor ingestor) {
        var transaction=new DefaultTransactionAttribute();
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step=new StepBuilder("L2ArchiveIntegrity",repository).tasklet((contribution,context) -> {
            Object parameter=context.getStepContext().getJobParameters().get("archivePath");
            if(parameter==null) throw new IllegalArgumentException("archivePath parameter required");
            var admission=archives.inspect(java.nio.file.Path.of(parameter.toString()),java.time.Instant.now());
            var execution=contribution.getStepExecution().getJobExecution();
            execution.getExecutionContext().putString("archiveSha256",admission.sha256());
            execution.getExecutionContext().putString("frozenPath",admission.path().toString());
            execution.getExecutionContext().putString("tradeDate",admission.tradeDate().toString());
            execution.getExecutionContext().putString("status",admission.status());
            execution.getExecutionContext().putString("verification",admission.verification());
            execution.getExecutionContext().putLong("archiveMembers",admission.members());
            execution.getExecutionContext().putLong("archiveSymbols",admission.symbols());
            execution.getExecutionContext().putLong("archiveSizeBytes",admission.sizeBytes());
            execution.getExecutionContext().putString("archiveDuplicate",Boolean.toString(admission.duplicate()));
            execution.getExecutionContext().putLong("sourceRows",admission.sourceRows());
            execution.getExecutionContext().putLong("tradeDateMismatches",admission.tradeDateMismatches());
            if(admission.revisionOfSha256()!=null) execution.getExecutionContext().putString("revisionOfSha256",admission.revisionOfSha256());
            return RepeatStatus.FINISHED;
        },transactionManager).transactionAttribute(transaction).build();
        var materialize=new StepBuilder("L2ArchiveSemanticMaterialization",repository).tasklet((contribution,context) -> {
            var execution=contribution.getStepExecution().getJobExecution();var values=execution.getExecutionContext();
            if(values.getLong("tradeDateMismatches")>0) {
                values.putString("parseStatus","DATA_QUALITY_FAILED");
                return RepeatStatus.FINISHED;
            }
            String sha=values.getString("archiveSha256"),date=values.getString("tradeDate"),path=values.getString("frozenPath");
            var admission=new L2ArchiveAdmission.Admission(sha,java.time.LocalDate.parse(date),java.nio.file.Path.of(path),
                    values.getLong("archiveSizeBytes"),(int)values.getLong("archiveMembers"),(int)values.getLong("archiveSymbols"),
                    values.getLong("sourceRows"),values.getLong("tradeDateMismatches"),Boolean.parseBoolean(values.getString("archiveDuplicate")),
                    values.getString("verification"),values.getString("status"),values.containsKey("revisionOfSha256")?values.getString("revisionOfSha256"):null);
            var parsed=materializer.materialize(admission);var manifest=parsed.manifest();
            values.putString("parseStatus","SEMANTICALLY_PARSED");values.putString("materializationManifest",parsed.manifestPath().toString());
            values.putString("parserVersion",manifest.parserVersion());values.putString("materializationReused",Boolean.toString(parsed.reused()));
            values.putLong("dealRows",manifest.dealRows());values.putLong("orderRows",manifest.orderRows());values.putLong("quoteRows",manifest.quoteRows());
            if(ingestor.configured()) {
                var written=ingestor.ingest(parsed);values.putString("writeStatus","VERIFIED");values.putString("businessInstanceId",written.instanceId());
                values.putString("writeRows",Json.write(written.rows()));values.putString("writeTargets",Json.write(written.targets()));values.putString("ingestCertificate",written.certificatePath().toString());
            } else values.putString("writeStatus","NOT_CONFIGURED");
            return RepeatStatus.FINISHED;
        },transactionManager).transactionAttribute(transaction).build();
        return new JobBuilder("l2_archive_integrity",repository).start(step).next(materialize).build();
    }
    private static Step step(JobRepository repository, PlatformTransactionManager transactionManager,
                             SqliteLedger ledger, StageExecutor executor, PostCloseGraph.Stage stage) {
        var transaction = new DefaultTransactionAttribute();
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return new StepBuilder(stage.id(),repository).allowStartIfComplete(true).tasklet((contribution,context) -> {
            String instance=(String)context.getStepContext().getJobParameters().get("instance");
            RunRequest request=ledger.request(instance);
            var states=ledger.stages(instance);
            if (states.getOrDefault(stage.id(),BusinessState.WAITING_UPSTREAM).ready()) return RepeatStatus.FINISHED;
            if (stage.dependencies().stream().anyMatch(d -> !states.getOrDefault(d,BusinessState.WAITING_UPSTREAM).ready())) {
                ledger.stage(request,stage.id(),BusinessState.WAITING_UPSTREAM,null,"upstream-not-ready");
                throw new IllegalStateException("upstream-not-ready:"+stage.id());
            }
            ledger.stage(request,stage.id(),BusinessState.RUNNING,null,null);
            StageExecutor.Result result;
            try { result=executor.execute(request,stage); }
            catch (Exception error) {
                result=new StageExecutor.Result(BusinessState.IN_DOUBT,null,"executor-outcome-unknown:"+error.getClass().getSimpleName());
            }
            ledger.stage(request,stage.id(),result.state(),result.evidence(),result.reason());
            if (stage.critical() && !result.state().ready()) {
                ledger.state(instance,result.state(),contribution.getStepExecution().getJobExecutionId(),result.reason());
                throw new IllegalStateException("Business gate: "+stage.id()+":"+result.state());
            }
            return RepeatStatus.FINISHED;
        },transactionManager).transactionAttribute(transaction).build();
    }
}
