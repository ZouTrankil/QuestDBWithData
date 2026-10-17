package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.util.UUID;

/** Owner supplies its own target identity and adapter; completed requests are never replanned. */
public final class CompletedSourceRevalidation {
    private CompletedSourceRevalidation() {}
    @FunctionalInterface public interface TargetIdentity { String get()throws Exception; }
    /** preflight/fetch must not mutate database state; staged-publication adapters need a read-only variant. */
    @FunctionalInterface public interface AdapterFactory<T,K> {
        SyncJobRunner.Adapter<T,K> create(Path evidence,String target)throws Exception;
    }
    public static <T,K> String revalidate(Path ledgerPath,String runId,SyncJobDefinition definition,
            TargetIdentity identity,AdapterFactory<T,K> factory)throws Exception {
        var saved=FrozenRunRequest.restoreCompleted(ledgerPath,runId,definition);
        if(!saved.targetId().equals(identity.get()))throw new IllegalStateException("Completed source target changed");
        var evidence=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence")
                .resolve("source-recheck-"+UUID.randomUUID());
        var adapter=factory.create(evidence,saved.targetId());
        return VerifiedRunRecovery.revalidate(SyncRunLedger.openReadOnly(ledgerPath),runId,saved.targetId(),
                saved.request(),adapter,()-> {
                    if(Thread.currentThread().isInterrupted())return true;
                    try {
                        if(!saved.targetId().equals(identity.get()))throw new IllegalStateException("Source target changed during revalidation");
                        return false;
                    } catch(Exception failure) {throw new IllegalStateException("Cannot confirm current source target",failure);}
                },evidence);
    }
}
