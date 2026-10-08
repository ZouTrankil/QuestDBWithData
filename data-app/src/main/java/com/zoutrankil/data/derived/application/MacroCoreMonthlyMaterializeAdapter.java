package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.derived.port.MacroCoreMonthlyWriteSession;
import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** D104 bounded Java materialization; one complete verified page, no native refresh. */
public final class MacroCoreMonthlyMaterializeAdapter implements SyncJobRunner.Adapter<MacroCoreMonthly,YearMonth> {
    private final MacroCoreMonthlySource source;
    private final MacroCoreMonthlyWriteSession writer;
    private final MacroCoreMonthlySourceData.Batch frozen;
    private SyncJobDefinition.FrozenRequest active;
    private MacroCoreMonthlyTargetSnapshot verifiedTarget;
    private MacroCoreMonthlySourceData.Batch verifiedSource;
    public MacroCoreMonthlyMaterializeAdapter(MacroCoreMonthlySource source,MacroCoreMonthlyWriteSession writer,MacroCoreMonthlySourceData.Batch frozen){
        this.source=Objects.requireNonNull(source);this.writer=Objects.requireNonNull(writer);this.frozen=Objects.requireNonNull(frozen);
    }
    @Override public DatasetIntervalLock.Scope conflictScope(SyncJobDefinition.FrozenRequest request){return DatasetIntervalLock.Scope.allDates(request.definition().datasetId());}
    @Override public void preflight(SyncJobDefinition.FrozenRequest request)throws Exception{
        requireRequest(request);active=request;requireStable();writer.preflight();
        if(!writer.targetId().equals(request.parameters().get("target_id")))throw new IllegalStateException("Frozen D104 target changed");
        requireStable();
    }
    private void requireRequest(SyncJobDefinition.FrozenRequest request){
        if(request==null||!request.definition().equals(MacroCoreMonthlyJobService.definition()))throw new IllegalArgumentException("Exact D104 frozen definition required");
        MacroCoreMonthlySource.requireClosedWindow(request.from(),request.to(),request.logicalDate());
        if(!(request.parameters().get("bootstrap_from") instanceof java.time.LocalDate anchor))throw new IllegalArgumentException("Explicit D104 bootstrap month required");
        MacroCoreMonthlySource.requireWindow(anchor,request.to());
        if(anchor.isAfter(request.from())||!frozen.snapshot().version().equals(request.parameters().get("source_version"))
                ||!frozen.rawFingerprint().equals(request.parameters().get("source_hash")))throw new IllegalStateException("D104 frozen source vector or full-field fingerprint differs");
    }
    private MacroCoreMonthlySourceData.Batch requireStable(){
        if(active==null)throw new IllegalStateException("D104 request is not bound");
        var current=source.read((LocalDate)active.parameters().get("bootstrap_from"),active.to());
        if(!frozen.snapshot().equals(current.snapshot())||!frozen.rawFingerprint().equals(current.rawFingerprint()))
            throw new IllegalStateException("D104 source changed after plan; no implicit replan");
        if(active.mode()==SyncJobDefinition.Mode.INCREMENTAL)MacroCoreMonthlySource.requireIncrementalCoverage(current.rows(),(LocalDate)active.parameters().get("bootstrap_from"),active.to());
        return current;
    }
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,SyncJobRunner.PageConsumer<MacroCoreMonthly> consumer,BooleanSupplier cancelled)throws Exception{
        requireRequest(request);active=request;check(cancelled);
        var current=requireStable();var rows=current.rows().stream().filter(row->!row.month().atDay(1).isBefore(request.from())).toList();
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("sourceSnapshot",current.snapshot());evidence.put("sourceIdentity",current.snapshot().identity());
        evidence.put("candidateMonths",current.candidateMonths());evidence.put("withheldMonths",current.withheldMonths());evidence.put("sfContextRows",current.sfContextRows());evidence.put("windowRowsBySource",current.windowRowsBySource());evidence.put("sourceVersion",current.snapshot().version());
        evidence.put("rawSourceFingerprint",current.rawFingerprint());evidence.put("rawSourceRows",current.rawRows());
        evidence.put("fullPrefixExpected",current.rows().stream().map(row->new MacroCoreMonthlyMapper().values(row).asMap()).toList());
        evidence.put("bootstrapFrom",request.parameters().get("bootstrap_from"));evidence.put("toInclusive",request.to());evidence.put("complete",true);
        String json=JobDefinitionJson.mapper().writeValueAsString(evidence);
        String fingerprint=MacroCoreMonthlySource.hash(current.fingerprint()+"\n"+request.from()+"\n"+request.to());
        consumer.accept(new SyncJobRunner.Page<>(rows,fingerprint,json,request.from()+"/"+request.to()));check(cancelled);
        var before=writer.targetSnapshot();
        var actual=writer.readActualRange(YearMonth.from((LocalDate)request.parameters().get("bootstrap_from")),YearMonth.from(request.to()));
        if(!exact(current.rows(),actual))throw new IllegalStateException("D104 full monthly prefix readback differs or contains duplicate/stale keys");
        var after=writer.targetSnapshot();if(!before.equals(after)||!after.settled())throw new IllegalStateException("D104 target changed during full prefix verification");
        verifiedSource=requireStable();verifiedTarget=after;
        return new SyncJobRunner.SourceCompletion(1,rows.size(),true,json);
    }
    public static boolean exact(List<MacroCoreMonthly> expected,List<MacroCoreMonthly> actual){
        if(actual==null||expected.size()!=actual.size())return false;
        var values=new HashMap<YearMonth,MacroCoreMonthly>();for(var row:actual)if(row==null||values.putIfAbsent(row.month(),row)!=null)return false;
        var seen=new HashSet<YearMonth>();for(var row:expected)if(row==null||!seen.add(row.month())||values.get(row.month())==null||!MacroCoreMonthlyWriteSession.CODEC.equivalent(row,values.get(row.month())))return false;
        return true;
    }
    @Override public VerifiedBatchExecutor.Codec<MacroCoreMonthly,YearMonth> codec(){return writer.codec();}
    @Override public VerifiedBatchExecutor.Port<MacroCoreMonthly,YearMonth> port(){
        return new VerifiedBatchExecutor.Port<>(){
            public void preflight()throws Exception{requireStable();writer.preflight();requireTarget();}
            public void send(List<MacroCoreMonthly> rows)throws Exception{
                requireStable();requireTarget();
                if(active.mode()==SyncJobDefinition.Mode.RECONCILE){
                    if(!exact(rows,writer.readback(rows.stream().map(MacroCoreMonthly::month).toList())))throw new IllegalStateException("D104 readonly reconcile values differ");
                }else writer.send(rows);
                requireStable();requireTarget();
            }
            public List<MacroCoreMonthly> readback(List<YearMonth> keys)throws Exception{requireStable();requireTarget();var actual=writer.readback(keys);requireStable();requireTarget();return actual;}
            public boolean walSettled()throws Exception{requireStable();requireTarget();return writer.walSettled();}
            public boolean uncertainSenderStopped()throws Exception{return active.mode()==SyncJobDefinition.Mode.RECONCILE||writer.uncertainSenderStopped();}
        };
    }
    private void requireTarget(){if(!writer.targetId().equals(active.parameters().get("target_id")))throw new IllegalStateException("D104 physical target identity changed");}
    @Override public boolean recoveryRequired(String runId){return writer.unresolved();}
    @Override public Duration visibilityTimeout(){return Duration.ofSeconds(20);}
    public MacroCoreMonthlyTargetSnapshot verifiedTarget(){return verifiedTarget;}
    public MacroCoreMonthlySourceData.Batch verifiedSource(){return verifiedSource;}
    public void requireUnchangedVerification()throws Exception{
        if(verifiedTarget==null||verifiedSource==null||!verifiedTarget.equals(writer.targetSnapshot()))throw new IllegalStateException("D104 lacks stable final actual verification");
        requireStable();
    }
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D104 materialization cancelled");}
}
