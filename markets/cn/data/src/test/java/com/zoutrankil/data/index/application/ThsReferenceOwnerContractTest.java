package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.ThsMemberRow;
import com.zoutrankil.data.index.domain.ThsIndexState;
import com.zoutrankil.data.index.domain.ThsMemberState;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThsReferenceOwnerContractTest {
    private static final String ID="static-v2-"+"a".repeat(64),BOARD="885001.TI";
    private static final LocalDate DAY=LocalDate.of(2020,1,3);
    private static final Instant OBSERVED=Instant.parse("2020-01-03T01:02:03.123456Z");
    @TempDir Path temp;
    @Test void constructorsAndPlansHaveNoDatabaseOrSourceSideEffects() {
        var index=mock(ThsIndexTarget.class);var member=mock(ThsMemberTarget.class);var pages=mock(TusharePageService.class);
        when(index.tableName()).thenReturn("ths_index");when(member.tableName()).thenReturn("ths_member");
        var a=new ThsIndexJobService(index,pages,temp.resolve("index.sqlite3"));var b=new ThsMemberJobService(member,pages,temp.resolve("member.sqlite3"));
        assertTrue(a.plan(DAY).parameters().isEmpty());assertEquals(Map.of("board_code",BOARD),b.plan(BOARD,DAY).parameters());
        verify(index,times(2)).tableName();verify(member,times(2)).tableName();verifyNoMoreInteractions(index,member);verifyNoInteractions(pages);
        assertFalse(Files.exists(temp.resolve("index.sqlite3")));assertFalse(Files.exists(temp.resolve("member.sqlite3")));
    }
    @Test void formalMemberRunAndFinishFailBeforeRequestOrStoppedProofProcessing() {
        var target=mock(ThsMemberTarget.class);var pages=mock(TusharePageService.class);when(target.tableName()).thenReturn("ths_member");
        var owner=new ThsMemberJobService(target,pages,temp.resolve("ledger.sqlite3"));clearInvocations(target);
        String message="Formal ths_member publication requires separate consumer cutover; configure an isolated table";
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.run(null)).getMessage());
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.finishInterrupted(null,false)).getMessage());
        assertEquals(message,assertThrows(IllegalStateException.class,()->owner.execute("run",null,null)).getMessage());
        verifyNoInteractions(target,pages);assertFalse(Files.exists(temp.resolve("ledger.sqlite3")));
    }
    @Test void indexPreflightFailureOccursBeforeCreatingTheLedger() {
        var target=mock(ThsIndexTarget.class);var table=mock(ThsIndexTables.Table.class);var pages=mock(TusharePageService.class);
        when(target.tableName()).thenReturn("ths_index");when(target.open("ths_index")).thenReturn(table);var failure=new IllegalStateException("offline preflight");when(table.preflight()).thenThrow(failure);
        Path path=temp.resolve("ledger.sqlite3");var owner=new ThsIndexJobService(target,pages,path);
        assertSame(failure,assertThrows(IllegalStateException.class,()->owner.execute("run",null,owner.plan(DAY))));assertFalse(Files.exists(path));verifyNoInteractions(pages);verify(target,never()).newStaging();
    }
    @Test void memberPreflightFailureOccursBeforeCreatingTheLedger() {
        var target=mock(ThsMemberTarget.class);var table=mock(ThsMemberTables.Table.class);var pages=mock(TusharePageService.class);
        when(target.tableName()).thenReturn("java_ths_member_contract");when(target.open(anyString())).thenReturn(table);var failure=new IllegalStateException("offline preflight");when(table.preflight()).thenThrow(failure);
        Path path=temp.resolve("ledger.sqlite3");var owner=new ThsMemberJobService(target,pages,path);
        assertSame(failure,assertThrows(IllegalStateException.class,()->owner.execute("run",null,owner.plan(BOARD,DAY))));assertFalse(Files.exists(path));verifyNoInteractions(pages);verify(target,never()).newStaging();
    }
    @Test void indexParentCancellationStopsBeforeConstructingASourceAndReleasesTheChildLease()throws Exception {
        var target=mock(ThsIndexTarget.class);var table=mock(ThsIndexTables.Table.class);var pages=mock(TusharePageService.class);Path path=temp.resolve("ledger.sqlite3");
        when(target.tableName()).thenReturn("ths_index");when(target.open("ths_index")).thenReturn(table);when(table.preflight()).thenReturn(new ThsIndexState.Identity(1,"g1"));when(target.identify("ths_index",1,"g1")).thenReturn(ID);
        var owner=new ThsIndexJobService(target,pages,path);var request=owner.plan(DAY);var ledger=cancelledParent(path,request);
        try(var sources=mockConstruction(ThsIndexSource.class)) {
            var result=owner.execute("child","parent",request);assertEquals(SyncRunState.CANCELLED,result.state());assertTrue(sources.constructed().isEmpty());
        }
        assertEquals("parent",ledger.getRun("child").parentRunId());assertEquals(SyncRunState.CANCELLED,ledger.get("child-snapshot").state());
        assertNull(new DatasetIntervalLock(path).findOwned("child",DatasetIntervalLock.Scope.allDates("ths_index")));
        verify(table,never()).snapshot();verify(target,never()).newStaging();verifyNoInteractions(pages);
    }
    @Test void memberParentCancellationStopsBeforeSourceAndStaging()throws Exception {
        var target=memberTarget();var pages=mock(TusharePageService.class);Path path=temp.resolve("ledger.sqlite3");var owner=new ThsMemberJobService(target,pages,path);var request=owner.plan(BOARD,DAY);var ledger=cancelledParent(path,request);
        try(var sources=mockConstruction(ThsMemberSource.class)) {
            var result=owner.execute("child","parent",request);assertEquals(SyncRunState.CANCELLED,result.state());assertTrue(sources.constructed().isEmpty());
        }
        assertEquals("parent",ledger.getRun("child").parentRunId());assertEquals(SyncRunState.CANCELLED,ledger.get("child-board").state());
        assertNull(new DatasetIntervalLock(path).findOwned("child",DatasetIntervalLock.Scope.allDates("ths_member")));verify(target,never()).newStaging();verifyNoInteractions(pages);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void emptyMemberSourceCannotDeleteAnExistingBoardButCanCertifyAnAlreadyEmptyBoard(boolean existing)throws Exception {
        var target=memberTarget();var table=target.open("java_ths_member_contract");var stage=mock(ThsMemberTarget.StageWriter.class);when(target.newStaging()).thenReturn(stage);
        List<ThsMemberRow> rows=existing?List.of(new ThsMemberRow(BOARD,"000001.SZ","old",null,null,null,null,OBSERVED)):List.of();
        var before=new ThsMemberState.Snapshot(new ThsMemberState.Identity(1,"g1",7),BOARD,rows,3,"other-fingerprint");
        var prepared=new ThsMemberState.Prepared("java_ths_member_contract",BOARD,before,List.of());
        when(stage.prepare("java_ths_member_contract",BOARD,List.of())).thenReturn(prepared);when(table.snapshot(BOARD)).thenReturn(before);
        var pages=mock(TusharePageService.class);Path path=temp.resolve("ledger.sqlite3");var owner=new ThsMemberJobService(target,pages,path);
        try(var sources=mockConstruction(ThsMemberSource.class,(source,context)->when(source.fetchBoard(eq(BOARD),any(),any())).thenReturn(new SyncJobRunner.Page<>(List.of(),"empty-hash","empty-receipt",null)))) {
            var result=owner.execute("run",null,owner.plan(BOARD,DAY));assertEquals(existing?SyncRunState.FAILED:SyncRunState.VERIFIED_EMPTY,result.state(),result::toString);
            if(existing){assertEquals("IllegalStateException",result.errorCode());verify(stage,never()).requiresWrite(any());}else{assertEquals(3,result.copiedOtherRows());assertNull(result.errorCode());}
        }
        if(!existing)for(String id:List.of("run","run-attempt","run-board")) {
            var entry=SyncRunLedger.openReadOnly(path).get(id);assertEquals(SyncRunState.VERIFIED_EMPTY,entry.state());
            var proof=JobDefinitionJson.mapper().readTree(entry.payloadJson());
            assertTrue(proof.path("sourceComplete").isBoolean());assertTrue(proof.path("sourceComplete").booleanValue());
            assertTrue(proof.path("returnedRows").isIntegralNumber());assertEquals(0,proof.path("returnedRows").longValue());
            assertTrue(proof.path("submittedRows").isIntegralNumber());assertEquals(0,proof.path("submittedRows").longValue());
            assertEquals("empty-receipt",proof.path("responseEvidence").textValue());
            assertTrue(proof.path("verification").path("passed").booleanValue());assertTrue(proof.path("verification").path("writerStopped").booleanValue());
            assertEquals(0,proof.path("verification").path("actualRows").intValue());
        }
        verify(stage,never()).write(any(),any(),any());verify(target,never()).publicationTables();verifyNoInteractions(pages);
        assertNull(new DatasetIntervalLock(path).findOwned("run",DatasetIntervalLock.Scope.allDates("ths_member")));
    }
    @Test void emptyIndexDirectoryFailsBeforeReadingOrPreparingATargetSnapshot()throws Exception {
        var target=mock(ThsIndexTarget.class);var table=mock(ThsIndexTables.Table.class);when(target.tableName()).thenReturn("ths_index");when(target.open("ths_index")).thenReturn(table);
        when(table.preflight()).thenReturn(new ThsIndexState.Identity(1,"g1"));when(target.identify("ths_index",1,"g1")).thenReturn(ID);
        var pages=mock(TusharePageService.class);Path path=temp.resolve("ledger.sqlite3");var owner=new ThsIndexJobService(target,pages,path);
        try(var sources=mockConstruction(ThsIndexSource.class,(source,context)->when(source.fetch(any(),any(),any())).thenReturn(new SyncJobRunner.Page<>(List.of(),"empty-hash","empty-receipt",null)))) {
            assertEquals(SyncRunState.FAILED,owner.execute("run",null,owner.plan(DAY)).state());
        }
        verify(table,never()).snapshot();verify(target,never()).prepare(any(),any(),any());verify(target,never()).newStaging();verifyNoInteractions(pages);
    }
    private static ThsMemberTarget memberTarget() {
        var target=mock(ThsMemberTarget.class);var table=mock(ThsMemberTables.Table.class);when(target.tableName()).thenReturn("java_ths_member_contract");when(target.open("java_ths_member_contract")).thenReturn(table);
        when(table.preflight()).thenReturn(new ThsMemberState.Identity(1,"g1",7));when(target.identify("java_ths_member_contract",1,"g1")).thenReturn(ID);return target;
    }
    private static SyncRunLedger cancelledParent(Path path,SyncJobDefinition.FrozenRequest request)throws Exception {
        var ledger=new SyncRunLedger(path);ledger.createRun("parent",null,ID,request);ledger.transition("parent",0,SyncRunState.RUNNING,"{}");assertTrue(ledger.requestCancellation("parent"));return ledger;
    }
}
