package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.derived.domain.MarketSentimentDailyRows;
import com.zoutrankil.data.derived.domain.MarketSentimentDailySourceData.*;
import com.zoutrankil.data.derived.port.*;
import com.zoutrankil.data.derived.storage.MarketSentimentDailyStorage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real owner calculation, runner, immutable source file, SQLite journal and leases; physical tables are in memory. */
class MarketSentimentDailyPublicationContractTest {
    private static final LocalDate DAY=LocalDate.of(2026,9,17);
    private static final String TABLE="market_sentiment_daily",PIN="a".repeat(64);
    private static final List<String> CODES=List.of("000001.SZ","000002.SZ","600001.SH");
    @TempDir Path temp;

    @Test void realOwnerPublishesExactWindowPreservesOutsideRowsAndRetainsBackup()throws Exception {
        var h=new Harness(temp);var original=h.target.snapshot(TABLE);assertFalse(Files.exists(h.ledger));
        var plan=h.owner.plan(DAY,DAY,DAY);assertFalse(Files.exists(h.ledger));assertEquals(DAY.minusDays(1400),plan.warmupFrom());
        var result=h.owner.run(plan);assertEquals(SyncRunState.VERIFIED,result.result().state());
        assertEquals(161,result.historyDates());assertEquals(483,result.sourceRawRows());
        var entry=h.publication(result.result().runId());assertEquals(ReferencePublicationJournal.State.VERIFIED,entry.state());
        assertRetainedBackup(original,h.target.snapshot(entry.intent().backup()));
        assertEquals(3,h.target.snapshot(TABLE).rows().size());
        assertEquals(original.rows().getFirst(),h.target.snapshot(TABLE).rows().getFirst());
        assertEquals(original.rows().getLast(),h.target.snapshot(TABLE).rows().getLast());
        assertNotEquals(original.rows().get(1),h.target.snapshot(TABLE).rows().get(1));
        assertEquals(List.of(false,true),h.target.writerSawLedger);
        assertEquals(1,h.target.writers.get(1).prepares);assertEquals(1,h.target.writers.get(1).sends);
        assertEquals(result.fullTargetFingerprint(),h.target.snapshot(TABLE).fingerprint());
        assertProofAndLease(h,result.result().runId(),false);verifyNoInteractions(h.client);
    }

    @ParameterizedTest @CsvSource({"1,false","1,true","2,false","2,true"})
    void faultsBeforeOrAfterEitherRenameRecoverFromActualDurableProof(int step,boolean after)throws Exception {
        var h=new Harness(temp);h.target.faultStep=step;h.target.faultAfter=after;
        var plan=h.owner.plan(DAY,DAY,DAY);var original=plan.targetBefore();var failed=h.owner.run(plan);
        assertEquals(SyncRunState.IN_DOUBT,failed.result().state());String run=failed.result().runId();
        assertEquals(ReferencePublicationJournal.State.IN_DOUBT,h.publication(run).state());assertProofAndLease(h,run,true);
        assertThrows(IllegalStateException.class,()->h.owner.plan(DAY,DAY,DAY));
        int before=h.target.renames;h.target.faultStep=0;
        var actual=h.owner.finishInterrupted(run,true);
        assertEquals(3,actual.rows().size());assertRetainedBackup(original,h.target.snapshot(h.publication(run).intent().backup()));
        assertEquals(ReferencePublicationJournal.State.VERIFIED,h.publication(run).state());assertProofAndLease(h,run,false);
        assertEquals(SyncRunState.VERIFIED,new SyncRunLedger(h.ledger).get(run).state());
        int expectedRemaining=step==1&&!after?2:step==2&&after?0:1;
        assertEquals(expectedRemaining,h.target.renames-before);
        assertEquals(actual,h.owner.finishInterrupted(run,true));assertEquals(before+expectedRemaining,h.target.renames);
        assertEquals(1,h.target.writers.stream().mapToInt(w->w.sends).sum());verifyNoInteractions(h.client);
    }

