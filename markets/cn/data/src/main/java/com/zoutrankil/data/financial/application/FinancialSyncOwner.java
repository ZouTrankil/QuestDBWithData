package com.zoutrankil.data.financial.application;

import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.financial.client.FinancialSource;
import com.zoutrankil.data.financial.domain.FinancialContract;
import com.zoutrankil.data.financial.domain.FinancialContract.*;
import com.zoutrankil.data.financial.port.FinancialTarget;
import com.zoutrankil.data.financial.storage.FinancialWritePort;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** 复用正式账本、整表锁与未决写入协议；冻结证券和报告期，不使用 MAX(ann_date) 当进度。 */
public final class FinancialSyncOwner implements DatasetImplementation,SyncJobOwner {
    private final FinancialContract contract;private final FinancialTarget target;private final TusharePageService pages;private final Path ledger;
    public FinancialSyncOwner(FinancialContract contract,FinancialTarget target,TusharePageService pages,Path ledger){this.contract=contract;this.target=target;this.pages=pages;this.ledger=ledger.toAbsolutePath().normalize();}
    public String datasetId(){return contract.dataset();}
    public DatasetDefinition definition(){return contract.definition();}
    public Set<Mode> supportedSyncModes(){return Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE);}
    public List<SyncJobDefinition> syncJobDefinitions(){return List.of(job());}
    public SyncJobDefinition job(){return new SyncJobDefinition("data."+datasetId(),1,datasetId(),1,"financial_owner",supportedSyncModes(),Mode.INCREMENTAL,
            Map.of("codes",new Parameter(ParameterType.STRING_LIST,true,12,6000,Set.of()),"periods",new Parameter(ParameterType.STRING_LIST,true,10,16,Set.of())),
            "tushare.shared","financial.code_period","questdb.full_key_values",new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofHours(12),
            new Budget(36600,100000,100000,10000000,1024*1024),0,List.of(),Frequency.DAILY,ZoneId.of("Asia/Shanghai"),true,false);}
    public FrozenRequest plan(List<String> codes,List<LocalDate> periods,LocalDate logical,Mode mode) {
        if(codes.isEmpty()||codes.size()>6000||new HashSet<>(codes).size()!=codes.size()||codes.stream().anyMatch(c->!c.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                ||periods.isEmpty()||periods.size()>16||new HashSet<>(periods).size()!=periods.size()
                ||periods.stream().anyMatch(p->!FinancialContract.quarterEnd(p)||p.isAfter(logical)))throw new IllegalArgumentException("需明确唯一证券及已结束报告期");
        var sorted=periods.stream().sorted().toList();
        return job().freeze(mode,Map.of("codes",codes.stream().sorted().toList(),"periods",sorted.stream().map(LocalDate::toString).toList()),sorted.getFirst(),sorted.getLast(),logical);
    }
    /** 近期四个报告期反复核对；另轮巡一个旧期，修订范围不会永远停在最新公告之后。 */
    public static List<LocalDate> dailyPeriods(LocalDate logical,LocalDate earliest) {
        if(!FinancialContract.quarterEnd(earliest)||earliest.isAfter(logical))throw new IllegalArgumentException("历史起点需为已结束季度末");
        var all=new ArrayList<LocalDate>();LocalDate p=earliest;
        while(!p.isAfter(logical)){all.add(p);p=p.plusMonths(3).with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());if(all.size()>400)throw new IllegalArgumentException("历史范围超过 100 年");}
        int recent=Math.max(0,all.size()-4);var result=new TreeSet<>(all.subList(recent,all.size()));
        if(recent>0)result.add(all.get(Math.floorMod(logical.toEpochDay(),recent)));return List.copyOf(result);
    }
    public SyncJobRunner.Result run(FrozenRequest request)throws Exception{return execute(request,null,Duration.ofHours(12));}
    /** 日批共享剩余时间；沿用同一冻结请求和恢复身份，不修改持久化任务定义。 */
    public SyncJobRunner.Result runWithin(FrozenRequest request,Duration remaining)throws Exception {
        if(remaining.isNegative()||remaining.isZero()||remaining.compareTo(Duration.ofHours(12))>0)throw new IllegalArgumentException("财务执行剩余时间需在 0 到 12 小时内");
        return execute(request,null,remaining);
    }
    public SyncJobRunner.Result resume(String previous)throws Exception {
        var saved=FrozenRunRequest.restore(ledger,previous,job());if(!target.targetId().equals(saved.targetId()))throw new IllegalStateException("财务恢复目标已重建");
        return execute(saved.request(),previous,Duration.ofHours(12));
    }
    private SyncJobRunner.Result execute(FrozenRequest request,String previous,Duration remaining)throws Exception {
        final long deadline=System.nanoTime()+remaining.toNanos();
        if(!request.definition().equals(job()))throw new IllegalArgumentException("财务任务定义不匹配");
        @SuppressWarnings("unchecked") var codes=(List<String>)request.parameters().get("codes");
        @SuppressWarnings("unchecked") var textPeriods=(List<String>)request.parameters().get("periods");
        var periods=textPeriods.stream().map(LocalDate::parse).toList();
        if(!SyncRequestIdentity.snapshotJson(plan(codes,periods,request.logicalDate(),request.mode())).equals(SyncRequestIdentity.snapshotJson(request)))throw new IllegalArgumentException("财务冻结请求被改变");
        String targetId=target.targetId(),id="financial-"+datasetId()+"-"+UUID.randomUUID();Path evidence=ledger.getParent().resolve("sync-evidence").resolve(id);
        var source=new FinancialSource(pages,contract,evidence);
        var adapter=new SyncJobRunner.Adapter<Row,Key>() {
            void stable()throws Exception {if(!targetId.equals(target.targetId()))throw new IllegalStateException("财务物理目标已改变");}
            public void preflight(FrozenRequest r)throws Exception{stable();target.preflight();}
            public DatasetIntervalLock.Scope conflictScope(FrozenRequest r){return DatasetIntervalLock.Scope.allDates(datasetId());}
            public int maxWriteBatchRows(FrozenRequest r){return 100;}
            public Duration visibilityTimeout(){return Duration.ofMinutes(3);}
            public VerifiedBatchExecutor.Codec<Row,Key> codec(){return FinancialWritePort.CODEC;}
            public VerifiedBatchExecutor.Port<Row,Key> port(){return new VerifiedBatchExecutor.Port<>() {
                public void preflight()throws Exception{stable();target.preflight();}
                public void send(List<Row> rows)throws Exception{stable();target.send(rows);}
                public List<Row> readback(List<Key> keys)throws Exception{stable();return target.readback(keys);}
                public boolean walSettled()throws Exception{stable();return target.walSettled();}
            };}
            public SyncJobRunner.SourceCompletion fetch(FrozenRequest r,SyncJobRunner.PageConsumer<Row> consumer,BooleanSupplier stopped)throws Exception {
                // 只停止后续采集；已投递的写入仍由原协议核验或保留未决状态。
                BooleanSupplier limited=()->stopped.getAsBoolean()||System.nanoTime()-deadline>=0;
                int count=0,total=0;var receipts=new ArrayList<String>();
                for(var period:periods)for(var code:codes){if(limited.getAsBoolean())throw new java.util.concurrent.CancellationException();
                    var page=source.fetch(code,period,r.logicalDate(),limited);var preserved=target.preserveLocalColumns(page.rows());
                    Path frozen=evidence.resolve("write-input-"+UUID.randomUUID()+".json");byte[] values=JobDefinitionJson.canonicalMapper().writeValueAsBytes(preserved);
                    FileEvidenceStore.writeNewDurable(frozen,JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of("source",page.responseEvidence(),"sourceSha256",page.sourceFingerprint(),"preservedLocalColumns",true,"rows",preserved)));
                    consumer.accept(new SyncJobRunner.Page<>(preserved,FileEvidenceStore.sha256(values),frozen.toString(),null));count++;total+=preserved.size();receipts.add(frozen.toString());}
                stable();Path proof=evidence.resolve("completion.json");
                FileEvidenceStore.writeNewDurable(proof,JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of("request",SyncRequestIdentity.snapshotJson(r),"targetId",targetId,
                        "sourceComplete",true,"scope","explicit codes and report periods; income/cashflow report_type=1","pages",count,"rows",total,"receipts",receipts)));
                return new SyncJobRunner.SourceCompletion(count,total,true,proof.toString());
            }
        };
        return SyncRunExecution.execute(ledger,id,previous,targetId,request,"财务取消状态不可读",()->adapter);
    }
}
