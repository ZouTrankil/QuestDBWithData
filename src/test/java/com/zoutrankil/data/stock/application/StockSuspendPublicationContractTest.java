package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.stock.domain.StockSuspendState;
import com.zoutrankil.data.stock.domain.StockSuspendState.*;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.data.stock.port.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockSuspendPublicationContractTest {
    private static final String TABLE="stk_suspend", RUN="suspend-contract", LOGICAL="static-v2-"+"a".repeat(64);
    private static final String STAGE="java_stk_suspend_stage_0123456789abcdef0123456789abcdef";
    private static final LocalDate DATE=LocalDate.of(2026,9,28);
    @TempDir Path root;

    @ParameterizedTest @CsvSource({"1,false,ORIGINAL","1,true,OLD_MOVED","2,false,OLD_MOVED","2,true,PUBLISHED"})
    void everyRenameFaultRetainsItsJournalAndRecoveryCompletesTheExactGeneration(int failAt,boolean after,String layout)throws Exception {
        var f=new Fixture(false);f.tables.failAt=failAt;f.tables.failAfter=after;
        var failure=assertThrows(IllegalStateException.class,()->f.publish(()->false));
        assertEquals("stk_suspend window publication is uncertain; reconcile its journal before another run",failure.getMessage());
        assertSame(f.tables.fault,failure.getCause());
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,f.journal().forRun(RUN).state());
        assertEquals(StockSuspendPublication.Layout.valueOf(layout),f.publisher.inspect(RUN));
        assertNotNull(f.locks.findOwned(RUN,f.scope));
        f.tables.failAt=0;
        f.publisher.finish(RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().forRun(RUN).state());
        assertEquals(StockSuspendPublication.Layout.PUBLISHED,f.publisher.inspect(RUN));
        assertEquals(f.stage.snapshot(),f.tables.values.get(TABLE));
        assertEquals(f.before,f.tables.values.get(f.journal().forRun(RUN).intent().backup()));
        assertTrue(StockSuspendPublication.authorizesResume(f.ledgerPath,RUN,LOGICAL,f.physicalBefore,f.physicalAfter));
        int renames=f.tables.renames;f.publisher.finish(RUN,true);assertEquals(renames,f.tables.renames);
    }

    @ParameterizedTest @CsvSource({"false,VERIFIED","true,VERIFIED_EMPTY"})
    void receiptBackedRunRecoveryFinishesNonemptyAndAuthoritativeEmptyReplacement(boolean empty,String state)throws Exception {
        var f=new Fixture(empty);f.tables.failAt=2;
        assertThrows(IllegalStateException.class,()->f.publish(()->false));f.tables.failAt=0;
        var result=StockSuspendRunRecovery.finishInterrupted(f.target(),f.ledgerPath,RUN,true);
        assertEquals(SyncRunState.valueOf(state),result.state());assertEquals(empty?0:1,result.sourceRows());
        assertEquals(SyncRunState.valueOf(state),f.ledger.get(RUN).state());
        assertEquals(SyncRunState.valueOf(state),f.ledger.get("attempt").state());
        assertEquals(SyncRunState.valueOf(state),f.ledger.get("slice").state());
        assertEquals(f.stage.snapshot().rows(),f.tables.values.get(TABLE).rows());
        assertTrue(Files.isRegularFile(f.completion));assertNull(f.locks.findOwned(RUN,f.scope));
        var proof=JobDefinitionJson.mapper().readTree(f.completion.toFile());
        assertTrue(proof.path("sourceComplete").asBoolean());assertEquals(empty?0:1,proof.path("sourceRows").asInt());
        assertEquals(f.physicalAfter,proof.path("publication").path("physicalTargetAfter").asText());
    }

    @Test void changedRawReceiptBlocksRecoveryBeforeAnyFurtherRename()throws Exception {
        var f=new Fixture(false);f.tables.failAt=1;assertThrows(IllegalStateException.class,()->f.publish(()->false));
        f.tables.failAt=0;Files.writeString(f.rawReceipt," ",StandardOpenOption.APPEND);int renamed=f.tables.renames;
        assertEquals("D011 raw source receipt digest/row count differs from FETCHED event",assertThrows(IllegalStateException.class,
                ()->StockSuspendRunRecovery.finishInterrupted(f.target(),f.ledgerPath,RUN,true)).getMessage());
        assertEquals(renamed,f.tables.renames);assertEquals(ReferencePublicationJournal.State.IN_DOUBT,f.journal().forRun(RUN).state());
        assertNotNull(f.locks.findOwned(RUN,f.scope));
    }

    @Test void changedFullStageBlocksPublicationBeforeJournalOrRename()throws Exception {
        var f=new Fixture(false);f.tables.values.put(STAGE,snapshot(2,List.of()));
        assertEquals("stk_suspend complete stage differs from preserved outside rows plus authoritative source window",assertThrows(IllegalStateException.class,
                ()->f.publish(()->false)).getMessage());
        assertTrue(f.journal().findForRun(RUN).isEmpty());assertEquals(0,f.tables.renames);
    }

    @Test void conflictingPhysicalLayoutBlocksRecoveryWithoutRename()throws Exception {
        var f=new Fixture(false);f.tables.failAt=1;assertThrows(IllegalStateException.class,()->f.publish(()->false));
        f.tables.failAt=0;f.tables.values.put(TABLE,snapshot(99,f.before.rows()));int renamed=f.tables.renames;
        assertEquals("Conflicting stk_suspend target/backup/stage layout; no automatic rename",assertThrows(IllegalStateException.class,
                ()->f.publisher.finish(RUN,true)).getMessage());assertEquals(renamed,f.tables.renames);
    }

    @Test void cancellationBeforeIntentAndBetweenRenamesKeepsTheOriginalFailureBoundary()throws Exception {
        var f=new Fixture(false);assertThrows(CancellationException.class,()->f.publish(()->true));
        assertTrue(f.journal().findForRun(RUN).isEmpty());assertTrue(f.tables.calls.isEmpty());
        var count=new AtomicInteger();var failure=assertThrows(IllegalStateException.class,()->f.publish(()->count.incrementAndGet()==4));
        assertInstanceOf(CancellationException.class,failure.getCause());
        assertEquals(StockSuspendPublication.Layout.OLD_MOVED,f.publisher.inspect(RUN));
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,f.journal().forRun(RUN).state());
    }

    @Test void stoppedWriterIsCheckedBeforeAnyTargetOrLedgerAccess()throws Exception {
        var target=mock(StockSuspendTarget.class);
        assertEquals("Stopped stk_suspend writer proof required",assertThrows(IllegalStateException.class,
                ()->StockSuspendRunRecovery.finishInterrupted(target,root.resolve("absent.sqlite"),"missing",false)).getMessage());
        verifyNoInteractions(target);assertFalse(Files.exists(root.resolve("absent.sqlite")));
        var f=new Fixture(false);assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,false));
        assertTrue(f.tables.calls.isEmpty());
    }

    @Test void missingLedgerReadOnlyChecksDoNotConstructPhysicalAdapters()throws Exception {
        var target=mock(StockSuspendTarget.class);Path absent=root.resolve("absent.sqlite");
        StockSuspendPublication.verifyCurrentTarget(absent,target,LOGICAL,"physical");
        assertFalse(StockSuspendPublication.recoverIfPresent(absent,target,RUN,true));
        StockSuspendPublication.requireNoPendingPublication(absent);verifyNoInteractions(target);assertFalse(Files.exists(absent));
    }

    @Test void processLockExcludesConcurrentPublicationAndReleasesAfterClose()throws Exception {
        var f=new Fixture(false);
        try(var held=f.publisher.acquire()) {
            assertEquals("Another stk_suspend full-table replacement is active",assertThrows(IllegalStateException.class,f.publisher::acquire).getMessage());
        }
        try(var held=f.publisher.acquire()){assertNotNull(held.lock());}
    }

    private final class Fixture {
        final Path ledgerPath=root.resolve("ledger.sqlite"), evidence=root.resolve("sync-evidence").resolve(RUN);
        final Path rawReceipt=evidence.resolve("source/raw.json"),completion=evidence.resolve("complete-"+RUN+".json");
        final SyncRunLedger ledger;final DatasetIntervalLock locks;
        final DatasetIntervalLock.Scope scope=new DatasetIntervalLock.Scope("stk_suspend",DATE,DATE);
        final MemoryTables tables=new MemoryTables();final Snapshot before;final List<StockSuspend> source;
        final Verified stage;final String physicalBefore,physicalAfter,sourceFingerprint;final StockSuspendPublication publisher;
        Fixture(boolean empty)throws Exception {
            Files.createDirectories(rawReceipt.getParent());ledger=new SyncRunLedger(ledgerPath);
            before=snapshot(1,List.of(row("000001.SZ",DATE.minusDays(1)),row("000002.SZ",DATE),row("000003.SZ",DATE.plusDays(1))));
            source=empty?List.of():List.of(row("000004.SZ",DATE));
            var expected=new ArrayList<>(List.of(before.rows().getFirst(),before.rows().getLast()));expected.addAll(source);
            Snapshot staged=snapshot(2,expected);physicalBefore=physical(1);physicalAfter=physical(2);
            var request=StockSuspendSyncJobOwner.DEFINITION.freeze(Mode.BACKFILL,Map.of("targetId",LOGICAL,"physicalTargetId",physicalBefore),DATE,DATE,DATE);
            ledger.createRun(RUN,null,LOGICAL,request);transition(RUN,SyncRunState.RUNNING,"{}");
            ledger.createChild("attempt",SyncRunLedger.Kind.ATTEMPT,RUN,RUN);transition("attempt",SyncRunState.RUNNING,"{}");
            ledger.createChild("slice",SyncRunLedger.Kind.SLICE,RUN,"attempt");transition("slice",SyncRunState.RUNNING,"{}");
            var rawRows=new ArrayList<Map<String,Object>>();var normalized=new ArrayList<Map<String,Object>>();
            if(!empty){var raw=new LinkedHashMap<String,Object>();raw.put("ts_code","000004.SZ");raw.put("trade_date","20260928");raw.put("suspend_timing",null);raw.put("suspend_type","S");rawRows.add(raw);
                var norm=new LinkedHashMap<String,Object>();norm.put("ts_code","000004.SZ");norm.put("trade_date",DATE.toString());norm.put("is_suspended",1);norm.put("suspend_timing",null);normalized.add(norm);}
            var receipt=new LinkedHashMap<String,Object>();receipt.put("endpoint","suspend_d");receipt.put("sourceComplete",true);receipt.put("sourceRowCap",5000);receipt.put("pages",1);
            receipt.put("fields",StockSuspendMapper.SOURCE_FIELDS);receipt.put("rows",rawRows);receipt.put("normalizedRows",normalized);receipt.put("returnedRows",source.size());receipt.put("tradeDate",DATE.toString());receipt.put("parameters",Map.of("trade_date","20260928","suspend_type","S"));
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(receipt);Files.write(rawReceipt,bytes);String hash=sha(bytes);
            sourceFingerprint=sha((hash+"\0").getBytes(StandardCharsets.UTF_8));
            Path page=evidence.resolve("page-"+DATE+".json");JobDefinitionJson.mapper().writeValue(page.toFile(),Map.of("evidenceType","stk_suspend_daily_source","endpoint","suspend_d","sourceComplete",true,"tradeDate",DATE.toString(),"sourceFingerprint",hash,"sourceReceipt",rawReceipt.toString()));
            transition("slice",SyncRunState.FETCHED,JobDefinitionJson.mapper().writeValueAsString(Map.of("returnedRows",source.size(),"responseEvidence",page.toString(),"sourceFingerprint",hash)));
            Path stageReceipt=evidence.resolve("stage-verified.json");JobDefinitionJson.mapper().writeValue(stageReceipt.toFile(),Map.of("target",TABLE,"stage",STAGE,"actual",staged,"sourceRows",source.size(),"windowFrom",DATE.toString(),"windowTo",DATE.toString(),"preservedOutsideRows",2));
            stage=new Verified(STAGE,staged,empty?0:1,stageReceipt.toString());tables.values.put(TABLE,before);tables.values.put(STAGE,staged);
            locks=new DatasetIntervalLock(ledgerPath);locks.acquire(RUN,scope);
            publisher=new StockSuspendPublication(tables,ledgerPath,evidence,TABLE,LOGICAL,RUN);
        }
        void transition(String id,SyncRunState state,String json)throws Exception {ledger.transition(id,ledger.get(id).revision(),state,json);}
        ReferencePublicationJournal journal()throws Exception{return new ReferencePublicationJournal(ledgerPath,"stk_suspend");}
        StockSuspendPublication.WindowProof publish(java.util.function.BooleanSupplier cancelled)throws Exception {
            return publisher.publishWindow(before,stage,source,DATE,DATE,physicalBefore,sourceFingerprint,completion.toString(),cancelled);
        }
        StockSuspendTarget target()throws Exception {
            var target=mock(StockSuspendTarget.class);when(target.tableName()).thenReturn(TABLE);when(target.targetId()).thenReturn(LOGICAL);
            when(target.newPublicationTables()).thenReturn(tables);
            when(target.openTable(anyString())).thenAnswer(call->tables.openTable(call.getArgument(0)));
            when(target.physicalTargetId(anyString(),any(Identity.class))).thenAnswer(call->physical(((Identity)call.getArgument(1)).id()));
            when(target.prepare(any(),any(),anyList(),any(),any())).thenAnswer(call->tables.prepare(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4)));
            return target;
        }
    }

    private static final class MemoryTables implements StockSuspendTables {
        final Map<String,Snapshot> values=new HashMap<>();final List<String> calls=new ArrayList<>();
        final IllegalStateException fault=new IllegalStateException("injected rename fault");int renames,failAt;boolean failAfter;
        public Table openTable(String table){calls.add("open:"+table);return new Table(){
            public Identity preflight(){calls.add("preflight:"+table);return values.get(table).identity();}
            public Snapshot snapshot(){calls.add("snapshot:"+table);return values.get(table);}
        };}
        public String logicalTargetId(String table){calls.add("logical:"+table);return LOGICAL;}
        public String physicalTargetId(String table,Identity identity){calls.add("physical:"+table);return physical(identity.id());}
        public Prepared prepare(Snapshot before,Snapshot current,List<StockSuspend> source,LocalDate from,LocalDate to)throws Exception {
            if(!before.equals(current))throw new IllegalArgumentException("changed fixture");
            var expected=new ArrayList<>(before.rows().stream().filter(r->r.tradeDate().isBefore(from)||r.tradeDate().isAfter(to)).toList());expected.addAll(source);
            return new Prepared(before,current,from,to,ordered(source),ordered(expected));
        }
        public Snapshot snapshotIfPresent(String table){calls.add("optional:"+table);return values.get(table);}
        public void rename(String from,String to){calls.add("rename:"+from+":"+to);renames++;
            if(renames==failAt&&!failAfter)throw fault;
            Snapshot original=values.remove(from);if(original==null||values.putIfAbsent(to,original)!=null)throw new IllegalStateException("invalid layout");
            if(renames==failAt&&failAfter)throw fault;
        }
    }
    private static List<StockSuspend> ordered(List<StockSuspend> values){return values.stream().sorted(Comparator.comparing(StockSuspend::tradeDate).thenComparing(StockSuspend::tsCode)).toList();}
    private static StockSuspend row(String code,LocalDate date){return new StockSuspend(new StockSuspendKey(code,date),1);}
    private static String physical(long id){return "static-v2-"+String.format(Locale.ROOT,"%064x",id);}
    private static Snapshot snapshot(long id,List<StockSuspend> rows)throws Exception {
        var ordered=ordered(rows);var bytes=new ByteArrayOutputStream();
        try(var output=new DataOutputStream(bytes)) {for(var row:ordered){var map=new LinkedHashMap<String,Object>();map.put("ts_code",row.tsCode());map.put("is_suspended",row.isSuspended());map.put("trade_date",row.tradeDate());byte[] value=JobDefinitionJson.mapper().writeValueAsBytes(map);output.writeInt(value.length);output.write(value);}}
        return new Snapshot(new Identity(id,"generation-"+id),ordered,sha(bytes.toByteArray()),bytes.size());
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
