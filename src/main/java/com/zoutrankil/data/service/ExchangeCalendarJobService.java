package com.zoutrankil.data.service;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** D001 owner: finite manual invocation, verified coverage plus actual target reads before incremental planning. */
@Service
public class ExchangeCalendarJobService implements SyncJobOwner {
    public record Plan(SyncJobDefinition.FrozenRequest request,String targetId,
                       Map<String,LocalDate> checkpointCandidates,int checkedTargetRows) {
        public Plan {
            Objects.requireNonNull(request); Objects.requireNonNull(targetId);
            checkpointCandidates=Map.copyOf(checkpointCandidates);
            if(checkedTargetRows<0) throw new IllegalArgumentException("Negative checked row count");
        }
    }
    private final TusharePageService pages;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties properties;
    private final Path ledgerPath;
    private final String table;
    @org.springframework.beans.factory.annotation.Autowired
    public ExchangeCalendarJobService(TusharePageService pages,org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Lazy QuestDB questdb,QuestDbProperties properties,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(pages,jdbc,questdb,properties,ledgerPath,ExchangeCalendarDataset.DEFINITION.objectName());
    }
    ExchangeCalendarJobService(TusharePageService pages,org.springframework.jdbc.core.JdbcTemplate jdbc,
            QuestDB questdb,QuestDbProperties properties,String ledgerPath,String table) {
        this.pages=pages;this.jdbc=jdbc;this.questdb=questdb;this.properties=properties;
        this.ledgerPath=Path.of(ledgerPath).toAbsolutePath().normalize();
        DatasetDefinition.identifier(table);this.table=table;
    }
    public String datasetId() { return "exchange_calendar"; }
    public String tableName() { return table; }
    public Set<SyncJobDefinition.Mode> supportedSyncModes() { return ExchangeCalendarSyncAdapter.definition(true).supportedModes(); }
    public List<SyncJobDefinition> syncJobDefinitions() { return List.of(ExchangeCalendarSyncAdapter.definition(true)); }
    public String targetId() throws Exception {
        var rows=jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?",table);
        if(rows.size()!=1 || !(rows.getFirst().get("id") instanceof Number) || rows.getFirst().get("directoryName")==null)
            throw new IllegalStateException("Exact calendar physical identity required");
        String identity=properties.getHost()+":"+properties.getPgPort()+":"+properties.getQwpPort()+":"
                +properties.getDatabase()+":"+table+":"+rows.getFirst().get("id")+":"+rows.getFirst().get("directoryName");
        return "questdb-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    public Plan plan(List<String> exchanges,LocalDate bootstrapFrom,LocalDate end,LocalDate logicalDate,
                     SyncJobDefinition.Mode mode) throws Exception {
        var definition=ExchangeCalendarSyncAdapter.definition(true);
        var effective=mode==null?definition.defaultMode():mode;
        // Validate caller bounds and parameters before any storage access.
        definition.freeze(effective,Map.of("exchanges",exchanges),bootstrapFrom,end,logicalDate);
        String target=targetId();var port=new ExchangeCalendarWritePort(table,jdbc,questdb);
        port.preflight();
        Map<String,LocalDate> candidates=Map.of();int checked=0;LocalDate from=bootstrapFrom;
        if(effective==SyncJobDefinition.Mode.INCREMENTAL) {
            if(Files.isRegularFile(ledgerPath) && ExchangeCalendarCoverage.hasHistorySchema(ledgerPath))
                candidates=ExchangeCalendarCoverage.load(
                    SyncRunLedger.openReadOnly(ledgerPath),target,exchanges,bootstrapFrom);
            for(var entry:candidates.entrySet()) {
                if(entry.getValue().isAfter(end)) throw new IllegalArgumentException("Checkpoint exceeds request end; use bounded reconcile");
                for(var slice:ExchangeCalendarSlices.bounded(List.of(entry.getKey()),bootstrapFrom,entry.getValue())) {
                    var keys=slice.from().datesUntil(slice.to().plusDays(1)).map(day->new ExchangeCalendar.Key(slice.exchange(),day)).toList();
                    checked+=ExchangeCalendarSlices.complete(slice,port.readback(keys)).size();
                }
            }
            // A single job freezes a shared window: use the earliest exchange start, never skip a lagging exchange.
            from=ExchangeCalendarSlices.incremental(exchanges,bootstrapFrom,end,candidates,definition.revisionDays())
                    .stream().map(ExchangeCalendarSlices.Slice::from).min(LocalDate::compareTo).orElseThrow();
        }
        var request=definition.freeze(effective,Map.of("exchanges",exchanges),from,end,logicalDate);
        return new Plan(request,target,candidates,checked);
    }
    public SyncJobRunner.Result run(List<String> exchanges,LocalDate bootstrapFrom,LocalDate end,LocalDate logicalDate,
                                    SyncJobDefinition.Mode mode) throws Exception {
        return execute(plan(exchanges,bootstrapFrom,end,logicalDate,mode),null);
    }
    public SyncJobRunner.Result execute(Plan plan,String priorRun) throws Exception {
        return execute("calendar-"+UUID.randomUUID(),null,plan,priorRun);
    }
    public SyncJobRunner.Result runAsGroupChild(String runId,String parentId,String priorRun,
            String expectedTarget,SyncJobDefinition.FrozenRequest request) throws Exception {
        return execute(runId,Objects.requireNonNull(parentId),new Plan(request,expectedTarget,Map.of(),0),priorRun);
    }
    public String revalidateGroupChild(String priorChild,String expectedTarget,
            SyncJobDefinition.FrozenRequest request,String parentId) throws Exception {
        if(!targetId().equals(expectedTarget)) throw new IllegalStateException("Calendar group target changed");
        var ledger=new SyncRunLedger(ledgerPath);
        var evidence=ledgerPath.getParent().resolve("sync-evidence").resolve("calendar-recheck-"+UUID.randomUUID());
        var adapter=new ExchangeCalendarSyncAdapter(new ExchangeCalendarSource(pages,evidence),
                new ExchangeCalendarWritePort(table,jdbc,questdb),evidence);
        return VerifiedRunRecovery.revalidate(ledger,priorChild,expectedTarget,request,adapter,
                cancellation(ledger,Objects.requireNonNull(parentId)),evidence);
    }
    private java.util.function.BooleanSupplier cancellation(SyncRunLedger ledger,String parentId) {
        return () -> {
            if(Thread.currentThread().isInterrupted()) return true;
            if(parentId==null) return false;
            try { return ledger.cancellationRequested(parentId); }
            catch(java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot read calendar parent cancellation",failure);
            }
        };
    }
    private SyncJobRunner.Result execute(String runId,String parentId,Plan plan,String priorRun) throws Exception {
        if(!targetId().equals(plan.targetId())) throw new IllegalStateException("Calendar target changed since planning");
        var evidence=ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
        var adapter=new ExchangeCalendarSyncAdapter(new ExchangeCalendarSource(pages,evidence),
                new ExchangeCalendarWritePort(table,jdbc,questdb),evidence);
        var ledger=new SyncRunLedger(ledgerPath);
        var runner=new SyncJobRunner<ExchangeCalendar,ExchangeCalendar.Key>(ledger,new DatasetIntervalLock(ledgerPath));
        var cancelled=cancellation(ledger,parentId);
        return priorRun==null?runner.run(runId,parentId,plan.targetId(),plan.request(),adapter,cancelled)
                :runner.resume(runId,parentId==null?priorRun:parentId,priorRun,plan.targetId(),plan.request(),adapter,cancelled);
    }
}
