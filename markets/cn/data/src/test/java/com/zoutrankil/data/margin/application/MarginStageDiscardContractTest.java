package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.storage.*;
import com.zoutrankil.data.margin.domain.*;
import io.questdb.client.QuestDB;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarginStageDiscardContractTest {
    private static final String LOGICAL="static-v2-"+"a".repeat(64),PHYSICAL="static-v2-"+"b".repeat(64),STAGED="static-v2-"+"c".repeat(64),HASH="d".repeat(64);
    @TempDir Path temp;

    /** Real durable authority and owned proof; mocks replace only physical QuestDB operations. */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void validFrozenLogicalIdentityAllowsOwnedUnpublishedStageDiscard(boolean zrz)throws Exception {
        String prefix=zrz?"java_d031_margin_zrz_":"java_d028_margin_all_",target=prefix+"contract",stage=prefix+"stage_"+"1".repeat(32),run="owned-run";
        var definition=zrz?MarginZrzSyncJobOwner.DEFINITION:MarginAllSyncJobOwner.DEFINITION;
        LocalDate day=LocalDate.of(2025,7,1);
        var request=definition.freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("targetId",LOGICAL,"physicalTargetId",PHYSICAL,"targetRowsBefore",0,"targetFingerprint",HASH),day,day,day);
        Path ledgerPath=temp.resolve("ledger.sqlite3"),root=temp.resolve("sync-evidence").resolve(run),intentPath=root.resolve("stage").resolve(stage+"-intent.json");
        var ledger=new SyncRunLedger(ledgerPath);ledger.createRun(run,null,LOGICAL,request);ledger.transition(run,0,SyncRunState.RUNNING,"{}");ledger.transition(run,1,SyncRunState.IN_DOUBT,"{}");
        var proof=new LinkedHashMap<String,Object>();proof.put("dataset",definition.datasetId());proof.put("phase","READY");proof.put("runId",run);proof.put("target",target);proof.put("logicalTargetId",LOGICAL);proof.put("physicalTargetBefore",PHYSICAL);proof.put("stage",stage);proof.put("stagePhysicalTarget",STAGED);proof.put("requestFingerprint",SyncRequestIdentity.fingerprint(request,LOGICAL));proof.put("mode",request.mode());proof.put("logicalDate",day);proof.put("windowFrom",day);proof.put("windowTo",day);proof.put("dedup",false);
        proof.put("before",Map.of("identity",Map.of("id",1,"directory","g1","writerTxn",7),"rows",0,"fingerprint",HASH,"bytes",0));
        Files.createDirectories(intentPath.getParent());Files.write(intentPath,JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof));
        var source=mock(JdbcTemplate.class);when(source.getDataSource()).thenReturn(mock(DataSource.class));
        try(var jdbc=mockConstruction(JdbcTemplate.class,(client,ctx)->when(client.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",stage)).thenReturn(List.of(Map.of("id",2,"directoryName","g2"))))) {
            if(zrz) {
                var before=new MarginZrzState.Snapshot(new MarginZrzState.Identity(1,"g1",7),List.of(),HASH,0);
                var staged=new MarginZrzState.Snapshot(new MarginZrzState.Identity(2,"g2",8),List.of(),HASH,0);
                try(var stores=mockConstruction(MarginZrzStorage.class,(store,ctx)->when(store.snapshot()).thenReturn(target.equals(ctx.arguments().get(1))?before:staged));var ids=mockStatic(MarginZrzStorage.class)) {
                    ids.when(()->MarginZrzStorage.physicalTargetId(source,target,before.identity())).thenReturn(PHYSICAL);ids.when(()->MarginZrzStorage.physicalTargetId(source,stage,staged.identity())).thenReturn(STAGED);
                    assertDoesNotThrow(()->MarginZrzStaging.discardUnpublished(new QuestDbMarginZrzTarget(target,source,mock(QuestDB.class)),target,run,root,ledgerPath,true));
                }
            } else {
                var before=new MarginAllState.Snapshot(new MarginAllState.Identity(1,"g1",7),List.of(),HASH,0);
                var staged=new MarginAllState.Snapshot(new MarginAllState.Identity(2,"g2",8),List.of(),HASH,0);
                try(var stores=mockConstruction(MarginAllStorage.class,(store,ctx)->when(store.snapshot()).thenReturn(target.equals(ctx.arguments().get(1))?before:staged));var ids=mockStatic(MarginAllStorage.class)) {
                    ids.when(()->MarginAllStorage.physicalTargetId(source,target,before.identity())).thenReturn(PHYSICAL);ids.when(()->MarginAllStorage.physicalTargetId(source,stage,staged.identity())).thenReturn(STAGED);
                    assertDoesNotThrow(()->MarginAllStaging.discardUnpublished(new QuestDbMarginAllTarget(target,source,mock(QuestDB.class)),target,run,root,ledgerPath,true));
                }
            }
            assertEquals(1,jdbc.constructed().size());verify(jdbc.constructed().getFirst()).execute("DROP TABLE \""+stage+"\"");
        }
        var discarded=JobDefinitionJson.mapper().readTree(intentPath.toFile());assertEquals("DISCARDED",discarded.path("phase").asText());assertTrue(discarded.path("discardedWithStoppedWriter").asBoolean());
        assertEquals(SyncRunState.IN_DOUBT,ledger.get(run).state());assertEquals(LOGICAL,ledger.getRun(run).targetId());
    }
}
