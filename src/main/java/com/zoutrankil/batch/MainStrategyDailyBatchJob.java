package com.zoutrankil.batch;

import java.util.HashSet;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/** Long-running owner calls never hold a SQLite transaction across QuestDB side effects. */
final class MainStrategyDailyBatchJob {
    private MainStrategyDailyBatchJob() {}
    static Job build(JobRepository repository, PlatformTransactionManager manager,
                     SqliteLedger ledger, MainStrategyDailyWork work) {
        var transaction=new DefaultTransactionAttribute();
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step=new StepBuilder("MainStrategyDailyPipeline",repository).allowStartIfComplete(true)
                .tasklet((contribution,context) -> {
                    String id=(String)context.getStepContext().getJobParameters().get("instance");
                    RunRequest request=ledger.request(id);
                    var seen=new HashSet<String>();
                    try {
                        work.execute(request,new MainStrategyDailyWork.StageSink() {
                          public java.util.Optional<CompletionEvidence> existing(String stage) {
                            return ledger.jdbc().query("SELECT evidence_json FROM stage_result WHERE instance_id=? AND stage=? AND business_state IN ('VERIFIED','VERIFIED_EMPTY')",
                                    (rs,n)->Json.read(rs.getString(1),CompletionEvidence.class),id,stage).stream().findFirst();
                          }
                          public void complete(String stage,StageExecutor.Result result) throws Exception {
                            int index=MainStrategyDailyWork.STAGES.indexOf(stage);
                            if(index<0 || !seen.add(stage)) throw new IllegalArgumentException("Unknown or duplicate main-strategy stage");
                            var states=ledger.stages(id);
                            for(int i=0;i<index;i++) if(!states.getOrDefault(MainStrategyDailyWork.STAGES.get(i),
                                    BusinessState.WAITING_UPSTREAM).ready())
                                throw new IllegalStateException("Main-strategy upstream gate is not ready");
                            ledger.stage(request,stage,result.state(),result.evidence(),result.reason());
                            if(!result.state().ready()) {
                                ledger.state(id,result.state(),contribution.getStepExecution().getJobExecutionId(),result.reason());
                                throw new IllegalStateException("Main-strategy gate: "+stage+":"+result.state());
                            }
                          }
                        });
                        var states=ledger.stages(id);
                        if(MainStrategyDailyWork.STAGES.stream().anyMatch(stage ->
                                !states.getOrDefault(stage,BusinessState.WAITING_UPSTREAM).ready()))
                            throw new IllegalStateException("Main-strategy pipeline omitted an authoritative stage certificate");
                    } catch(Exception error) {
                        if(ledger.state(id)==BusinessState.RUNNING)
                            ledger.state(id,BusinessState.IN_DOUBT,contribution.getStepExecution().getJobExecutionId(),
                                    "Owner outcome requires reconciliation: "+error.getClass().getSimpleName());
                        throw error;
                    }
                    return RepeatStatus.FINISHED;
                },manager).transactionAttribute(transaction).build();
        return new JobBuilder(MainStrategyDailyWork.JOB,repository).start(step).build();
    }
}
