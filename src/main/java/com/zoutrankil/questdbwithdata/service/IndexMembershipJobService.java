package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.IndexMembershipStorage;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Registered manual SW2021 membership owner; execution is serial by industry. */
@Service
public final class IndexMembershipJobService implements SyncJobOwner {
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,String classificationReceipt,
                       String classificationFingerprint) {}
    private final JdbcTemplate jdbc;
    private final TusharePageService pages;
    private final Path ledgerPath;
    private final String table;
    @org.springframework.beans.factory.annotation.Autowired
    public IndexMembershipJobService(JdbcTemplate jdbc,TusharePageService pages,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            @Value("${app.sync.index-member-table:index_member}") String table) {
        this(jdbc,pages,Path.of(ledgerPath),table);
    }
    public IndexMembershipJobService(JdbcTemplate jdbc,TusharePageService pages,Path ledgerPath,String table) {
        this.jdbc=Objects.requireNonNull(jdbc);this.pages=Objects.requireNonNull(pages);
        this.ledgerPath=ledgerPath.toAbsolutePath().normalize();DatasetDefinition.identifier(table);this.table=table;
    }
    @Override public String datasetId() { return "index_member"; }
    @Override public Set<SyncJobDefinition.Mode> supportedSyncModes() { return IndexMembershipJobPlan.definition().supportedModes(); }
    @Override public List<SyncJobDefinition> syncJobDefinitions() { return List.of(IndexMembershipJobPlan.definition()); }
    public String targetId() { return new IndexMembershipSliceJob(jdbc,pages,ledgerPath,table).targetId(); }
    public IndexMembershipClassificationSource.Catalog discover(Path outputDirectory) throws Exception {
        return new IndexMembershipClassificationSource(pages,outputDirectory)
                .fetch(()->Thread.currentThread().isInterrupted());
    }
    public SyncJobDefinition.FrozenRequest planFromReceipt(Path receipt,String fingerprint,List<String> industryCodes,
            IndexMembershipSource.Selection selection,LocalDate logicalDate) throws Exception {
        var catalog=IndexMembershipClassificationSource.reopen(receipt,fingerprint);
        return IndexMembershipJobPlan.freeze(catalog,industryCodes,selection,logicalDate);
    }
    public Plan plan(List<String> industryCodes,IndexMembershipSource.Selection selection,LocalDate logicalDate) throws Exception {
        var snapshot=new IndexMembershipStorage(jdbc,table).snapshot();
        String target=StaticTargetIdentity.identify(jdbc,table,snapshot.identity().id(),snapshot.identity().directory());
        Path folder=ledgerPath.getParent().resolve("sync-evidence").resolve("index-member-plan-"+UUID.randomUUID());
        var catalog=new IndexMembershipClassificationSource(pages,folder).fetch(()->Thread.currentThread().isInterrupted());
        var request=IndexMembershipJobPlan.freeze(catalog,industryCodes,selection,logicalDate);
        if(!target.equals(targetId())) throw new IllegalStateException("Membership target changed while planning");
        return new Plan(request,target,catalog.receipt(),catalog.fingerprint());
    }
    public Plan fromReceipt(Path receipt,String fingerprint,List<String> industryCodes,
                            IndexMembershipSource.Selection selection,LocalDate logicalDate) throws Exception {
        var catalog=IndexMembershipClassificationSource.reopen(receipt,fingerprint);
        var request=IndexMembershipJobPlan.freeze(catalog,industryCodes,selection,logicalDate);
        return new Plan(request,targetId(),catalog.receipt(),catalog.fingerprint());
    }
    public IndexMembershipBatchJob.Result run(SyncJobDefinition.FrozenRequest request) throws Exception {
        requireIsolatedWriteTarget();
        return new IndexMembershipBatchJob(jdbc,pages,ledgerPath,table).run(request);
    }
    public IndexMembershipBatchJob.Result resume(SyncJobDefinition.FrozenRequest request,String priorRun) throws Exception {
        requireIsolatedWriteTarget();
        return new IndexMembershipBatchJob(jdbc,pages,ledgerPath,table).resume(request,priorRun);
    }
    public IndexMembershipBatchJob.Result resume(String priorRun) throws Exception {
        return resume(IndexMembershipJobPlan.restore(SyncRunLedger.openReadOnly(ledgerPath).getRun(priorRun).frozenJson()),priorRun);
    }
    public IndexMembershipBatchJob.Result resumeStopped(SyncJobDefinition.FrozenRequest request,String priorRun,boolean writerStopped) throws Exception {
        requireIsolatedWriteTarget();
        return new IndexMembershipBatchJob(jdbc,pages,ledgerPath,table).resumeStopped(request,priorRun,writerStopped);
    }
    public IndexMembershipBatchJob.Result resumeStopped(String priorRun,boolean writerStopped) throws Exception {
        return resumeStopped(IndexMembershipJobPlan.restore(SyncRunLedger.openReadOnly(ledgerPath).getRun(priorRun).frozenJson()),
                priorRun,writerStopped);
    }
    public IndexMembershipSliceJob.Result finishInterruptedChild(String childRun,boolean writerStopped) throws Exception {
        requireIsolatedWriteTarget();
        return new IndexMembershipSliceJob(jdbc,pages,ledgerPath,table).finishInterrupted(childRun,writerStopped);
    }
    public SyncJobRunner.Result runAsGroupChild(String childRun,String parent,String expectedTarget,SyncJobDefinition.FrozenRequest request) throws Exception {
        requireIsolatedWriteTarget();
        if(!targetId().equals(expectedTarget)) throw new IllegalStateException("Membership group target changed before execution");
        var result=new IndexMembershipBatchJob(jdbc,pages,ledgerPath,table).runAsChild(childRun,parent,request);
        int rows=0;
        for(var member:result.members()) if(Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(member.state()))
            rows+=IndexMembershipCompletedRun.verify(jdbc,ledgerPath,table,member.childRunId(),
                    IndexMembershipJobPlan.restore(SyncRunLedger.openReadOnly(ledgerPath).getRun(member.childRunId()).frozenJson())).sourceRows();
        return new SyncJobRunner.Result(result.runId(),result.state(),rows,rows,result.errorCode());
    }
    public String revalidateGroupChild(String childRun,String expectedTarget,SyncJobDefinition.FrozenRequest request) throws Exception {
        return new IndexMembershipBatchJob(jdbc,pages,ledgerPath,table).revalidateGroupChild(childRun,expectedTarget,request);
    }
    public IndexMembershipPreparedJob.Result executePrepared(String run,String parent,
            SyncJobDefinition.FrozenRequest request,List<IndexMembership> rows,Path receipt) throws Exception {
        requireIsolatedWriteTarget();
        return new IndexMembershipPreparedJob(jdbc,ledgerPath,table).execute(run,parent,request,rows,receipt);
    }
    public String revalidatePreparedChild(String run,String target,SyncJobDefinition.FrozenRequest request) throws Exception {
        return new IndexMembershipPreparedJob(jdbc,ledgerPath,table).revalidate(run,target,request);
    }
    public IndexMembershipPreparedJob.Result finishPreparedChild(String run,boolean writerStopped) throws Exception {
        requireIsolatedWriteTarget();
        return IndexMembershipPreparedRecovery.finish(jdbc,ledgerPath,table,run,writerStopped);
    }
    private void requireIsolatedWriteTarget() {
        if(table.equals("index_member")) throw new IllegalStateException(
                "Formal index_member publication requires separate consumer cutover; configure an isolated membership table");
    }
}
