package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.mapper.StockBasicMapper;
import com.zoutrankil.questdbwithdata.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Manual, bounded entry point using one configured control store across invocations. */
@Service
public class StockBasicJobService {
    private final SyncJobRegistry jobs;
    private final TusharePageService pages;
    private final StockBasicMapper mapper;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties target;
    private final Path ledgerPath;
    public StockBasicJobService(SyncJobRegistry jobs, TusharePageService pages, StockBasicMapper mapper,
                               JdbcTemplate jdbc, @Lazy QuestDB questdb, QuestDbProperties target,
                               @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jobs=jobs; this.pages=pages; this.mapper=mapper; this.jdbc=jdbc;
        this.questdb=questdb; this.target=target; this.ledgerPath=Path.of(ledgerPath).toAbsolutePath().normalize();
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
    public String targetId() throws Exception {
        String table=StockBasicDataset.DEFINITION.objectName();
        var objects=jdbc.queryForList("SELECT id, directoryName FROM tables() WHERE table_name = ?",table);
        if(objects.size()!=1 || !(objects.getFirst().get("id") instanceof Number)
                || objects.getFirst().get("directoryName")==null)
            throw new IllegalStateException("Exact physical QuestDB target identity required");
        String address=target.getHost()+":"+target.getPgPort()+":"+target.getQwpPort()+":"+target.getDatabase()
                +":"+table+":"+objects.getFirst().get("id")+":"+objects.getFirst().get("directoryName");
        return "questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(address.getBytes(StandardCharsets.UTF_8)));
    }
    private SyncJobRunner.Result execute(String run,String parentRunId,String priorRunId,
                                         List<String> codes,LocalDate logicalDate,String targetId) throws Exception {
        var request=jobs.prepare("data.stock_basic",2,null,Map.of("codes",codes),null,null,logicalDate);
        String table=StockBasicDataset.DEFINITION.objectName();
        var ledger=new SyncRunLedger(ledgerPath);
        var adapter=new StockBasicSyncAdapter(pages,mapper,
                new StockBasicWritePort(table,jdbc,questdb),
                ledgerPath.getParent().resolve("sync-evidence").resolve(run));
        var runner=new SyncJobRunner<StockBasicSnapshot,StockBasicSnapshotKey>(ledger,new DatasetIntervalLock(ledgerPath));
        return priorRunId==null ? runner.run(run,parentRunId,targetId,request,adapter,
                ()->Thread.currentThread().isInterrupted())
                : runner.resume(run,parentRunId==null?priorRunId:parentRunId,priorRunId,targetId,
                request,adapter,()->Thread.currentThread().isInterrupted());
    }
}
