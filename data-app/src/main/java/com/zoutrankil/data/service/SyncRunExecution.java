package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Builds each run's adapter after its ledger, preserving writer and recovery construction order. */
public final class SyncRunExecution {
    private SyncRunExecution() {}

    @FunctionalInterface
    public interface AdapterFactory<T,K> {
        SyncJobRunner.Adapter<T,K> create() throws Exception;
    }

    public static <T,K> SyncJobRunner.Result execute(Path ledgerPath,String runId,String priorRunId,String targetId,
                                                     FrozenRequest request,String cancellationFailureMessage,
                                                     AdapterFactory<T,K> factory) throws Exception {
        var ledger=new SyncRunLedger(ledgerPath);
        var adapter=factory.create();
        var runner=new SyncJobRunner<T,K>(ledger,new DatasetIntervalLock(ledgerPath));
        BooleanSupplier cancelled=() -> {
            if(Thread.currentThread().isInterrupted()) return true;
            try { return ledger.cancellationRequested(runId); }
            catch(java.sql.SQLException failure) { throw new IllegalStateException(cancellationFailureMessage,failure); }
        };
        return priorRunId==null ? runner.run(runId,null,targetId,request,adapter,cancelled)
                : runner.resume(runId,priorRunId,priorRunId,targetId,request,adapter,cancelled);
    }
}
