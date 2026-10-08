package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.IndexCatalogState.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.storage.IndexCatalogStaging;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;
import com.zoutrankil.data.service.TusharePageService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real owners through ports; source receipts and ledgers are local temporary files. */
class IndexCatalogOwnerPortContractTest {
    @TempDir Path temp;
    private static final LocalDate DAY=LocalDate.of(2026,9,29);
    private static final String TABLE="java_catalog_owner_contract";

    @Test void filePlanningFreezesActualBytesWithoutTouchingTargetOrCreatingLedger() throws Exception {
        var target=mock(IndexCatalogTarget.class);when(target.tableName()).thenReturn(TABLE);
        var ledger=temp.resolve("ledger.sqlite");var owner=new IndexCatalogJobService(target,ledger);
        clearInvocations(target);var file=csv("first");var request=owner.plan(file,DAY);
        assertEquals(file.toAbsolutePath().normalize().toString(),request.parameters().get("file"));
        assertEquals(FileEvidenceStore.sha256(Files.readAllBytes(file)),request.parameters().get("sha256"));
        assertEquals(DAY,request.from());assertEquals(DAY,request.to());assertEquals(DAY,request.logicalDate());
        verifyNoInteractions(target);assertFalse(Files.exists(ledger));assertFalse(Files.exists(temp.resolve("sync-evidence")));
    }

    @Test void eachChangedRunCreatesIndependentStageAndEvidenceRoot() throws Exception {
        var target=mock(IndexCatalogTarget.class);when(target.tableName()).thenReturn(TABLE);
        var tables=new HashMap<String,Snapshot>();tables.put(TABLE,snapshot(11,List.of()));
        var stages=new ArrayList<IndexCatalogStagingPort>();var paths=new ArrayList<Path>();var sequence=new AtomicInteger();
        when(target.open(anyString())).thenAnswer(inv->{String table=inv.getArgument(0);return new IndexCatalogTables.Table(){
            public Identity preflight(){return tables.get(table).identity();}
            public Snapshot snapshot(){return Objects.requireNonNull(tables.get(table));}};});
        when(target.identify(anyString(),anyLong(),anyString())).thenAnswer(inv->identify(inv.getArgument(0),inv.getArgument(1),inv.getArgument(2)));
        when(target.prepare(any(),anyList())).thenAnswer(inv->IndexCatalogStaging.prepare(inv.getArgument(0),inv.getArgument(1)));
        when(target.publicationTables()).thenReturn(target);when(target.exists(anyString())).thenAnswer(inv->tables.containsKey(inv.getArgument(0)));
        when(target.walSettled(anyString())).thenReturn(true);
        doAnswer(inv->{String from=inv.getArgument(0),to=inv.getArgument(1);assertFalse(tables.containsKey(to));tables.put(to,tables.remove(from));return null;})
                .when(target).rename(anyString(),anyString());
        when(target.newStaging()).thenAnswer(inv->{int next=sequence.incrementAndGet();IndexCatalogStagingPort port=(prepared,evidence,cancelled)->{
            assertFalse(cancelled.getAsBoolean());paths.add(evidence);var actual=snapshot(20+next,prepared.rows());
            String stage="java_catalog_stage_"+next;tables.put(stage,actual);return new Verified(stage,actual,evidence.resolve("stage.json").toString(),1);};
            stages.add(port);return port;});
        var ledger=temp.resolve("ledger.sqlite");var owner=new IndexCatalogJobService(target,ledger);
        var first=owner.execute("first",null,owner.plan(csv("first"),DAY));
        var second=owner.execute("second",null,owner.plan(csv("revised"),DAY));
        assertEquals(SyncRunState.VERIFIED,first.state());assertEquals(SyncRunState.VERIFIED,second.state());
        assertEquals(1,first.inserted());assertEquals(1,second.revised());assertEquals(2,stages.size());assertNotSame(stages.get(0),stages.get(1));
        assertEquals(List.of(temp.resolve("sync-evidence/first"),temp.resolve("sync-evidence/second")),paths);
        assertEquals("revised",tables.get(TABLE).businessRows().getFirst().shortName());
        assertNull(new DatasetIntervalLock(ledger).findOwned("second",DatasetIntervalLock.Scope.allDates("index")));
    }

    @Test void targetPreflightFailureOccursBeforeLedgerCreation() throws Exception {
        var target=mock(IndexCatalogTarget.class);when(target.tableName()).thenReturn(TABLE);
        var table=mock(IndexCatalogTables.Table.class);when(target.open(TABLE)).thenReturn(table);
        var failure=new IllegalStateException("physical schema changed");when(table.preflight()).thenThrow(failure);
        var ledger=temp.resolve("ledger.sqlite");var owner=new IndexCatalogJobService(target,ledger);var request=owner.plan(csv("first"),DAY);
        assertSame(failure,assertThrows(IllegalStateException.class,()->owner.execute("run",null,request)));
        assertFalse(Files.exists(ledger));verify(target,never()).newStaging();verify(table,never()).snapshot();
    }