    @ParameterizedTest @CsvSource({"1,false","1,true","2,false","2,true"})
    void hardStopLeavesRealRunningAuthorityAndRecoveryHandlesEachPhysicalLayout(int step,boolean after)throws Exception {
        var h=new Harness(temp);h.target.faultStep=step;h.target.faultAfter=after;h.target.hardStop=true;
        var plan=h.owner.plan(DAY,DAY,DAY);
        assertThrows(HardStop.class,()->h.owner.run(plan));
        String run=h.target.submittedRun;assertNotNull(run);
        var ledger=new SyncRunLedger(h.ledger);assertEquals(SyncRunState.RUNNING,ledger.get(run).state());
        var slice=ledger.entries(run,null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).findFirst().orElseThrow();
        assertEquals(SyncRunState.VERIFIED,slice.state());
        var lease=new DatasetIntervalLock(h.ledger).findOwned(run,DatasetIntervalLock.Scope.allDates(TABLE));
        assertNotNull(lease);assertFalse(lease.inDoubt());
        h.target.faultStep=0;
        var actual=h.owner.finishInterrupted(run,true);assertEquals(3,actual.rows().size());
        assertEquals(SyncRunState.VERIFIED,ledger.get(run).state());assertProofAndLease(h,run,false);
    }

    @Test void stoppedWriterProofIsRejectedBeforeOpeningLedgerOrReadingTables() {
        var h=new Harness(temp);assertThrows(IllegalArgumentException.class,()->h.owner.finishInterrupted("absent",false));
        assertFalse(Files.exists(h.ledger));assertTrue(h.target.writers.isEmpty());assertEquals(0,h.target.renames);
    }

