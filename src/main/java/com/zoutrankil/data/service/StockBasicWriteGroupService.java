package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.StockBasicWritePort;
import com.zoutrankil.data.repository.ExchangeCalendarWritePort;
import com.zoutrankil.data.mapper.ExchangeCalendarMapper;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EtfFactorJobService etfFactorTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private IndexDailyMarketJobService indexDailyMarketTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private IndexDailyBasicJobService indexDailyBasicTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private IndexWeightJobService indexWeightTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private IndexMonthlyJobService indexMonthlyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DcIndexJobService dcIndexTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneyflowHsgtJobService moneyflowHsgtTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneyflowDcJobService moneyflowDcTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneyflowThsJobService moneyflowThsTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MoneyflowJobService moneyflowTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EtfPortfolioJobService etfPortfolioTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EtfBasicJobService etfBasicTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private L2IntradayBarFeaturesJobService l2IntradayBarFeaturesTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private L2EventResponseFeaturesJobService l2EventResponseFeaturesTarget;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private L2T0TrainingLabelsJobService l2T0TrainingLabelsTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockStDailyJobService stockStDailyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private StockSuspendJobService stockSuspendTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EtfMarketOverviewDailyCacheJobService etfMarketOverviewCacheTarget;
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
            } else if(member.datasetId().equals(L2IntradayBarFeaturesDataset.DEFINITION.datasetId())
                    && l2IntradayBarFeaturesTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),l2IntradayBarFeaturesTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.l2_intraday_bar_features"));

            } else if(member.datasetId().equals(L2EventResponseFeaturesDataset.DEFINITION.datasetId())
                    && l2EventResponseFeaturesTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),l2EventResponseFeaturesTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.l2_event_response_features"));
            } else if(member.datasetId().equals(L2T0TrainingLabelsDataset.DEFINITION.datasetId())
                    && l2T0TrainingLabelsTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),l2T0TrainingLabelsTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.l2_t0_training_labels"));
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
            } else if(member.datasetId().equals(EtfFactorDataset.DEFINITION.datasetId()) && etfFactorTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfFactorTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_factor"));
            } else if(member.datasetId().equals(MoneyflowDcDataset.DEFINITION.datasetId()) && moneyflowDcTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),moneyflowDcTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.moneyflow_dc"));
            } else if(member.datasetId().equals(MoneyflowThsDataset.DEFINITION.datasetId()) && moneyflowThsTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),moneyflowThsTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.moneyflow_ths"));
            } else if(member.datasetId().equals(MoneyflowDataset.DEFINITION.datasetId()) && moneyflowTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),moneyflowTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.moneyflow"));
            } else if(member.datasetId().equals(IndexDailyMarketDataset.DEFINITION.datasetId()) && indexDailyMarketTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),indexDailyMarketTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index_daily_market"));
            } else if(member.datasetId().equals(IndexDailyBasicDataset.DEFINITION.datasetId()) && indexDailyBasicTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),indexDailyBasicTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index_daily_basic"));
            } else if(member.datasetId().equals(IndexMonthlyDataset.DEFINITION.datasetId()) && indexMonthlyTarget!=null) {
                new IndexMonthlyPublication(jdbc,ledger).requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),indexMonthlyTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index_monthly"));
            } else if(member.datasetId().equals(IndexWeightDataset.DEFINITION.datasetId()) && indexWeightTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),indexWeightTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.index_weight"));
            } else if(member.datasetId().equals(EtfPortfolioDataset.DEFINITION.datasetId()) && etfPortfolioTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfPortfolioTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_portfolio"));
            } else if(member.datasetId().equals(EtfBasicDataset.DEFINITION.datasetId()) && etfBasicTarget!=null) {
                if(priorRun==null) targets.put(member.datasetId(),etfBasicTarget.targetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.etf_basic"));
            } else if(member.datasetId().equals(DcIndexDataset.DEFINITION.datasetId()) && dcIndexTarget!=null) {
                dcIndexTarget.requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),dcIndexTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.dc_index"));
            } else if(member.datasetId().equals(MoneyflowHsgtDataset.DEFINITION.datasetId()) && moneyflowHsgtTarget!=null) {
                new MoneyflowHsgtPublication(jdbc,ledger).requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),moneyflowHsgtTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.moneyflow_hsgt"));
            } else if(member.datasetId().equals(StockStDailyDataset.DEFINITION.datasetId()) && stockStDailyTarget!=null) {
                stockStDailyTarget.requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),stockStDailyTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_st_daily"));
            } else if(member.datasetId().equals(StockSuspendDataset.DEFINITION.datasetId()) && stockSuspendTarget!=null) {
                stockSuspendTarget.requireNoPendingPublication();
                if(priorRun==null) targets.put(member.datasetId(),stockSuspendTarget.physicalTargetId());
                else targets.put(member.datasetId(),SyncGroupTargetIdentity.frozen(ledger,priorRun,"write.stk_suspend"));
            } else if (member.datasetId().equals(EtfMarketOverviewDailyCacheDataset.DEFINITION.datasetId())
                    && etfMarketOverviewCacheTarget != null) {
                if (priorRun == null) targets.put(member.datasetId(), etfMarketOverviewCacheTarget.targetId());
                else targets.put(member.datasetId(), SyncGroupTargetIdentity.frozen(ledger, priorRun,
                        "write.etf_market_overview_daily_cache"));
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
            } else if(member.definition().datasetId().equals(L2IntradayBarFeaturesDataset.DEFINITION.datasetId())) {
                var mapper = new com.zoutrankil.data.mapper.L2IntradayBarFeaturesMapper();
                var port = new com.zoutrankil.data.repository.L2IntradayBarFeaturesWritePort(
                        l2IntradayBarFeaturesTarget.isolatedTableName(), jdbc, questdb);
                var prepared = new PreparedWriteAdapter<>(plan, member.memberId(),
                        mapper::fromValues, mapper::values,
                        com.zoutrankil.data.repository.L2IntradayBarFeaturesWritePort.CODEC,
                        port, () -> {
                            try { return l2IntradayBarFeaturesTarget.targetId(); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot resolve D087 target", failure); }
                        }, evidence.resolve(run).resolve(member.memberId()));
                adapters.put(member.memberId(), new L2IntradayBarFeaturesPreparedWriteAdapter(
                        l2IntradayBarFeaturesTarget, prepared));
            } else if(member.definition().datasetId().equals(L2EventResponseFeaturesDataset.DEFINITION.datasetId())) {
                var mapper = new com.zoutrankil.data.mapper.L2EventResponseFeaturesMapper();
                var port = new com.zoutrankil.data.repository.L2EventResponseFeaturesWritePort(
                        l2EventResponseFeaturesTarget.isolatedTableName(), jdbc, questdb);
                var prepared = new PreparedWriteAdapter<>(plan, member.memberId(),
                        mapper::fromValues, mapper::values,
                        com.zoutrankil.data.repository.L2EventResponseFeaturesWritePort.CODEC,
                        port, () -> {
                            try { return l2EventResponseFeaturesTarget.targetId(); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot resolve D088 target", failure); }
                        }, evidence.resolve(run).resolve(member.memberId()));
                adapters.put(member.memberId(), new L2EventResponseFeaturesPreparedWriteAdapter(
                        l2EventResponseFeaturesTarget, prepared));
            } else if(member.definition().datasetId().equals(L2T0TrainingLabelsDataset.DEFINITION.datasetId())) {
                var mapper = new com.zoutrankil.data.mapper.L2T0TrainingLabelsMapper();
                var port = new com.zoutrankil.data.repository.L2T0TrainingLabelsWritePort(
                        l2T0TrainingLabelsTarget.isolatedTableName(), jdbc, questdb);
                var prepared = new PreparedWriteAdapter<>(plan, member.memberId(),
                        mapper::fromValues, mapper::values,
                        com.zoutrankil.data.repository.L2T0TrainingLabelsWritePort.CODEC,
                        port, () -> {
                            try { return l2T0TrainingLabelsTarget.targetId(); }
                            catch (Exception failure) { throw new IllegalStateException("Cannot resolve D089 target", failure); }
                        }, evidence.resolve(run).resolve(member.memberId()));
                adapters.put(member.memberId(), new L2T0TrainingLabelsPreparedWriteAdapter(
                        l2T0TrainingLabelsTarget, prepared));
            } else if(member.definition().datasetId().equals(DailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.DailyMapper();
                var port=new com.zoutrankil.data.repository.DailyWritePort(
                        dailyTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.DailyWritePort.CODEC,port,()->{
                            try { return dailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(DailyBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.DailyBasicMapper();
                var port=new com.zoutrankil.data.repository.DailyBasicWritePort(
                        dailyBasicTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.DailyBasicWritePort.CODEC,port,()->{
                            try { return dailyBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockFactorDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.StockFactorMapper();
                var port=new com.zoutrankil.data.repository.StockFactorWritePort(
                        stockFactorTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.StockFactorWritePort.CODEC,port,()->{
                            try { return stockFactorTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_factor target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockLimitDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.StockLimitMapper();
                var port=new com.zoutrankil.data.repository.StockLimitWritePort(
                        stockLimitTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.StockLimitWritePort.CODEC,port,()->{
                            try { return stockLimitTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_limit target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfDailyMapper();
                var port=new com.zoutrankil.data.repository.EtfDailyWritePort(
                        etfDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfDailyWritePort.CODEC,port,()->{
                            try { return etfDailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfAdjDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfAdjMapper();
                var port=new com.zoutrankil.data.repository.EtfAdjWritePort(
                        etfAdjTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfAdjWritePort.CODEC,port,()->{
                            try { return etfAdjTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_adj target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfShareDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfShareMapper();
                var port=new com.zoutrankil.data.repository.EtfShareWritePort(
                        etfShareTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfShareWritePort.CODEC,port,()->{
                            try { return etfShareTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_share target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfFactorDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfFactorMapper();
                var port=new com.zoutrankil.data.repository.EtfFactorWritePort(
                        etfFactorTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfFactorWritePort.CODEC,port,()->{
                            try { return etfFactorTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_factor target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowDcDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.MoneyflowDcMapper();
                var port=new com.zoutrankil.data.repository.MoneyflowDcWritePort(
                        moneyflowDcTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.MoneyflowDcWritePort.CODEC,port,()->{
                            try { return moneyflowDcTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_dc target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowThsDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.MoneyflowThsMapper();
                var port=new com.zoutrankil.data.repository.MoneyflowThsWritePort(
                        moneyflowThsTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.MoneyflowThsWritePort.CODEC,port,()->{
                            try { return moneyflowThsTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_ths target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.MoneyflowMapper();
                var port=new com.zoutrankil.data.repository.MoneyflowWritePort(
                        moneyflowTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.MoneyflowWritePort.CODEC,port,()->{
                            try { return moneyflowTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexDailyMarketDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.IndexDailyMarketMapper();
                var port=new com.zoutrankil.data.repository.IndexDailyMarketWritePort(
                        indexDailyMarketTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.IndexDailyMarketWritePort.CODEC,port,()->{
                            try { return indexDailyMarketTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_daily_market target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexDailyBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.IndexDailyBasicMapper();
                var port=new com.zoutrankil.data.repository.IndexDailyBasicWritePort(
                        indexDailyBasicTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.IndexDailyBasicWritePort.CODEC,port,()->{
                            try { return indexDailyBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_daily_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexWeightDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.IndexWeightMapper();
                var port=new com.zoutrankil.data.repository.IndexWeightWritePort(
                        indexWeightTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.IndexWeightWritePort.CODEC,port,()->{
                            try { return indexWeightTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_weight target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfPortfolioDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfPortfolioMapper();
                var port=new com.zoutrankil.data.repository.EtfPortfolioWritePort(
                        etfPortfolioTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfPortfolioWritePort.CODEC,port,()->{
                            try { return etfPortfolioTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_portfolio target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.EtfBasicMapper();
                var port=new com.zoutrankil.data.repository.EtfBasicWritePort(
                        etfBasicTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.EtfBasicWritePort.CODEC,port,()->{
                            try { return etfBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexMonthlyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.IndexMonthlyMapper();
                var port=new com.zoutrankil.data.repository.IndexMonthlyWritePort(
                        indexMonthlyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.IndexMonthlyWritePort.CODEC,port,()->{
                            try { new IndexMonthlyPublication(jdbc,ledger).requireNoPendingPublication(); return indexMonthlyTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_monthly target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(DcIndexDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.DcIndexMapper();
                var port=new com.zoutrankil.data.repository.DcIndexWritePort(
                        dcIndexTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.DcIndexWritePort.CODEC,port,()->{
                            try { dcIndexTarget.requireNoPendingPublication(); return dcIndexTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve dc_index target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowHsgtDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.MoneyflowHsgtMapper();
                var port=new com.zoutrankil.data.repository.MoneyflowHsgtWritePort(
                        moneyflowHsgtTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.MoneyflowHsgtWritePort.CODEC,port,()->{
                            try { new MoneyflowHsgtPublication(jdbc,ledger).requireNoPendingPublication(); return moneyflowHsgtTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_hsgt target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockStDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.StockStDailyMapper();
                var port=new com.zoutrankil.data.repository.StockStDailyWritePort(
                        stockStDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.StockStDailyWritePort.CODEC,port,()->{
                            try { stockStDailyTarget.requireNoPendingPublication(); return stockStDailyTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_st_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockSuspendDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.mapper.StockSuspendMapper();
                var port=new com.zoutrankil.data.repository.StockSuspendWritePort(
                        stockSuspendTarget.tableName(),jdbc,questdb,member.targetId());
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.repository.StockSuspendWritePort.CODEC,port,()->{
                            try { stockSuspendTarget.requireNoPendingPublication(); return stockSuspendTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_suspend target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if (member.definition().datasetId().equals(EtfMarketOverviewDailyCacheDataset.DEFINITION.datasetId())) {
                if (etfMarketOverviewCacheTarget == null)
                    throw new IllegalArgumentException("Registered D101 delegated owner required");
                adapters.put(member.memberId(), new EtfMarketOverviewCachePreparedWriteAdapter(plan,
                        member.memberId(), etfMarketOverviewCacheTarget.delegatedPort(),
                        evidence.resolve(run).resolve(member.memberId())));
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