    @Test void changedTargetAfterRunCreationFailsWithoutStageAndReleasesLease() throws Exception {
        var target=mock(IndexCatalogTarget.class);when(target.tableName()).thenReturn(TABLE);
        var table=mock(IndexCatalogTables.Table.class);when(target.open(TABLE)).thenReturn(table);
        var before=snapshot(11,List.of());when(table.preflight()).thenReturn(before.identity());when(table.snapshot()).thenReturn(before);
        when(target.identify(TABLE,11,"generation-11")).thenReturn("first-target","changed-target");
        var ledger=temp.resolve("ledger.sqlite");var owner=new IndexCatalogJobService(target,ledger);
        var result=owner.execute("run",null,owner.plan(csv("first"),DAY));
        assertEquals(SyncRunState.FAILED,result.state());verify(target,never()).newStaging();verify(target,never()).prepare(any(),anyList());
        assertNull(new DatasetIntervalLock(ledger).findOwned("run",DatasetIntervalLock.Scope.allDates("index")));
        assertEquals(SyncRunState.FAILED,SyncRunLedger.openReadOnly(ledger).get("run").state());
    }

    @Test void parentCancellationStopsBeforeSourceSnapshotOrStaging() throws Exception {
        var target=mock(IndexCatalogTarget.class);when(target.tableName()).thenReturn(TABLE);
        var table=mock(IndexCatalogTables.Table.class);when(target.open(TABLE)).thenReturn(table);
        when(table.preflight()).thenReturn(new Identity(11,"generation-11"));when(target.identify(TABLE,11,"generation-11")).thenReturn("frozen-target");
        var path=temp.resolve("ledger.sqlite");var owner=new IndexCatalogJobService(target,path);var request=owner.plan(csv("first"),DAY);
        var ledger=new SyncRunLedger(path);ledger.createRun("parent",null,"parent-target",request);
        ledger.transition("parent",ledger.get("parent").revision(),SyncRunState.RUNNING,"{}");assertTrue(ledger.requestCancellation("parent"));
        var result=owner.execute("child","parent",request);
        assertEquals(SyncRunState.CANCELLED,result.state());verify(table,never()).snapshot();verify(target,never()).newStaging();
        assertNull(new DatasetIntervalLock(path).findOwned("child",DatasetIntervalLock.Scope.allDates("index")));
        assertFalse(Files.exists(temp.resolve("sync-evidence/child/prepared.json")));
    }

    @Test void membershipFormalWriteRejectionPrecedesAnyBackendOrSourceWork() throws Exception {
        var target=mock(IndexMembershipTarget.class);when(target.tableName()).thenReturn("index_member");
        var pages=mock(TusharePageService.class);var path=temp.resolve("ledger.sqlite");
        var owner=new IndexMembershipJobService(target,pages,path);clearInvocations(target);
        String message="Formal index_member publication requires separate consumer cutover; configure an isolated membership table";
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.run(null)).getMessage());
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.resume(null,"prior")).getMessage());
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.finishInterruptedChild("run",false)).getMessage());
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.executePrepared("run",null,null,List.of(),temp)).getMessage());
        verifyNoInteractions(target,pages);assertFalse(Files.exists(path));
    }

    @Test void membershipReceiptPlanKeepsScopeOrderAndRechecksFrozenBytesWithoutBackend() throws Exception {
        var target=mock(IndexMembershipTarget.class);when(target.tableName()).thenReturn("java_membership_contract");
        var pages=mock(TusharePageService.class);var path=temp.resolve("ledger.sqlite");
        var owner=new IndexMembershipJobService(target,pages,path);clearInvocations(target);
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<134;i++)rows.add(Map.of("index_code","%06d.SI".formatted(810000+i),"industry_name","industry-"+i,
                "industry_code","110300","parent_code","110000","level","L2","src","SW2021","is_pub","0"));
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(Map.of("endpoint","index_classify","parameters",Map.of("level","L2","src","SW2021"),
                "observedAt","2026-09-29T01:00:00Z","completion",Map.of("pages",1,"rows",134),"rows",rows));
        var receipt=temp.resolve("classification.json");Files.write(receipt,bytes);String hash=FileEvidenceStore.sha256(bytes);
        var request=owner.planFromReceipt(receipt,hash,List.of("810002.SI","810001.SI"),IndexMembershipSource.Selection.BOTH,DAY);
        assertEquals(List.of("810002.SI","810001.SI"),IndexMembershipJobPlan.scopes(request).stream().map(IndexMembershipSource.Scope::l2Code).toList());
        String frozen=SyncRequestIdentity.snapshotJson(request);
        var restored=IndexMembershipJobPlan.restore(frozen);
        assertNotSame(request,restored);
        assertEquals(frozen,SyncRequestIdentity.snapshotJson(restored));
        assertEquals(IndexMembershipJobPlan.scopes(request),IndexMembershipJobPlan.scopes(restored));
        Files.writeString(receipt," ",StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class,()->IndexMembershipJobPlan.scopes(request));
        assertThrows(IllegalArgumentException.class,()->IndexMembershipJobPlan.restore(frozen));
        verifyNoInteractions(target,pages);assertFalse(Files.exists(path));
    }

    private Path csv(String name)throws Exception{
        var file=temp.resolve("source.csv");Files.writeString(file,String.join(",",IndexCatalogFileSource.HEADERS)+"\r\n"
                +"000300,"+name+",full,2004-12-31,1000,series,300,4598.32,-6.95,stock,,CNY,no,yes,IOSCO,size,2005-04-08\r\n");return file;
    }
    private static Snapshot snapshot(long id,List<com.zoutrankil.data.domain.table.IndexRow> rows)throws Exception{
        byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(rows);return new Snapshot(new Identity(id,"generation-"+id),rows,FileEvidenceStore.sha256(bytes),bytes.length);
    }
    private static String identify(String table,long id,String directory){
        return com.zoutrankil.data.domain.policy.StaticTargetIdentity.identify("jdbc:postgresql://localhost:8812/qdb",table,id,directory);
    }
}