    @Test void publicationEvidenceFailureAfterVerifiedJournalRetainsRecoverableOwnerAndLease()throws Exception {
        var h=new Harness(temp);h.target.blockPublicationEvidence=true;
        var result=h.owner.run(h.owner.plan(DAY,DAY,DAY));String run=result.result().runId();
        assertEquals(ReferencePublicationJournal.State.VERIFIED,h.publication(run).state());
        assertEquals(2,h.target.renames);assertEquals(3,h.target.snapshot(TABLE).rows().size());
        assertTrue(Files.isRegularFile(Path.of(result.evidence())));
        assertTrue(Files.isDirectory(Path.of(result.evidence()).resolveSibling("publication.json")));
        var ledger=new SyncRunLedger(h.ledger);
        var slices=ledger.entries(run,null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        assertEquals(1,slices.size());assertEquals(SyncRunState.VERIFIED,slices.getFirst().state());
        assertEquals(SyncRunState.IN_DOUBT,result.result().state());assertProofAndLease(h,run,true);
        assertThrows(IllegalStateException.class,()->h.owner.finishInterrupted(run,true));
        assertEquals(SyncRunState.IN_DOUBT,ledger.get(run).state());assertProofAndLease(h,run,true);
        Files.delete(Path.of(result.evidence()).resolveSibling("publication.json"));
        var actual=h.owner.finishInterrupted(run,true);assertEquals(h.target.snapshot(TABLE),actual);
        assertEquals(SyncRunState.VERIFIED,ledger.get(run).state());assertProofAndLease(h,run,false);
        assertPublicationEvidence(h,run);assertEquals(2,h.target.renames);assertEquals(1,h.target.writers.stream().mapToInt(w->w.sends).sum());
        assertEquals(actual,h.owner.finishInterrupted(run,true));assertPublicationEvidence(h,run);
    }

    @ParameterizedTest @ValueSource(strings={"conflicting_json","malformed_json","missing_field"})
    void conflictingPublicationEvidenceCannotCompleteOwnerOrReleaseLease(String kind)throws Exception {
        var h=new Harness(temp);h.target.blockPublicationEvidence=true;
        var result=h.owner.run(h.owner.plan(DAY,DAY,DAY));String run=result.result().runId();
        assertEquals(SyncRunState.IN_DOUBT,result.result().state());
        var evidence=Path.of(result.evidence()).resolveSibling("publication.json");Files.delete(evidence);
        String contents;
        if(kind.equals("malformed_json"))contents="{";
        else {var proof=JobDefinitionJson.mapper().valueToTree(publicationEvidence(h,run));
            if(kind.equals("conflicting_json"))((com.fasterxml.jackson.databind.node.ObjectNode)proof).put("fullTargetFingerprint","tampered");
            else ((com.fasterxml.jackson.databind.node.ObjectNode)proof).remove("published");
            contents=JobDefinitionJson.mapper().writeValueAsString(proof);}
        Files.writeString(evidence,contents,StandardOpenOption.CREATE_NEW);byte[] original=Files.readAllBytes(evidence);
        assertThrows(Exception.class,()->h.owner.finishInterrupted(run,true));assertArrayEquals(original,Files.readAllBytes(evidence));
        assertEquals(SyncRunState.IN_DOUBT,new SyncRunLedger(h.ledger).get(run).state());assertProofAndLease(h,run,true);
        assertEquals(2,h.target.renames);assertEquals(1,h.target.writers.stream().mapToInt(w->w.sends).sum());
    }

    @Test void matchingExistingPublicationEvidenceCanCompleteOwnerWithoutRewritingIt()throws Exception {
        var h=new Harness(temp);h.target.blockPublicationEvidence=true;
        var result=h.owner.run(h.owner.plan(DAY,DAY,DAY));String run=result.result().runId();
        var evidence=Path.of(result.evidence()).resolveSibling("publication.json");Files.delete(evidence);
        Files.writeString(evidence,JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(publicationEvidence(h,run)),StandardOpenOption.CREATE_NEW);
        byte[] original=Files.readAllBytes(evidence);h.owner.finishInterrupted(run,true);
        assertArrayEquals(original,Files.readAllBytes(evidence));assertEquals(SyncRunState.VERIFIED,new SyncRunLedger(h.ledger).get(run).state());
        assertProofAndLease(h,run,false);assertPublicationEvidence(h,run);assertEquals(2,h.target.renames);
    }

    @ParameterizedTest @ValueSource(strings={"missing_lease","foreign_lease","legacy_partial"})
    void missingRecoveryAuthorityRejectsBeforePhysicalReadsOrNewEvidence(String kind)throws Exception {
        var h=new Harness(temp);h.target.blockPublicationEvidence=true;var plan=h.owner.plan(DAY,DAY,DAY);
        var result=h.owner.run(plan);String run=result.result().runId();assertEquals(SyncRunState.IN_DOUBT,result.result().state());
        var evidence=Path.of(result.evidence()).resolveSibling("publication.json");Files.delete(evidence);
        var ledger=new SyncRunLedger(h.ledger);var locks=new DatasetIntervalLock(h.ledger);var scope=DatasetIntervalLock.Scope.allDates(TABLE);
        var lease=locks.findOwned(run,scope);assertNotNull(lease);locks.releaseAfterReconciliation(lease,true,true);
        if(kind.equals("foreign_lease")){
            ledger.createRun("foreign-owner",null,plan.targetId(),plan.request());assertNotNull(locks.acquire("foreign-owner",scope));
        }else if(kind.equals("legacy_partial")){
            // Negative authority fixture emulates the pre-fix terminal owner; source, slice and journal remain runner-generated.
            try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+h.ledger);var update=db.prepareStatement("UPDATE sync_entries SET state='PARTIAL' WHERE run_id=? AND kind IN ('RUN','ATTEMPT')")){
                update.setString(1,run);assertEquals(2,update.executeUpdate());
            }
        }
        var before=ledger.entries(run,null,100);int snapshots=h.target.snapshots;
        assertThrows(IllegalStateException.class,()->h.owner.finishInterrupted(run,true));
        assertEquals(before,ledger.entries(run,null,100));assertFalse(Files.exists(evidence));assertEquals(snapshots,h.target.snapshots);
        assertEquals(2,h.target.renames);assertEquals(ReferencePublicationJournal.State.VERIFIED,h.publication(run).state());
        assertNull(locks.findOwned(run,scope));if(kind.equals("foreign_lease"))assertNotNull(locks.findOwned("foreign-owner",scope));
    }

    @ParameterizedTest @ValueSource(strings={"source_bytes","foreign_layout","replacement_rows","original_rows"})
    void corruptionIsRejectedBeforeAnyRecoveryRenameAndRetainsLease(String kind)throws Exception {
        var h=new Harness(temp);h.target.faultStep=1;var result=h.owner.run(h.owner.plan(DAY,DAY,DAY));
        assertEquals(SyncRunState.IN_DOUBT,result.result().state());String run=result.result().runId();
        var intent=h.publication(run).intent();
        switch(kind){
            case "source_bytes" -> Files.writeString(Path.of(result.evidence()),"tampered",StandardOpenOption.APPEND);
            case "foreign_layout" -> h.target.tables.put(intent.stage(),new Physical(999,"foreign",h.target.tables.get(intent.stage()).rows));
            case "replacement_rows" -> h.target.tables.get(intent.stage()).rows.add(row(DAY.plusDays(3),777d));
            case "original_rows" -> h.target.tables.get(intent.target()).rows.add(row(DAY.plusDays(4),888d));
            default -> throw new AssertionError(kind);
        }
        h.target.faultStep=0;int renames=h.target.renames;
        assertThrows(IllegalStateException.class,()->h.owner.finishInterrupted(run,true));
        assertEquals(renames,h.target.renames);assertProofAndLease(h,run,true);
    }

    @Test void sourcePinDriftRejectsBeforeCalculationEvidenceOrStage()throws Exception {
        var h=new Harness(temp);var plan=h.owner.plan(DAY,DAY,DAY);h.source.pin="b".repeat(64);
        var result=h.owner.run(plan);assertEquals(SyncRunState.FAILED,result.result().state());
        assertEquals(0,h.source.panelCalls);assertEquals(0,h.target.writers.stream().mapToInt(w->w.prepares).sum());
        assertEquals(0,h.target.renames);assertFalse(Files.exists(h.ledger.getParent().resolve("sync-evidence")));
    }

    private static void assertProofAndLease(Harness h,String run,boolean retained)throws Exception {
        var ledger=new SyncRunLedger(h.ledger);var slices=ledger.entries(run,null,100).stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        assertEquals(1,slices.size());assertEquals(SyncRunState.VERIFIED,slices.getFirst().state());
        var proof=JobDefinitionJson.mapper().readTree(slices.getFirst().payloadJson()).path("verification");
        assertTrue(proof.path("passed").asBoolean());assertEquals(1,proof.path("expectedRows").asInt());assertEquals(1,proof.path("matchedRows").asInt());
        var events=ledger.events(slices.getFirst().id(),-1,100);
        assertTrue(events.stream().anyMatch(e->e.state()==SyncRunState.FETCHED));
        assertTrue(events.stream().anyMatch(e->e.state()==SyncRunState.SUBMITTED));
        var lease=new DatasetIntervalLock(h.ledger).findOwned(run,DatasetIntervalLock.Scope.allDates(TABLE));
        if(retained){assertNotNull(lease);assertTrue(lease.inDoubt());}else assertNull(lease);
    }

    private static MarketSentimentDailyRow row(LocalDate date,double score){return MarketSentimentDailyRows.row(Map.of("trade_date",date.atStartOfDay().toInstant(ZoneOffset.UTC),"sentiment_score",score));}
    private static Map<String,Object> publicationEvidence(Harness h,String run)throws Exception {
        var intent=h.publication(run).intent();var scope=JobDefinitionJson.mapper().readTree(intent.scope());
        Path sourcePath=Path.of(scope.path("sourceEvidence").asText());var source=JobDefinitionJson.mapper().readTree(sourcePath.toFile());
        var actual=h.target.snapshot(TABLE);
        return Map.of("published",true,"backup",intent.backup(),"formalRows",actual.rows().size(),"windowRows",source.path("rows").size(),
                "fullTargetFingerprint",actual.fingerprint(),"sourceFingerprint",scope.path("sourceFingerprint").asText(),"sourceEvidence",sourcePath.toString());
    }
    private static void assertPublicationEvidence(Harness h,String run)throws Exception {
        Path path=h.ledger.getParent().resolve("sync-evidence").resolve(run).resolve("publication.json");assertTrue(Files.isRegularFile(path));
        assertEquals(JobDefinitionJson.mapper().valueToTree(publicationEvidence(h,run)),JobDefinitionJson.mapper().readTree(path.toFile()));
    }
    private static void assertRetainedBackup(MarketSentimentDailyTargetSnapshot original,MarketSentimentDailyTargetSnapshot backup){
        assertEquals(original.tableId(),backup.tableId());assertEquals(original.directory(),backup.directory());
        assertEquals(original.wal(),backup.wal());assertEquals(original.rows(),backup.rows());assertEquals(original.fingerprint(),backup.fingerprint());
        assertNotEquals(original.targetId(),backup.targetId());
    }
    private static final class Harness {
        final Path ledger;final Source source=new Source();final Target target;final TushareClient client=mock(TushareClient.class);final MarketSentimentDailyJobService owner;
        Harness(Path temp){ledger=temp.resolve("ledger.sqlite3");target=new Target(ledger);owner=new MarketSentimentDailyJobService(source,target,client,ledger.toString());}
        ReferencePublicationJournal.Entry publication(String run)throws Exception{return new ReferencePublicationJournal(ledger,TABLE).forRun(run);}
    }
    private static final class Source implements MarketSentimentDailySourceReadPort {
        String pin=PIN;int panelCalls;
        public Map<LocalDate,Set<String>> expectedDates(LocalDate from,LocalDate to){return Map.of(DAY,Set.copyOf(CODES));}
        public String sourcePin(List<String> sources){assertEquals(MarketSentimentDailyJobService.SOURCES,sources);return pin;}
        public int readPanelMonth(LocalDate lower,LocalDate upper,Runnable cancellation,PanelConsumer consumer){
            panelCalls++;int count=0;
            for(LocalDate date=DAY.minusDays(160);!date.isAfter(DAY);date=date.plusDays(1))if(!date.isBefore(lower)&&date.isBefore(upper)){
                cancellation.run();int ordinal=(int)java.time.temporal.ChronoUnit.DAYS.between(DAY.minusDays(160),date);
                for(int i=0;i<3;i++){
                    double price=10+i+ordinal*.02,previous=price-(i==1?-.03:.05);
                    consumer.accept(new PanelRow(date,CODES.get(i),price,previous,100+ordinal+i*10,1+i+ordinal*.001,1000+i*500,10000+i*5000,1+i,price+1,price-1,false,false));count++;
                }
                consumer.completeDay(date,Set.copyOf(CODES),3,3);
            }return count;
        }
        public MarginData margins(LocalDate from,LocalDate to){
            var values=new TreeMap<LocalDate,double[]>();var exchanges=new TreeMap<LocalDate,Map<String,Long>>();
            for(int i=0;i<=160;i++){var date=DAY.minusDays(160-i);values.put(date,new double[]{10000+i*20,100+i,80+i*.5});exchanges.put(date,Map.of("SH",1L,"SZ",2L,"BJ",0L));}
            return new MarginData(values,exchanges);
        }
        public Map<LocalDate,double[]> flows(LocalDate from,LocalDate to){var result=new TreeMap<LocalDate,double[]>();for(int i=0;i<=160;i++)result.put(DAY.minusDays(160-i),new double[]{20+i,10+i*.5});return result;}
    }
    private static final class HardStop extends Error {}
    private static final class Physical {
        final long id;final String directory;final List<MarketSentimentDailyRow> rows;
        Physical(long id,String directory,List<MarketSentimentDailyRow> rows){this.id=id;this.directory=directory;this.rows=new ArrayList<>(rows);}
    }
    private static final class Target implements MarketSentimentDailyTarget {
        final Path ledger;final Map<String,Physical> tables=new LinkedHashMap<>();final List<Session> writers=new ArrayList<>();final List<Boolean> writerSawLedger=new ArrayList<>();
        final MarketSentimentDailyStorage pending=new MarketSentimentDailyStorage(new JdbcTemplate(mock(DataSource.class)),TABLE);
        int renames,faultStep,snapshots;boolean faultAfter,hardStop,blockPublicationEvidence;String submittedRun;
        Target(Path ledger){this.ledger=ledger;tables.put(TABLE,new Physical(11,"formal~11",List.of(row(DAY.minusDays(1),1),row(DAY,2),row(DAY.plusDays(1),3))));}
        public String table(){return TABLE;}public void requireTarget(){MarketSentimentDailyRows.requireTarget(TABLE);}
        public MarketSentimentDailyWriteSession newWriter(){writerSawLedger.add(Files.isRegularFile(ledger));var session=new Session(this);writers.add(session);return session;}
        public void requireNoPendingPublication(Path path)throws Exception{pending.requireNoPendingPublication(path);}
        public boolean tableExists(String name){return tables.containsKey(name);}public boolean identityMatches(String name,long id){return tables.containsKey(name)&&tables.get(name).id==id;}
        public void rename(String from,String to){int step=++renames;if(step==faultStep&&!faultAfter)failRename("injected before rename");assertFalse(tables.containsKey(to));tables.put(to,Objects.requireNonNull(tables.remove(from)));if(step==faultStep&&faultAfter)failRename("injected after rename");}
        void failRename(String message){if(hardStop)throw new HardStop();throw new IllegalStateException(message);}
        MarketSentimentDailyTargetSnapshot snapshot(String name){snapshots++;var p=Objects.requireNonNull(tables.get(name),name);var rows=p.rows.stream().sorted(Comparator.comparing(MarketSentimentDailyRow::tradeDate)).toList();return new MarketSentimentDailyTargetSnapshot(com.zoutrankil.data.domain.policy.StaticTargetIdentity.identify("jdbc:postgresql://127.0.0.1:8812/qdb",name,p.id,p.directory),p.id,p.directory,true,rows,MarketSentimentDailyRows.digest(rows));}
    }
    private static final class Session implements MarketSentimentDailyWriteSession {
        final Target target;String stage;int prepares,sends;
        Session(Target target){this.target=target;}
        public NativeDailyWindowSession<MarketSentimentDailyRow> nativePort(){throw new UnsupportedOperationException("Market owner uses the specialized session");}
        public String table(){return TABLE;}public String stage(){return Objects.requireNonNull(stage);}
        public MarketSentimentDailyTargetSnapshot snapshot(String table){return target.snapshot(table);}public MarketSentimentDailyTargetSnapshot formalSnapshot(){return snapshot(TABLE);}
        public void requireSame(MarketSentimentDailyTargetSnapshot before){assertEquals(before,formalSnapshot());}
        public MarketSentimentDailyTargetSnapshot prepare(MarketSentimentDailyTargetSnapshot before,LocalDate from,LocalDate to){
            prepares++;requireSame(before);stage="java_d121_market_sentiment_daily_stage_"+UUID.randomUUID().toString().replace("-","");
            target.tables.put(stage,new Physical(22,"stage~22",before.rows().stream().filter(r->MarketSentimentDailyRows.outside(r,from,to)).toList()));return snapshot(stage);
        }
        public void submissionRecorded(VerifiedBatchExecutor.Submission submission)throws Exception{
            target.submittedRun=submission.runId();
            if(target.blockPublicationEvidence)Files.createDirectory(target.ledger.getParent().resolve("sync-evidence").resolve(submission.runId()).resolve("publication.json"));
        }
        public void preflight(){snapshot(stage==null?TABLE:stage);}
        public void send(List<MarketSentimentDailyRow> rows){sends++;preflight();target.tables.get(stage()).rows.addAll(rows);}
        public List<MarketSentimentDailyRow> readback(List<Instant> keys){return target.tables.get(stage()).rows.stream().filter(r->keys.contains(r.tradeDate())).sorted(Comparator.comparing(MarketSentimentDailyRow::tradeDate)).toList();}
        public boolean walSettled(){return true;}public boolean uncertainSenderStopped(){return false;}
        public List<MarketSentimentDailyRow> readAll(String table){return snapshot(table).rows();}public void requireSchema(String table){}
    }
}
