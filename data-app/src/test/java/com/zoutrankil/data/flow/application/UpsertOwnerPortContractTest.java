package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.flow.domain.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.flow.storage.*;
import com.zoutrankil.data.margin.application.*;
import com.zoutrankil.data.margin.domain.MarginDetailTargetRange;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.margin.storage.MarginDetailWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite run state with independently created offline target sessions. */
@SuppressWarnings({"unchecked","rawtypes"})
class UpsertOwnerPortContractTest {
    @TempDir Path temp;
    static final LocalDate DAY=UpsertSourceContractTest.DAY;
    static final String ID="static-v2-"+"a".repeat(64),OTHER="static-v2-"+"b".repeat(64);
    enum Family {
        MONEYFLOW(MoneyflowSyncJobOwner.DEFINITION,"java_d024_moneyflow_owner",MoneyflowSource.class),
        DC(MoneyflowDcSyncJobOwner.DEFINITION,"java_d026_moneyflow_dc_owner",MoneyflowDcSource.class),
        THS(MoneyflowThsSyncJobOwner.DEFINITION,"java_d025_moneyflow_ths_owner",MoneyflowThsSource.class),
        DETAIL(MarginDetailSyncJobOwner.DEFINITION,"java_d029_margin_detail_owner",MarginDetailSource.class);
        final SyncJobDefinition definition;final String table;final Class<?> source;
        Family(SyncJobDefinition d,String t,Class<?> s){definition=d;table=t;source=s;}
    }

