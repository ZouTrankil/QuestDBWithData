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
    private final ThsIndexJobService thsIndexTarget;
    private final IndexMembershipJobService indexMembershipTarget;
    private final ThsMemberJobService thsMemberTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DailyJobService dailyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DailyBasicJobService dailyBasicTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockFactorJobService stockFactorTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockLimitJobService stockLimitTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtfDailyJobService etfDailyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EtfAdjJobService etfAdjTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EtfShareJobService etfShareTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtfBasicJobService etfBasicTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockStDailyJobService stockStDailyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockSuspendJobService stockSuspendTarget;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final Path ledger;
    @org.springframework.beans.factory.annotation.Autowired
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            IndexCatalogJobService indexCatalogTarget,ThsIndexJobService thsIndexTarget,
            IndexMembershipJobService indexMembershipTarget,ThsMemberJobService thsMemberTarget,
            JdbcTemplate jdbc, @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.datasets=datasets; this.target=target; this.jdbc=jdbc; this.questdb=questdb;
        this.calendarTarget=calendarTarget;this.stockDetailTarget=stockDetailTarget;
        this.indexCatalogTarget=indexCatalogTarget;
        this.thsIndexTarget=thsIndexTarget;
        this.indexMembershipTarget=indexMembershipTarget;
        this.thsMemberTarget=thsMemberTarget;
        this.ledger=Path.of(ledger).toAbsolutePath().normalize();
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            IndexCatalogJobService indexCatalogTarget,ThsIndexJobService thsIndexTarget,
            IndexMembershipJobService indexMembershipTarget,
            JdbcTemplate jdbc,QuestDB questdb,String ledger) {
        this(datasets,target,calendarTarget,stockDetailTarget,indexCatalogTarget,thsIndexTarget,
                indexMembershipTarget,null,jdbc,questdb,ledger);
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            IndexCatalogJobService indexCatalogTarget,ThsIndexJobService thsIndexTarget,
            JdbcTemplate jdbc,QuestDB questdb,String ledger) {
        this(datasets,target,calendarTarget,stockDetailTarget,indexCatalogTarget,thsIndexTarget,null,null,jdbc,questdb,ledger);
    }
    public StockBasicWriteGroupService(DatasetRegistry datasets, StockBasicJobService target,
            ExchangeCalendarJobService calendarTarget,StockDetailInfoJobService stockDetailTarget,
            IndexCatalogJobService indexCatalogTarget,JdbcTemplate jdbc,QuestDB questdb,String ledger) {
        this(datasets,target,calendarTarget,stockDetailTarget,indexCatalogTarget,null,null,null,jdbc,questdb,ledger);
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
        this.stockDetailTarget=null;this.indexCatalogTarget=null;this.thsIndexTarget=null;this.indexMembershipTarget=null;
        this.thsMemberTarget=null;
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
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index"));
            } else if(member.datasetId().equals(ThsIndexDataset.DEFINITION.datasetId())
                    && thsIndexTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),thsIndexTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.ths_index"));
            } else if(member.datasetId().equals(IndexMembershipDataset.DEFINITION.datasetId())
                    && indexMembershipTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),indexMembershipTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index_member"));
            } else if(member.datasetId().equals(ThsMemberDataset.DEFINITION.datasetId())
                    && thsMemberTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),thsMemberTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.ths_member"));
            } else if(member.datasetId().equals(DailyDataset.DEFINITION.datasetId()) && dailyTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),dailyTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.daily"));
            } else if(member.datasetId().equals(DailyBasicDataset.DEFINITION.datasetId()) && dailyBasicTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),dailyBasicTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.daily_basic"));
            } else if(member.datasetId().equals(StockFactorDataset.DEFINITION.datasetId()) && stockFactorTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),stockFactorTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_factor"));
            } else if(member.datasetId().equals(StockLimitDataset.DEFINITION.datasetId()) && stockLimitTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),stockLimitTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_limit"));
            } else if(member.datasetId().equals(EtfDailyDataset.DEFINITION.datasetId()) && etfDailyTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfDailyTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_daily"));
            } else if(member.datasetId().equals(EtfAdjDataset.DEFINITION.datasetId()) && etfAdjTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfAdjTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_adj"));
            } else if(member.datasetId().equals(EtfShareDataset.DEFINITION.datasetId()) && etfShareTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfShareTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_share"));
            } else if(member.datasetId().equals(EtfBasicDataset.DEFINITION.datasetId()) && etfBasicTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfBasicTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_basic"));
            } else if(member.datasetId().equals(StockStDailyDataset.DEFINITION.datasetId()) && stockStDailyTarget!=null) {
                stockStDailyTarget.requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),stockStDailyTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_st_daily"));
            } else if(member.datasetId().equals(StockSuspendDataset.DEFINITION.datasetId()) && stockSuspendTarget!=null) {
                stockSuspendTarget.requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),stockSuspendTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_suspend"));
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
            } else if(member.definition().datasetId().equals(ThsIndexDataset.DEFINITION.datasetId())) {
                adapters.put(member.memberId(),new ThsIndexPreparedWriteAdapter(plan,member.memberId(),
                        thsIndexTarget,evidence.resolve(run),priorRun!=null));
            } else if(member.definition().datasetId().equals(IndexMembershipDataset.DEFINITION.datasetId())) {
                adapters.put(member.memberId(),new IndexMembershipPreparedWriteAdapter(plan,member.memberId(),
                        indexMembershipTarget,evidence.resolve(run),priorRun!=null));
            } else if(member.definition().datasetId().equals(ThsMemberDataset.DEFINITION.datasetId())) {
                adapters.put(member.memberId(),new ThsMemberPreparedWriteAdapter(plan,member.memberId(),
                        thsMemberTarget,evidence.resolve(run),priorRun!=null));
            } else if(member.definition().datasetId().equals(DailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.DailyMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.DailyWritePort(
                        dailyTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.DailyWritePort.CODEC,port,()->{
                            try { return dailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(DailyBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.DailyBasicMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.DailyBasicWritePort(
                        dailyBasicTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.DailyBasicWritePort.CODEC,port,()->{
                            try { return dailyBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockFactorDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.StockFactorMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.StockFactorWritePort(
                        stockFactorTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.StockFactorWritePort.CODEC,port,()->{
                            try { return stockFactorTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_factor target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockLimitDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.StockLimitMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.StockLimitWritePort(
                        stockLimitTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.StockLimitWritePort.CODEC,port,()->{
                            try { return stockLimitTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_limit target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.EtfDailyMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.EtfDailyWritePort(
                        etfDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.EtfDailyWritePort.CODEC,port,()->{
                            try { return etfDailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfAdjDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.EtfAdjMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.EtfAdjWritePort(
                        etfAdjTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.EtfAdjWritePort.CODEC,port,()->{
                            try { return etfAdjTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_adj target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfShareDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.EtfShareMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.EtfShareWritePort(
                        etfShareTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.EtfShareWritePort.CODEC,port,()->{
                            try { return etfShareTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_share target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.EtfBasicMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.EtfBasicWritePort(
                        etfBasicTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.EtfBasicWritePort.CODEC,port,()->{
                            try { return etfBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockStDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.StockStDailyMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.StockStDailyWritePort(
                        stockStDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.StockStDailyWritePort.CODEC,port,()->{
                            try { stockStDailyTarget.requireNoPendingPublication(); return stockStDailyTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_st_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockSuspendDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.questdbwithdata.mapper.StockSuspendMapper();
                var port=new com.zoutrankil.questdbwithdata.repository.StockSuspendWritePort(
                        stockSuspendTarget.tableName(),jdbc,questdb,member.targetId());
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.questdbwithdata.repository.StockSuspendWritePort.CODEC,port,()->{
                            try { stockSuspendTarget.requireNoPendingPublication(); return stockSuspendTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_suspend target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
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
