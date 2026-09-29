package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncGroupPlanningTest {
    @TempDir Path temp;
    private final SyncJobDefinition job=StockBasicSyncAdapter.definition(true);
    private final SyncJobRegistry jobs=new SyncJobRegistry(List.of(job),
            new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION)),
            Map.of(job.datasetId(),job.supportedModes()),new SyncJobRegistry.Policies(
            Set.of(job.ratePolicyRef()),Set.of(job.slicePolicyRef()),Set.of(job.verificationPolicyRef())));
    private final SyncGroupRegistry groups=new SyncGroupRegistry(List.of(new SyncGroupDefinition(
            "group.sample",1,List.of(new SyncGroupDefinition.Member(
            new SyncJobDefinition.JobRef(job.jobId(),job.version()),List.of())),true,false)),jobs);
    private Map<String,String> options() {
        return new HashMap<>(Map.of("--group","group.sample","--version","1",
                "--logical-date","2026-09-29","--parameters","{\"codes\":[\"000001.SZ\"]}"));
    }
    @Test void freezesRegisteredMembersWithoutStartingRunner() throws Exception {
        var plan=SyncGroupPlanning.prepare(groups,jobs,options());
        assertEquals(LocalDate.of(2026,9,29),plan.logicalDate());
        assertEquals(1,plan.requests().size());
        assertEquals(List.of("000001.SZ"),plan.requests().getFirst().parameters().get("codes"));
        assertEquals(SyncJobDefinition.Mode.SNAPSHOT,plan.requests().getFirst().mode());
    }
    @Test void explicitNullWindowClearsInheritedRangeForSnapshotMember() throws Exception {
        var options = options();
        options.put("--from","2026-09-01");
        options.put("--to","2026-09-29");
        options.put("--overrides","{\"data.stock_basic\":{\"from\":null,\"to\":null}}");
        var request = SyncGroupPlanning.prepare(groups,jobs,options).requests().getFirst();
        assertNull(request.from()); assertNull(request.to());
        assertEquals(SyncJobDefinition.Mode.SNAPSHOT,request.mode());
    }
    @Test void rejectsUnknownOverrideDuplicateJsonAndUnsupportedWindow() {
        var unknown=options(); unknown.put("--overrides","{\"other.job\":{}}");
        assertThrows(Exception.class,() -> SyncGroupPlanning.prepare(groups,jobs,unknown));
        var duplicate=options(); duplicate.put("--parameters",
                "{\"codes\":[\"000001.SZ\"],\"codes\":[\"600000.SH\"]}");
        assertThrows(Exception.class,() -> SyncGroupPlanning.prepare(groups,jobs,duplicate));
        var window=options(); window.put("--from","2026-09-29");
        assertThrows(Exception.class,() -> SyncGroupPlanning.prepare(groups,jobs,window));
        var mode=options(); mode.put("--mode","INCREMENTAL");
        assertThrows(Exception.class,() -> SyncGroupPlanning.prepare(groups,jobs,mode));
        var unsafe=options(); unsafe.put("--run","true");
        assertThrows(Exception.class,() -> SyncGroupPlanning.prepare(groups,jobs,unsafe));
    }
    @Test void fileInputsCarryTypedOverridesWithoutShellJsonQuoting() throws Exception {
        Path common=Files.writeString(temp.resolve("common.json"),"{\"codes\":[\"000001.SZ\"]}");
        Path overrides=Files.writeString(temp.resolve("members.json"),
                "{\"data.stock_basic\":{\"parameters\":{\"codes\":[\"600000.SH\"]}}}");
        var options=options(); options.remove("--parameters");
        options.put("--parameters-file",common.toString());
        options.put("--overrides-file",overrides.toString());
        var plan=SyncGroupPlanning.prepare(groups,jobs,options);
        assertEquals(List.of("600000.SH"),plan.requests().getFirst().parameters().get("codes"));
        options.put("--overrides","{}");
        assertThrows(IllegalArgumentException.class,() -> SyncGroupPlanning.prepare(groups,jobs,options));
    }
}
