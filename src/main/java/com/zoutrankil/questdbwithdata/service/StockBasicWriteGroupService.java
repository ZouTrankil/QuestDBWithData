package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.StockBasicWritePort;
import com.zoutrankil.questdbwithdata.repository.ExchangeCalendarWritePort;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import com.zoutrankil.questdbwithdata.mapper.ExchangeCalendarMapper;
import io.questdb.client.QuestDB;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Application entry for admitted dataset writers; every member uses its owning typed port. */
@Service
public class StockBasicWriteGroupService {
    private final DatasetRegistry datasets;
    private final StockBasicJobService target;
    private final ExchangeCalendarJobService calendarTarget;
    private final StockDetailInfoJobService stockDetailTarget;
    private final IndexCatalogJobService indexCatalogTarget;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledger;
    @org.springframework.beans.factory.annotation.Autowired
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            IndexCatalogJobService indexCatalogTarget,
            JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.datasets=datasets; this.target=target; this.jdbc=jdbc; this.questdb=questdb;
        this.calendarTarget=calendarTarget;this.stockDetailTarget=stockDetailTarget;
        this.indexCatalogTarget=indexCatalogTarget;
        this.ledger=Path.of(ledger).toAbsolutePath().normalize();
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            JdbcTemplate jdbc,QuestDB questdb,String ledger) {
        this(datasets,target,calendarTarget,stockDetailTarget,null,jdbc,questdb,ledger);
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,JdbcTemplate jdbc,QuestDB questdb,String ledger) {
        this(datasets,target,calendarTarget,null,null,jdbc,questdb,ledger);
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target, JdbcTemplate jdbc,
            QuestDB questdb, String ledger) {
        this.datasets=datasets; this.target=target; this.calendarTarget=null; this.jdbc=jdbc; this.questdb=questdb;
        this.stockDetailTarget=null;this.indexCatalogTarget=null;
        this.ledger=Path.of(ledger).toAbsolutePath().normalize();
    }
    public SyncGroupRunner.Result run(Path file, String priorRun) throws Exception {
        var request = new WriteGroupJson(datasets).read(file);
        var targets = new LinkedHashMap<String,String>();
        var frozenSnapshot = request.logicalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        for (var member : request.members()) {
            if (member.datasetId().equals(StockBasicDataset.DEFINITION.datasetId())) {
                for (var row : member.rows()) if (!frozenSnapshot.equals(row.get("snapshot_ts", Instant.class)))
                    throw new IllegalArgumentException("Stock-basic snapshot differs from frozen logical date");
                targets.put(member.datasetId(), target.targetId());
            } else if (member.datasetId().equals(ExchangeCalendarDataset.DEFINITION.datasetId())
                    && calendarTarget != null) {
                targets.put(member.datasetId(), calendarTarget.targetId());
            } else if(member.datasetId().equals(StockDetailInfoDataset.DEFINITION.datasetId())
                    && stockDetailTarget!=null) {
                targets.put(member.datasetId(),stockDetailTarget.targetId());
            } else if(member.datasetId().equals(IndexCatalogDataset.DEFINITION.datasetId())
                    && indexCatalogTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),indexCatalogTarget.targetId());
                else {
                    var prior=SyncRunLedger.openReadOnly(ledger).groupMembers(priorRun).stream()
                            .filter(m->m.jobId().equals("write.index")).toList();
                    if(prior.size()!=1 || prior.getFirst().childRunId()==null)
                        throw new IllegalStateException("Prior catalog write child identity required");
                    targets.put(member.datasetId(),SyncRunLedger.openReadOnly(ledger)
                            .getRun(prior.getFirst().childRunId()).targetId());
                }
            } else throw new IllegalArgumentException("No admitted write owner for requested dataset");
        }
        var plan = WriteGroupPlan.prepare(request, datasets, targets);
        String run = "write-group-" + UUID.randomUUID();
        Path evidence = ledger.getParent().resolve("write-evidence");
        var adapters = new LinkedHashMap<String,WriteGroupMemberAdapter>();
        for (var member : plan.members()) {
            if (member.definition().datasetId().equals(StockBasicDataset.DEFINITION.datasetId())) {
                var port = new StockBasicWritePort(StockBasicDataset.DEFINITION.objectName(), jdbc, questdb);
                adapters.put(member.memberId(), new PreparedWriteAdapter<>(plan, member.memberId(),
                        StockBasicWriteGroupService::decode, StockBasicWriteGroupService::encode,
                        StockBasicWritePort.CODEC, port, () -> {
                            try { return target.targetId(); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot resolve stock target", failure); }
                        }, evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(ExchangeCalendarDataset.DEFINITION.datasetId())) {
                var mapper = new ExchangeCalendarMapper();
                var port = new ExchangeCalendarWritePort(calendarTarget.tableName(), jdbc, questdb);
                adapters.put(member.memberId(), new PreparedWriteAdapter<>(plan, member.memberId(),
                        mapper::fromValues, mapper::values, ExchangeCalendarWritePort.CODEC, port, () -> {
                            try { return calendarTarget.targetId(); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot resolve calendar target", failure); }
                        }, evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockDetailInfoDataset.DEFINITION.datasetId())) {
                adapters.put(member.memberId(),new StaticStockDetailWriteAdapter(plan,member.memberId(),
                        stockDetailTarget,evidence.resolve(run)));
            } else if(member.definition().datasetId().equals(IndexCatalogDataset.DEFINITION.datasetId())) {
                adapters.put(member.memberId(),new IndexCatalogPreparedWriteAdapter(plan,member.memberId(),
                        indexCatalogTarget,evidence.resolve(run),priorRun!=null));
            } else throw new IllegalArgumentException("No prepared adapter for dataset");
        }
        return new PersistentWriteGroupRunner(ledger, evidence, datasets)
                .run(run, plan, adapters, priorRun);
    }
    static StockBasicSnapshot decode(DatasetValues row) {
        return new StockBasicSnapshot(row.get("snapshot_ts", Instant.class), new StockBasic(row.get("ts_code", String.class),
                row.get("symbol", String.class), row.get("name", String.class), row.get("area", String.class),
                row.get("industry", String.class), row.get("list_date", LocalDate.class)));
    }
    static DatasetValues encode(StockBasicSnapshot row) {
        var values = new LinkedHashMap<String,Object>(); var stock = row.stock();
        values.put("snapshot_ts", row.snapshotTimestamp()); values.put("ts_code", stock.tsCode());
        values.put("symbol", stock.symbol()); values.put("name", stock.name()); values.put("area", stock.area());
        values.put("industry", stock.industry()); values.put("list_date", stock.listDate());
        return new DatasetValues(values);
    }
}
