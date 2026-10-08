package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.port.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class L2IntradayBarFeaturesOwnerContractTest {
    private static final LocalDate DAY=LocalDate.of(2026,9,21);
    private static final String TABLE="java_d087_l2_intraday_bar_features_contract",ROOT="e".repeat(64),TARGET="questdb-"+"a".repeat(64);
    private static final List<String> SYMBOLS=List.of("000001.SZ","600001.SH");
    @TempDir Path temp;

    @Test void constructionAndInvalidPlanDoNotCreateLedgerOrUseSourceOrTargetIo()throws Exception{
        var h=new Harness(temp);assertFalse(Files.exists(h.ledger));assertTrue(h.trace.isEmpty());
        assertThrows(IllegalArgumentException.class,()->h.owner.plan(DAY,DAY.plusDays(31),DAY,null,SYMBOLS));
        assertThrows(IllegalArgumentException.class,()->h.owner.plan(DAY,DAY,DAY,null,List.of()));
        assertThrows(IllegalArgumentException.class,()->h.owner.plan(DAY,DAY,DAY,null,List.of("000001.SZ","000001.SZ")));
        assertThrows(IllegalArgumentException.class,()->h.owner.plan(DAY,DAY,DAY,null,List.of("bad")));
        assertThrows(IllegalArgumentException.class,()->h.owner.plan(DAY,DAY,DAY,SyncJobDefinition.Mode.MATERIALIZE,SYMBOLS));
        assertFalse(Files.exists(h.ledger));assertTrue(h.trace.isEmpty());verifyNoInteractions(h.source);
    }

    @Test void planningKeepsIdentityWriterPreflightLedgerCalendarInspectionAndFrontierOrder()throws Exception{
        var h=new Harness(temp);var plan=h.owner.plan(DAY,DAY,DAY,null,List.of("600001.SH","000001.SZ"));
        assertEquals(List.of("identity","writer","preflight","root","calendar","inspect","count","latest","root"),h.trace);
        assertEquals(List.of(false),h.writerSawLedger);assertTrue(Files.isRegularFile(h.ledger));
        assertEquals(SYMBOLS,plan.request().parameters().get("symbols"));assertEquals(ROOT,plan.request().parameters().get("source_root_id"));
        assertEquals(0,plan.targetDatesChecked());assertNull(plan.verifiedThrough());
        verify(h.source).inspect(DAY,DAY,SYMBOLS,300_000,10_000);
    }

    @Test void coldRunCreatesWriterAndAdapterBeforeLedgerAndPersistsRealVerifiedSlice()throws Exception{
        var h=new Harness(temp);var result=h.owner.run(h.frozen(DAY,SYMBOLS));
        assertEquals(SyncRunState.VERIFIED,result.state());assertEquals(2,result.sourceRows());assertEquals(2,result.verifiedRows());
        assertEquals(List.of(false),h.writerSawLedger);assertEquals(1,h.sessions.size());assertEquals(1,h.sends);
        assertEquals(List.of("identity","writer","root","preflight","inspect","stream","preflight","send"),h.trace);
        var ledger=new SyncRunLedger(h.ledger);assertNull(ledger.getRun(result.runId()).parentRunId());
        var slices=ledger.entries(result.runId(),null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        assertEquals(1,slices.size());assertEquals(SyncRunState.VERIFIED,slices.getFirst().state());
        var status=h.owner.status(result.runId());assertEquals(2,status.sourceRows());assertEquals(2,status.verifiedRows());
        assertEquals(1,status.verifiedSlices());assertEquals(0,status.unresolvedSlices());assertEquals(List.of(),status.errorCodes());
        assertFalse(h.owner.cancel(result.runId()));
    }

    @Test void resumeKeepsPriorAsParentAndRevalidatesWithFreshWriter()throws Exception{
        var h=new Harness(temp);var plan=h.frozen(DAY,SYMBOLS);var first=h.owner.run(plan);int sent=h.sends;
        var resumed=h.owner.resume(plan,first.runId());
        assertEquals(SyncRunState.VERIFIED,resumed.state());assertEquals(first.runId(),new SyncRunLedger(h.ledger).getRun(resumed.runId()).parentRunId());
        assertEquals(2,h.sessions.size());assertNotSame(h.sessions.get(0),h.sessions.get(1));
        assertEquals(2,resumed.reusedRows());assertEquals(sent,h.sends);
    }

    @Test void targetDriftRejectsBeforeWriterOrLedger()throws Exception{
        var h=new Harness(temp);var plan=h.frozen(DAY,SYMBOLS);h.targetId="questdb-"+"b".repeat(64);
        assertThrows(IllegalStateException.class,()->h.owner.run(plan));assertEquals(List.of("identity"),h.trace);
        assertFalse(Files.exists(h.ledger));verifyNoInteractions(h.source);
    }

    @Test void rootDriftRejectsBeforeWriterPreflightInspectionOrStream()throws Exception{
        var h=new Harness(temp);var plan=h.frozen(DAY,SYMBOLS);h.root="f".repeat(64);
        var result=h.owner.run(plan);assertEquals(SyncRunState.FAILED,result.state());
        assertEquals(List.of("identity","writer","root"),h.trace);assertEquals(0,h.sends);
    }

    @Test void currentRunCancellationDuringPreflightNeverFetchesOrSends()throws Exception{
        var h=new Harness(temp);h.cancelDuringPreflight=true;
        var result=h.owner.run(h.frozen(DAY,SYMBOLS));assertEquals(SyncRunState.CANCELLED,result.state());
        assertEquals(0,h.sends);assertFalse(h.trace.contains("stream"));assertTrue(new SyncRunLedger(h.ledger).cancellationRequested(result.runId()));
    }

    @ParameterizedTest @ValueSource(strings={"match","target","root","symbols"})
    void checkpointFiltersFrozenIdentityRootAndSymbolsAndKeepsThreeDayOverlap(String mismatch)throws Exception{
        var h=new Harness(temp);
        if(mismatch.equals("target"))h.targetId="questdb-"+"b".repeat(64);
        if(mismatch.equals("root"))h.root="f".repeat(64);
        var prior=h.owner.run(h.frozen(DAY,mismatch.equals("symbols")?List.of("000001.SZ"):SYMBOLS));assertEquals(SyncRunState.VERIFIED,prior.state());
        h.targetId=TARGET;h.root=ROOT;h.trace.clear();
        var plan=h.owner.plan(DAY.minusDays(5),DAY.plusDays(1),DAY.plusDays(1),SyncJobDefinition.Mode.INCREMENTAL,SYMBOLS);
        assertEquals(mismatch.equals("match")?DAY:null,plan.verifiedThrough());
        assertEquals(mismatch.equals("match")?DAY.minusDays(2):DAY.minusDays(5),plan.request().from());
    }

    @Test void reconcileAloneAdmitsRowsPastTheMissingCheckpoint()throws Exception{
        var h=new Harness(temp);h.targetRows=2;h.latest=DAY;
        assertThrows(IllegalStateException.class,()->h.owner.plan(DAY,DAY,DAY,SyncJobDefinition.Mode.INCREMENTAL,SYMBOLS));
        var plan=h.owner.plan(DAY,DAY,DAY,SyncJobDefinition.Mode.RECONCILE,SYMBOLS);
        assertEquals(2,plan.targetDatesChecked());assertNull(plan.verifiedThrough());
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void reconcileStillRejectsContradictoryCountAndFrontier(boolean hasRows)throws Exception{
        var h=new Harness(temp);h.targetRows=hasRows?1:0;h.latest=hasRows?null:DAY;
        assertEquals("D087 target row count and timestamp frontier disagree",assertThrows(IllegalStateException.class,
                ()->h.owner.plan(DAY,DAY,DAY,SyncJobDefinition.Mode.RECONCILE,SYMBOLS)).getMessage());
    }

    private static final class Harness {
        final Path ledger;final List<String> trace=new ArrayList<>();final List<Boolean> writerSawLedger=new ArrayList<>();
        final List<L2IntradayBarFeaturesWriteSession> sessions=new ArrayList<>();final Map<L2IntradayBarFeaturesKey,L2IntradayBarFeatures> stored=new HashMap<>();
        final L2IntradayBarFeaturesParquetSource source=mock(L2IntradayBarFeaturesParquetSource.class);
        final L2IntradayBarFeaturesTarget target=mock(L2IntradayBarFeaturesTarget.class);final L2IntradayBarFeaturesJobService owner;
        String targetId=TARGET,root=ROOT;int targetRows,sends;LocalDate latest;boolean cancelDuringPreflight;
        Harness(Path folder)throws Exception{
            ledger=folder.resolve("ledger.sqlite3");when(target.tableName()).thenReturn(TABLE);
            when(target.targetId()).thenAnswer(a->{trace.add("identity");return targetId;});
            when(target.newWriter()).thenAnswer(a->newSession());
            when(source.sourceRootIdentity()).thenAnswer(a->{trace.add("root");return root;});
            when(source.inspect(any(),any(),anyList(),anyInt(),anyInt())).thenAnswer(a->{trace.add("inspect");return inspection(a.getArgument(0),a.getArgument(1),a.getArgument(2));});
            doAnswer(a->{trace.add("stream");var inspected=(L2IntradayBarFeaturesParquetSource.Inspection)a.getArgument(0);
                List<String> symbols=a.getArgument(1);var rows=rows(inspected.from(),inspected.to(),symbols);
                @SuppressWarnings("unchecked") var consumer=(SyncJobRunner.PageConsumer<L2IntradayBarFeatures>)a.getArgument(4);
                consumer.accept(new SyncJobRunner.Page<>(rows,"a".repeat(64),"synthetic inspected Parquet receipt","cursor"));
                return new SyncJobRunner.SourceCompletion(1,rows.size(),true,"synthetic completion");
            }).when(source).stream(any(),anyList(),anyInt(),anyInt(),any(),any(BooleanSupplier.class),any(Duration.class));
            ExchangeCalendarReadPort calendar=query->{trace.add("calendar");assertTrue(Files.isRegularFile(ledger));
                LocalDate from=(LocalDate)query.fromInclusive(),to=(LocalDate)query.toExclusive();var dates=new ArrayList<ExchangeCalendar>();
                for(var date=from;date.isBefore(to);date=date.plusDays(1))dates.add(new ExchangeCalendar("SSE",date,true,date.minusDays(1)));
                return new DatasetReadPage<>("exchange_calendar",1,"fixture",Instant.EPOCH,dates,null);};
            owner=new L2IntradayBarFeaturesJobService(calendar,target,source,ledger);
        }
        L2IntradayBarFeaturesWriteSession newSession()throws Exception{
            trace.add("writer");writerSawLedger.add(Files.isRegularFile(ledger));var session=mock(L2IntradayBarFeaturesWriteSession.class);sessions.add(session);
            when(session.codec()).thenReturn(L2IntradayBarFeaturesWriteSession.CODEC);when(session.tableName()).thenReturn(TABLE);
            doAnswer(a->{trace.add("preflight");if(cancelDuringPreflight){var store=new SyncRunLedger(ledger);var run=store.history("data.l2_intraday_bar_features",null,100).getFirst();assertTrue(store.requestCancellation(run.id()));}return null;}).when(session).preflight();
            when(session.rowCount()).thenAnswer(a->{trace.add("count");return targetRows;});when(session.readLatestTradeDate()).thenAnswer(a->{trace.add("latest");return latest;});
            doAnswer(a->{trace.add("send");sends++;List<L2IntradayBarFeatures> rows=a.getArgument(0);rows.forEach(row->stored.put(row.key(),row));return null;}).when(session).send(anyList());
            when(session.readback(anyList())).thenAnswer(a->{List<L2IntradayBarFeaturesKey> keys=a.getArgument(0);return keys.stream().map(stored::get).filter(Objects::nonNull).toList();});
            when(session.walSettled()).thenReturn(true);when(session.uncertainSenderStopped()).thenReturn(true);return session;
        }
        L2IntradayBarFeaturesJobService.Plan frozen(LocalDate day,List<String> symbols)throws Exception{
            var request=L2IntradayBarFeaturesJobService.definition().freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("source_root_id",root,"symbols",symbols),day,day,day);
            return new L2IntradayBarFeaturesJobService.Plan(request,targetId,day,null,0,inspection(day,day,symbols));
        }
        L2IntradayBarFeaturesParquetSource.Inspection inspection(LocalDate from,LocalDate to,List<String> symbols)throws Exception{
            var dates=from.datesUntil(to.plusDays(1)).toList();int count=dates.size()*symbols.size();
            return new L2IntradayBarFeaturesParquetSource.Inspection(from,to,dates,count,count,1,1,100,"a".repeat(64),"b".repeat(64),"l2-intraday-bar-features-parquet-v1",root,true);
        }
        List<L2IntradayBarFeatures> rows(LocalDate from,LocalDate to,List<String> symbols)throws Exception{
            var features=L2IntradayBarFeaturesFixtures.rows().getFirst().features();var rows=new ArrayList<L2IntradayBarFeatures>();
            for(var day:from.datesUntil(to.plusDays(1)).toList())for(var symbol:symbols)
                rows.add(new L2IntradayBarFeatures(day,symbol,symbol.substring(7),"MAIN",day.atTime(9,15).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),features));
            return rows;
        }
    }
}
