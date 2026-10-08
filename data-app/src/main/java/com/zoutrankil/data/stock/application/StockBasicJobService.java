package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.stock.port.StockBasicTarget;


import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Manual, bounded entry point using one configured control store across invocations. */
@Service
public class StockBasicJobService {
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final StockBasicMapper mapper;
    private final StockBasicTarget target;
    private final Path ledgerPath;
    public StockBasicJobService(@Lazy SyncJobRegistry jobs, TusharePageService pages, StockBasicMapper mapper,
                               StockBasicTarget target,
                               @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jobs=jobs; this.pages=pages; this.mapper=mapper; this.target=target;
        this.ledgerPath=Path.of(ledgerPath).toAbsolutePath().normalize();
    }
    public SyncJobRunner.Result run(List<String> codes, LocalDate logicalDate) throws Exception {
        return execute("run-"+UUID.randomUUID(),null,null,codes,logicalDate,targetId());
    }
    public SyncJobRunner.Result resume(List<String> codes,LocalDate logicalDate,String priorRunId) throws Exception {
        return execute("run-"+UUID.randomUUID(),null,Objects.requireNonNull(priorRunId),
                codes,logicalDate,targetId());
    }
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentGroupRunId,String priorChildRunId,
                                                String expectedTargetId,List<String> codes,LocalDate logicalDate)
            throws Exception {
        String actual=targetId();
        if(!actual.equals(expectedTargetId)) throw new IllegalStateException("Group target changed before child run");
        return execute(runId,parentGroupRunId,priorChildRunId,codes,logicalDate,actual);
    }
    public String targetId() throws Exception { return target.targetId(); }
    public String revalidateGroupChild(String priorChild, String expectedTarget,
            SyncJobDefinition.FrozenRequest request, String parentGroupRunId) throws Exception {
        if (!targetId().equals(expectedTarget)) throw new IllegalStateException("Group target identity changed");
        var ledger = new SyncRunLedger(ledgerPath);
        var evidence = ledgerPath.getParent().resolve("sync-evidence").resolve("recheck-" + UUID.randomUUID());
        var adapter = new StockBasicSyncAdapter(pages, mapper,
                target.newWriter(), evidence);
        return VerifiedRunRecovery.revalidate(ledger, priorChild, expectedTarget, request, adapter, () -> {
            try { return ledger.cancellationRequested(parentGroupRunId); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot read group cancellation", failure); }
        }, evidence);
    }
    private SyncJobRunner.Result execute(String run,String parentRunId,String priorRunId,
                                         List<String> codes,LocalDate logicalDate,String targetId) throws Exception {
        var request=jobs.prepare("data.stock_basic",2,null,Map.of("codes",codes),null,null,logicalDate);
        String table=StockBasicDataset.DEFINITION.objectName();
        var ledger=new SyncRunLedger(ledgerPath);
        var adapter=new StockBasicSyncAdapter(pages,mapper,
                target.newWriter(),
                ledgerPath.getParent().resolve("sync-evidence").resolve(run));
        var runner=new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger,new DatasetIntervalLock(ledgerPath));
        java.util.function.BooleanSupplier cancelled = () -> {
            if (Thread.currentThread().isInterrupted()) return true;
            if (parentRunId == null) return false;
            try { return ledger.cancellationRequested(parentRunId); }
            catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot read parent group cancellation", failure);
            }
        };
        return priorRunId==null ? runner.run(run,parentRunId,targetId,request,adapter,
                cancelled)
                : runner.resume(run,parentRunId==null?priorRunId:parentRunId,priorRunId,targetId,
                request,adapter,cancelled);
    }
}
