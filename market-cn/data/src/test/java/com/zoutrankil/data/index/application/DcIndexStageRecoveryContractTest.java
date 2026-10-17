package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static com.zoutrankil.data.index.application.DcIndexPublicationContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A complete stage survives process death before any publication journal was created. */
class DcIndexStageRecoveryContractTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void reconcilesFrozenStageWithoutSendingOrRecollectingSource(boolean empty)throws Exception {
        var f=new Fixture(temp,empty);var staging=stageOnly(f);
        DcIndexRunRecovery.finishStageOnly(f.target,f.path,RUN,true);
        assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());
        assertTrue(f.locks.findOwned(RUN,f.scope()).inDoubt());
        assertEquals(SyncRunState.FETCHED,f.ledger.get(RUN+"-slice").state());
        DcIndexRunRecovery.finishInterrupted(f.target,f.path,RUN,true);
        assertEquals(empty?SyncRunState.VERIFIED_EMPTY:SyncRunState.VERIFIED,f.ledger.get(RUN).state());
        assertEquals(f.expected,f.tables.rows.get(TABLE));assertEquals(2,f.tables.renames);assertEquals(1,f.pages.calls);
        assertNull(f.locks.findOwned(RUN,f.scope()));f.assertMutexFree();
        verify(staging).verifyComplete(any(),any(),eq(f.combined),eq(f.evidence.resolve("staging")),any());
        verify(staging,never()).create(any(),any(),any(),any(),any(),any(),any());
        verify(f.target,never()).newWriter(anyString());verify(f.target,never()).stageWriter(anyString(),anyString());
    }
    @Test void damagedSourceIsRejectedBeforePhysicalStageVerificationOrAnyRename()throws Exception {
        var f=new Fixture(temp,false);var staging=stageOnly(f);
        Files.writeString(Path.of(f.page.responseEvidence()),"\n",StandardOpenOption.APPEND);
        assertThrows(IllegalStateException.class,()->DcIndexRunRecovery.finishStageOnly(f.target,f.path,RUN,true));
        verifyNoInteractions(staging);assertNoPublication(f);
    }
    @Test void changedOutsideReceiptIdentityCannotAuthorizeStagePublication()throws Exception {
        var f=new Fixture(temp,false);var staging=stageOnly(f);
        Path outside=f.evidence.resolve("staging").resolve(STAGE+"-outside-verified.json");
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)JobDefinitionJson.mapper().readTree(outside.toFile());body.put("stagePhysicalTarget",physical(3));
        Files.write(outside,JobDefinitionJson.mapper().writeValueAsBytes(body));
        assertThrows(IllegalStateException.class,()->DcIndexRunRecovery.finishStageOnly(f.target,f.path,RUN,true));
        verifyNoInteractions(staging);assertNoPublication(f);f.assertMutexFree();
    }
    @Test void cancellationBetweenRenamesRetainsUncertainJournalForExplicitRecovery()throws Exception {
        var f=new Fixture(temp,false);
        try(var ignored=f.publisher.acquire()) {
            var failure=assertThrows(IllegalStateException.class,()->f.publisher.publishWindow(f.before,f.complete,f.page.rows(),
                    DAY,DAY,physical(1),f.combined,f.completion.toString(),()->f.tables.renames==1));
            assertInstanceOf(java.util.concurrent.CancellationException.class,failure.getCause());
        }
        assertEquals(1,f.tables.renames);assertEquals(ReferencePublicationJournal.State.IN_DOUBT,f.journal().state());
        assertNotNull(f.locks.findOwned(RUN,f.scope()));f.assertMutexFree();
        f.publisher.finish(RUN,true);DcIndexRunRecovery.finishInterrupted(f.target,f.path,RUN,true);
        assertEquals(2,f.tables.renames);assertNull(f.locks.findOwned(RUN,f.scope()));
    }
    static void assertNoPublication(Fixture f)throws Exception {
        assertEquals(0,f.tables.renames);assertTrue(new ReferencePublicationJournal(f.path,"dc_index").findForRun(RUN).isEmpty());
        assertEquals(SyncRunState.RUNNING,f.ledger.get(RUN).state());assertNotNull(f.locks.findOwned(RUN,f.scope()));
    }
    static DcIndexStagingPort stageOnly(Fixture f)throws Exception {
        Files.delete(f.completion);Path stageRoot=f.evidence.resolve("staging");Files.createDirectories(stageRoot);
        var request=DcIndexSyncJobOwner.DEFINITION.freeze(SyncJobDefinition.Mode.INCREMENTAL,
                Map.of("targetId",LOGICAL,"physicalTargetId",physical(1),"trade_dates","20260917","checkpointAnchor",DAY),DAY,DAY,DAY);
        var intent=Map.ofEntries(Map.entry("dataset","dc_index"),Map.entry("target",TABLE),Map.entry("stage",STAGE),Map.entry("runId",RUN),
                Map.entry("logicalTargetId",LOGICAL),Map.entry("physicalTargetBefore",physical(1)),Map.entry("beforeId",1),Map.entry("beforeDirectory","old"),
                Map.entry("requestFingerprint",SyncRequestIdentity.fingerprint(request,LOGICAL)),Map.entry("mode","INCREMENTAL"),Map.entry("logicalDate",DAY.toString()),
                Map.entry("fromInclusive",DAY.toString()),Map.entry("toInclusive",DAY.toString()),Map.entry("dedup",false),
                Map.entry("beforeFingerprint",f.before.fingerprint()),Map.entry("sourceRows",f.page.rows().size()));
        FileEvidenceStore.writeNew(stageRoot.resolve(STAGE+"-intent.json"),JobDefinitionJson.mapper().writeValueAsBytes(intent));
        var outside=DcIndexRows.outside(f.before.rows(),DAY,DAY.plusDays(1));byte[] bytes=DcIndexRows.canonical(outside);
        var snapshot=new Snapshot(new Identity(2,"stage"),outside,FileEvidenceStore.sha256(bytes),bytes.length);
        var proof=Map.ofEntries(Map.entry("proofVersion",2),Map.entry("dataset","dc_index"),Map.entry("target",TABLE),Map.entry("stage",STAGE),Map.entry("dedup",false),
                Map.entry("stagePhysicalTarget",physical(2)),Map.entry("physicalTargetAfter",physical(2)),Map.entry("stageId",2),Map.entry("stageDirectory","stage"),
                Map.entry("snapshotProof",DcIndexRows.snapshotProof(snapshot)),Map.entry("outsideRows",outside.size()),Map.entry("windowFrom",DAY.toString()),Map.entry("windowTo",DAY.toString()));
        FileEvidenceStore.writeNew(stageRoot.resolve(STAGE+"-outside-verified.json"),JobDefinitionJson.mapper().writeValueAsBytes(proof));
        var staging=mock(DcIndexStagingPort.class);when(f.target.newStaging()).thenReturn(staging);
        when(staging.verifyComplete(any(),any(),eq(f.combined),eq(stageRoot),any())).thenAnswer(call->{
            Prepared prepared=call.getArgument(0);Verified verified=call.getArgument(1);
            assertEquals(f.before,prepared.before());assertEquals(f.expected,prepared.expected());assertEquals(STAGE,verified.table());
            assertEquals(f.complete.snapshot(),verified.outsideSnapshot());return f.complete;
        });return staging;
    }
}
