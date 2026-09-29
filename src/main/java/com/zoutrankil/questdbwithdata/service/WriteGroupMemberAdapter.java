package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Prepared member boundary shared by WAL pages and whole-table static publication. */
public interface WriteGroupMemberAdapter {
    WriteGroupPlan.Member member();
    FrozenRequest request();
    void preflight(FrozenRequest request) throws Exception;
    SyncJobRunner.Result execute(SyncRunLedger ledger,DatasetIntervalLock locks,String child,String parent,
                                 String prior,String target,FrozenRequest request,BooleanSupplier cancelled)
            throws Exception;
    String revalidate(SyncRunLedger ledger,String prior,String target,FrozenRequest request,
                      BooleanSupplier cancelled,Path evidence) throws Exception;
}