    @ParameterizedTest @EnumSource(Family.class)
    void planningKeepsFiniteBoundsAndFrozenBaselineMetadata(Family family)throws Exception {
        var h=new Harness(family,temp.resolve("plan.sqlite"),null);h.failPreflight=false;
        if(family==Family.THS) {
            var p=((MoneyflowThsJobService)h.job).plan(Mode.INCREMENTAL,DAY,DAY,DAY.plusDays(1));
            assertEquals(new MoneyflowThsTargetRange(null,null),p.physicalRange());assertTrue(p.bootstrap());assertEquals(0,p.checkedPhysicalRows());
            assertEquals(Set.of("targetId","checkpointAnchor"),p.request().parameters().keySet());
            assertThrows(IllegalArgumentException.class,()->((MoneyflowThsJobService)h.job).plan(Mode.BACKFILL,DAY.minusDays(366),DAY,DAY.plusDays(1)));
        } else {
            FrozenRequest request=switch(family){
                case MONEYFLOW->{var p=((MoneyflowJobService)h.job).planDetailed(Mode.INCREMENTAL,DAY,DAY.plusDays(20),DAY.plusDays(21));assertTrue(p.cappedByBudget());assertTrue(p.bootstrap());assertEquals(new MoneyflowTargetRange(null,null,0),p.targetBefore());yield p.request();}
                case DC->{var p=((MoneyflowDcJobService)h.job).planDetailed(Mode.INCREMENTAL,DAY,DAY.plusDays(20),DAY.plusDays(21));assertTrue(p.cappedByBudget());assertTrue(p.bootstrap());assertEquals(new MoneyflowDcTargetRange(null,null,0),p.targetBefore());yield p.request();}
                case DETAIL->{var p=((MarginDetailJobService)h.job).planDetailed(Mode.INCREMENTAL,DAY,DAY.plusDays(20),DAY.plusDays(21));assertTrue(p.cappedByBudget());assertTrue(p.bootstrap());assertEquals(new MarginDetailTargetRange(null,null,0),p.physicalRange());yield p.request();}
                default->throw new AssertionError();};
            assertEquals(DAY.plusDays(family==Family.DETAIL?13:4),request.to());assertEquals(DAY,request.parameters().get("checkpointAnchor"));
            assertEquals(family==Family.DETAIL?"0":0,request.parameters().get("targetRowsBefore"));assertFalse(request.parameters().containsKey("checkpointBefore"));
        }
        assertFalse(Files.exists(h.ledger));verifyNoInteractions(h.pages);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void resumeKeepsFamilyParentIdentityAndFreshWriterSourcePerAttempt(Family family)throws Exception {
        var h=new Harness(family,temp.resolve("resume.sqlite"),null);var request=request(family);
        failed(h.ledger,request);var roots=new ArrayList<Path>();
        try(var sources=mockConstruction((Class)family.source,(mock,context)->roots.add((Path)context.arguments().get(1)))) {
            var a=h.resume("prior");var b=h.resume("prior");
            assertEquals(SyncRunState.FAILED,a.state());assertEquals(SyncRunState.FAILED,b.state());assertNotEquals(a.runId(),b.runId());
            assertEquals(2,h.writers.size());assertNotSame(h.writers.get(0),h.writers.get(1));assertEquals(2,sources.constructed().size());
            for(var r:List.of(a,b)) {
                var stored=SyncRunLedger.openReadOnly(h.ledger).getRun(r.runId());
                assertEquals(family==Family.THS?null:"prior",stored.parentRunId());
                assertEquals(SyncRequestIdentity.snapshotJson(request),stored.frozenJson());
                assertTrue(roots.contains(h.ledger.getParent().resolve("sync-evidence").resolve(r.runId()).resolve("source")));
            }
            for(var w:h.writers){verify(w).preflight();verify(w,never()).send(anyList());verify(w,never()).readback(anyList());}
            verifyNoInteractions(h.pages,h.jobs);
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void changedTargetRejectsBeforeWriterOrNewLedger(Family family)throws Exception {
        var h=new Harness(family,temp.resolve("drift.sqlite"),null);h.identity=OTHER;
        assertThrows(IllegalStateException.class,()->h.run(request(family)));
        assertEquals(0,h.writers.size());assertFalse(Files.exists(h.ledger));verifyNoInteractions(h.pages,h.jobs);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void parentOnlyCancellationDoesNotPropagateToTheOriginalSingleOwnerRunner(Family family)throws Exception {
        var h=new Harness(family,temp.resolve("parent.sqlite"),null);h.failPreflight=false;
        var request=request(family);var ledger=new SyncRunLedger(h.ledger);ledger.createRun("parent",null,ID,request);assertTrue(ledger.requestCancellation("parent"));
        var sourceFamily=UpsertSourceContractTest.Family.valueOf(family.name());
        var provider=new UpsertSourceContractTest.Pages(List.of(UpsertSourceContractTest.raw(sourceFamily,"000001.SZ"),UpsertSourceContractTest.raw(sourceFamily,"600000.SH")));
        var page=UpsertSourceContractTest.fetch(sourceFamily,provider,temp.resolve("actual-source"),()->false);
        var fetched=new java.util.concurrent.atomic.AtomicInteger();
        try(var sources=mockConstruction((Class)family.source,withSettings().defaultAnswer(invocation->{
            if(invocation.getMethod().getName().equals("fetch")) {
                assertEquals(DAY,invocation.getArgument(0));
                assertFalse(((java.util.function.BooleanSupplier)invocation.getArgument(1)).getAsBoolean());
                fetched.incrementAndGet();return page;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        }))) {
            var result=h.group("child","parent",request);
            assertEquals(SyncRunState.VERIFIED,result.state());assertNull(result.errorCode());assertEquals(2,result.sourceRows());assertEquals(2,result.verifiedRows());
            assertEquals(1,fetched.get());assertEquals(1,sources.constructed().size());
            assertEquals("parent",ledger.getRun("child").parentRunId());assertTrue(ledger.cancellationRequested("parent"));assertFalse(ledger.cancellationRequested("child"));
            assertEquals(SyncRunState.VERIFIED,ledger.get("child").state());
            var completion=JobDefinitionJson.mapper().readTree(h.ledger.getParent().resolve("sync-evidence/child/complete-window.json").toFile());
            assertTrue(completion.path("complete").asBoolean());assertEquals(2,completion.path("sourceRows").asInt());
            assertEquals(page.sourceFingerprint(),completion.path("sourceReceipts").get(0).path("sourceFingerprint").asText());
            verify((VerifiedWriteSession)h.writers.getLast()).send(eq(page.rows()));verifyNoInteractions(h.pages,h.jobs);
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void explicitChildCancellationAfterPreflightStopsBeforeSourceOrSend(Family family)throws Exception {
        var h=new Harness(family,temp.resolve("child-cancel.sqlite"),null);h.failPreflight=false;h.cancelChildAtPreflight="child";
        var request=request(family);var ledger=new SyncRunLedger(h.ledger);ledger.createRun("parent",null,ID,request);
        try(var sources=mockConstruction((Class)family.source)) {
            var result=h.group("child","parent",request);
            assertEquals(SyncRunState.CANCELLED,result.state());assertEquals("CancellationException",result.errorCode());
            assertEquals("parent",ledger.getRun("child").parentRunId());assertTrue(ledger.cancellationRequested("child"));assertFalse(ledger.cancellationRequested("parent"));
            assertEquals(SyncRunState.CANCELLED,ledger.get("child").state());assertEquals(1,sources.constructed().size());
            sources.constructed().forEach(source->verifyNoInteractions(source));
            for(var writer:h.writers)verify(writer,never()).send(anyList());verifyNoInteractions(h.pages,h.jobs);
            assertFalse(Files.exists(h.ledger.getParent().resolve("sync-evidence/child/complete-window.json")));
        }
    }

    @Test void formalMoneyflowBackfillNeverClaimsAnIncrementalCheckpoint()throws Exception {
        var h=new Harness(Family.MONEYFLOW,temp.resolve("formal.sqlite"),"moneyflow");h.failPreflight=false;
        var job=(MoneyflowJobService)h.job;
        assertThrows(IllegalArgumentException.class,()->job.planDetailed(Mode.INCREMENTAL,DAY,DAY,DAY.plusDays(1)));assertEquals(0,h.writers.size());
        var p=job.planDetailed(Mode.BACKFILL,DAY,DAY,DAY.plusDays(1));
        assertFalse(p.request().parameters().containsKey("checkpointAnchor"));assertFalse(p.request().parameters().containsKey("checkpointBefore"));
        assertNull(p.checkpointAnchor());assertNull(p.checkpointBefore());assertFalse(p.bootstrap());
        assertThrows(IllegalArgumentException.class,()->job.planDetailed(Mode.BACKFILL,DAY,DAY.plusDays(5),DAY.plusDays(6)));
    }

    @Test void formalDetailWindowRejectsFifteenDaysBeforeIdentityAndKeepsStringPhysicalCount()throws Exception {
        var h=new Harness(Family.DETAIL,temp.resolve("formal-detail.sqlite"),"margin_detail");h.failPreflight=false;
        var job=(MarginDetailJobService)h.job;
        assertThrows(IllegalArgumentException.class,()->job.planDetailed(Mode.BACKFILL,DAY,DAY.plusDays(14),DAY.plusDays(15)));
        assertEquals(0,h.identityCalls);assertEquals(0,h.writers.size());
        var p=job.planDetailed(Mode.BACKFILL,DAY,DAY.plusDays(13),DAY.plusDays(14));
        assertEquals(DAY,p.checkpointAnchor());assertEquals("0",p.request().parameters().get("targetRowsBefore"));assertFalse(p.cappedByBudget());
        assertFalse(p.request().parameters().containsKey("checkpointBefore"));assertEquals(2,h.identityCalls);verifyNoInteractions(h.pages);
    }

    static FrozenRequest request(Family f){
        var p=new LinkedHashMap<String,Object>();p.put("targetId",ID);p.put("checkpointAnchor",DAY);
        if(f!=Family.THS){p.put("trade_dates","20260917");p.put("targetRowsBefore",f==Family.DETAIL?"0":0);}
        return f.definition.freeze(Mode.INCREMENTAL,p,DAY,DAY,DAY.plusDays(1));
    }
    static void failed(Path path,FrozenRequest request)throws Exception {var l=new SyncRunLedger(path);l.createRun("prior",null,ID,request);l.transition("prior",0,SyncRunState.FAILED,"{}");}
    static ExchangeCalendarReadPort calendar(){return query->{
        var rows=new ArrayList<ExchangeCalendar>();var exchanges=query.equalities().containsKey("exchange")?List.of("SSE"):List.of("SSE","SZSE");
        for(LocalDate day=(LocalDate)query.fromInclusive();day.isBefore((LocalDate)query.toExclusive());day=day.plusDays(1))for(String exchange:exchanges)rows.add(new ExchangeCalendar(exchange,day,true,day.minusDays(1)));
        return new DatasetReadPage<>("exchange_calendar",1,null,Instant.EPOCH,rows,null);
    };}
    static final class Harness {
        final Family family;final Path ledger;final Object job;final TusharePageService pages=mock(TusharePageService.class);final SyncJobRegistry jobs=mock(SyncJobRegistry.class);
        final List<VerifiedWriteSession<?,?>> writers=new ArrayList<>();String identity=ID;int identityCalls;boolean failPreflight=true;
        String cancelChildAtPreflight;int preflightCalls;
        Harness(Family f,Path path,String explicitTable){family=f;ledger=path.toAbsolutePath().normalize();String table=explicitTable==null?f.table:explicitTable;
            when(jobs.prepare(eq(f.definition.jobId()),eq(f.definition.version()),any(),anyMap(),any(),any(),any()))
                    .thenAnswer(i->f.definition.freeze(i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6)));
            job=switch(f){
                case MONEYFLOW->{var t=mock(MoneyflowTarget.class);when(t.tableName()).thenReturn(table);when(t.targetId()).thenAnswer(i->identity());when(t.newWriter(ID)).thenAnswer(i->writer());yield new MoneyflowJobService(jobs,pages,calendar(),t,ledger);}
                case DC->{var t=mock(MoneyflowDcTarget.class);when(t.tableName()).thenReturn(table);when(t.targetId()).thenAnswer(i->identity());when(t.newWriter(ID)).thenAnswer(i->writer());yield new MoneyflowDcJobService(jobs,pages,calendar(),t,ledger);}
                case THS->{var t=mock(MoneyflowThsTarget.class);when(t.tableName()).thenReturn(table);when(t.targetId()).thenAnswer(i->identity());when(t.newWriter(ID)).thenAnswer(i->writer());yield new MoneyflowThsJobService(jobs,pages,calendar(),t,ledger);}
                case DETAIL->{var t=mock(MarginDetailTarget.class);when(t.tableName()).thenReturn(table);when(t.targetId()).thenAnswer(i->identity());when(t.newWriter(ID)).thenAnswer(i->writer());yield new MarginDetailJobService(jobs,pages,calendar(),t,ledger);}
            };
        }
        String identity(){identityCalls++;return identity;}
        VerifiedWriteSession writer()throws Exception {
            VerifiedWriteSession w=switch(family){
                case MONEYFLOW->{var x=mock(MoneyflowWriteSession.class);when(x.readTargetRange()).thenReturn(new MoneyflowTargetRange(null,null,0));when(x.codec()).thenReturn(MoneyflowWritePort.CODEC);yield x;}
                case DC->{var x=mock(MoneyflowDcWriteSession.class);when(x.readTargetRange()).thenReturn(new MoneyflowDcTargetRange(null,null,0));when(x.codec()).thenReturn(MoneyflowDcWritePort.CODEC);yield x;}
                case THS->{var x=mock(MoneyflowThsWriteSession.class);when(x.readTargetRange()).thenReturn(new MoneyflowThsTargetRange(null,null));when(x.codec()).thenReturn(MoneyflowThsWritePort.CODEC);yield x;}
                case DETAIL->{var x=mock(MarginDetailWriteSession.class);when(x.readTargetRange()).thenReturn(new MarginDetailTargetRange(null,null,0));when(x.codec()).thenReturn(MarginDetailWritePort.CODEC);yield x;}
            };
            var stored=new ArrayList<Object>();
            doAnswer(i->{stored.clear();stored.addAll(i.<List<?>>getArgument(0));return null;}).when(w).send(anyList());
            when(w.readback(anyList())).thenAnswer(i->List.copyOf(stored));when(w.walSettled()).thenReturn(true);
            when(((DateSliceReadPort)w).readDate(any())).thenAnswer(i->List.copyOf(stored));
            doAnswer(i->{
                if(failPreflight)throw new IllegalStateException("stop before provider");
                // THS preflights once before Runner creates the child, then again inside Runner.
                if(++preflightCalls==(family==Family.THS?2:1)&&cancelChildAtPreflight!=null)
                    assertTrue(new SyncRunLedger(ledger).requestCancellation(cancelChildAtPreflight));
                return null;
            }).when(w).preflight();writers.add(w);return w;
        }
        SyncJobRunner.Result run(FrozenRequest request)throws Exception{return switch(family){case MONEYFLOW->((MoneyflowJobService)job).run(request);case DC->((MoneyflowDcJobService)job).run(request);case THS->((MoneyflowThsJobService)job).run(new MoneyflowThsJobService.Plan(request,ID,null,DAY,new MoneyflowThsTargetRange(null,null),true,0));case DETAIL->((MarginDetailJobService)job).run(request);};}
        SyncJobRunner.Result resume(String prior)throws Exception{return switch(family){case MONEYFLOW->((MoneyflowJobService)job).resume(prior);case DC->((MoneyflowDcJobService)job).resume(prior);case THS->((MoneyflowThsJobService)job).resume(prior);case DETAIL->((MarginDetailJobService)job).resume(prior);};}
        SyncJobRunner.Result group(String run,String parent,FrozenRequest request)throws Exception{return switch(family){case MONEYFLOW->((MoneyflowJobService)job).runAsGroupChild(run,parent,null,ID,request);case DC->((MoneyflowDcJobService)job).runAsGroupChild(run,parent,null,ID,request);case THS->((MoneyflowThsJobService)job).runAsGroupChild(run,parent,ID,request);case DETAIL->((MarginDetailJobService)job).runAsGroupChild(run,parent,null,ID,request);};}
    }
}
