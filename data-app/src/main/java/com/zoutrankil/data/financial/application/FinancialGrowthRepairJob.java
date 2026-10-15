package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.financial.domain.FinancialGrowthPatch;
import com.zoutrankil.data.financial.domain.FinancialGrowthPatch.Key;
import com.zoutrankil.data.financial.storage.QuestDbFinancialGrowthRepair;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Bounded financial null enrichment. The shared ledger owns submission and uncertainty. */
public final class FinancialGrowthRepairJob {
    private FinancialGrowthRepairJob() {}
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.fina_indicator_growth_repair",1,"fina_indicator",1,"financial_growth_owner",
            Set.of(Mode.RECONCILE),Mode.RECONCILE,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                    "sourceSha256",new Parameter(ParameterType.STRING,true,64,1,Set.of()),
                    "planSha256",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
            "local.frozen_financial_response","financial_growth.exact_keys","questdb.full_key_values",
            new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofMinutes(3)),Duration.ofHours(1),
            new Budget(36600,400,400,20000,1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    public static Map<String,Object> execute(JdbcTemplate jdbc,Path ledgerPath,Path sourcePath,
            Path evidenceRoot,LocalDate logicalDate,boolean publish)throws Exception {
        if(logicalDate.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException("Future logical date");
        var source=FinancialGrowthEvidence.read(sourcePath,logicalDate);
        var discovery=new QuestDbFinancialGrowthRepair(jdbc,"");String targetId=discovery.targetId();
        var port=new QuestDbFinancialGrowthRepair(jdbc,targetId);var plan=port.plan(source);
        Path root=evidenceRoot.toAbsolutePath().normalize();Files.createDirectories(root.getParent());Files.createDirectory(root);
        var json=JobDefinitionJson.canonicalMapper();String runId="financial-growth-"+UUID.randomUUID();
        byte[] baseline=json.writeValueAsBytes(plan.before());
        if(baseline.length>128*1024*1024)throw new IllegalArgumentException("Quarter baseline exceeds 128 MiB");
        FileEvidenceStore.writeNewDurable(root.resolve("before.json"),baseline);
        Path patchFile=root.resolve("patches.json");byte[] patchBytes=json.writeValueAsBytes(plan.patches());
        if(patchBytes.length>128*1024*1024)throw new IllegalArgumentException("Financial patches exceed 128 MiB");
        FileEvidenceStore.writeNewDurable(patchFile,patchBytes);String planSha=FileEvidenceStore.sha256(patchBytes);
        var report=new LinkedHashMap<String,Object>();report.put("runId",runId);report.put("targetId",targetId);
        report.put("sourcePath",sourcePath.toAbsolutePath().normalize().toString());report.put("sourceSha256",source.sha256());
        report.put("sourceRows",source.sourceRows());report.put("sourceKeys",source.rows().size());report.put("period",source.period());
        report.put("targetRows",plan.before().size());report.put("patchRows",plan.patches().size());report.put("skipped",plan.skipped());
        report.put("beforeSha256",FileEvidenceStore.sha256(baseline));report.put("planSha256",planSha);report.put("beforeState",plan.state());
        report.put("writerPid",ProcessHandle.current().pid());report.put("sourceComplete",false);report.put("executed",false);
        FileEvidenceStore.writeNewDurable(root.resolve("plan.json"),json.writeValueAsBytes(report));
        if(!publish || plan.patches().isEmpty())return report;
        LocalDate from=plan.patches().stream().map(p->p.key().announcement()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to=plan.patches().stream().map(p->p.key().announcement()).max(Comparator.naturalOrder()).orElseThrow();
        var request=DEFINITION.freeze(Mode.RECONCILE,Map.of("targetId",targetId,"sourceSha256",source.sha256(),"planSha256",planSha),from,to,logicalDate);
        var expected=new HashMap<Key,Map<String,Object>>();plan.patches().forEach(p->expected.put(p.key(),p.after()));
        var expectedQuarter=plan.before().stream().map(row->expected.getOrDefault(QuestDbFinancialGrowthRepair.key(row),row)).toList();
        String expectedDigest=FileEvidenceStore.sha256(json.writeValueAsBytes(expectedQuarter));
        var adapter=new SyncJobRunner.Adapter<FinancialGrowthPatch,Key>() {
            private boolean quarterVerified;
            public boolean recoveryRequired(String id){return port.writeAttempted() && !quarterVerified;}
            private void sourceUnchanged()throws Exception {
                byte[] current=FileEvidenceStore.readBounded(sourcePath,64*1024*1024,()->new IllegalStateException("Financial evidence enlarged"));
                if(!source.sha256().equals(FileEvidenceStore.sha256(current))
                        || !planSha.equals(FileEvidenceStore.sha256(FileEvidenceStore.readBounded(patchFile,128*1024*1024,()->new IllegalStateException("Patch evidence enlarged")))))
                    throw new IllegalStateException("Frozen financial source or plan changed");
            }
            public void preflight(FrozenRequest frozen)throws Exception {sourceUnchanged();port.preflight();}
            public DatasetIntervalLock.Scope conflictScope(FrozenRequest frozen){return DatasetIntervalLock.Scope.allDates("fina_indicator");}
            public int maxWriteBatchRows(FrozenRequest frozen){return 64;}
            public Duration visibilityTimeout(){return Duration.ofMinutes(3);}
            public VerifiedBatchExecutor.Codec<FinancialGrowthPatch,Key> codec(){return QuestDbFinancialGrowthRepair.CODEC;}
            public VerifiedBatchExecutor.Port<FinancialGrowthPatch,Key> port(){return port;}
            public SyncJobRunner.SourceCompletion fetch(FrozenRequest frozen,SyncJobRunner.PageConsumer<FinancialGrowthPatch> consumer,BooleanSupplier stopped)throws Exception {
                sourceUnchanged();
                if(!plan.state().equals(port.state()) || !port.walSettled())throw new IllegalStateException("Quarter changed before acquired-lock execution");
                int pages=0;
                for(int start=0;start<plan.patches().size();start+=64) {
                    if(stopped.getAsBoolean())throw new java.util.concurrent.CancellationException("Financial repair cancelled");
                    int end=Math.min(start+64,plan.patches().size());
                    String hash=FileEvidenceStore.sha256((planSha+"\n"+start+"\n"+end).getBytes(StandardCharsets.UTF_8));
                    consumer.accept(new SyncJobRunner.Page<>(plan.patches().subList(start,end),hash,patchFile.toString(),Integer.toString(pages++)));
                }
                sourceUnchanged();var beforeRead=port.state();var actual=port.readPeriod(source.period());
                String actualDigest=FileEvidenceStore.sha256(json.writeValueAsBytes(actual));
                if(!expectedDigest.equals(actualDigest) || !beforeRead.equals(port.state()) || !port.walSettled())
                    throw new IllegalStateException("Complete-quarter readback differs; retain evidence for inspection");
                Path proof=root.resolve("verified-quarter.json");
                FileEvidenceStore.writeNewDurable(proof,json.writeValueAsBytes(Map.of("runId",runId,"targetId",targetId,
                        "targetRows",actual.size(),"patchedRows",plan.patches().size(),"allOtherValuesUnchanged",true,
                        "expectedDigest",expectedDigest,"actualDigest",actualDigest,"state",beforeRead,"sourceComplete",false)));
                quarterVerified=true;
                return new SyncJobRunner.SourceCompletion(pages,plan.patches().size(),false,proof.toString());
            }
        };
        var result=SyncRunExecution.execute(ledgerPath,runId,null,targetId,request,"Cannot read financial cancellation",()->adapter);
        report.put("executed",true);report.put("result",result);
        FileEvidenceStore.writeNewDurable(root.resolve("result.json"),json.writeValueAsBytes(report));
        return report;
    }
}
