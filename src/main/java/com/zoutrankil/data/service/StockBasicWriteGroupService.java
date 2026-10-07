package com.zoutrankil.data.service;

import com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService;
import com.zoutrankil.data.derived.application.EtfMarketOverviewCachePreparedWriteAdapter;
import com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService;
import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper;
import com.zoutrankil.data.derived.storage.EquityStyleMonthlyWritePort;
import com.zoutrankil.data.derived.storage.MacroCoreMonthlyWritePort;

import com.zoutrankil.data.flow.application.MoneyflowJobService;
import com.zoutrankil.data.flow.storage.MoneyflowWritePort;
import com.zoutrankil.data.flow.mapper.MoneyflowMapper;
import com.zoutrankil.data.flow.application.MoneyflowThsJobService;
import com.zoutrankil.data.flow.storage.MoneyflowThsWritePort;
import com.zoutrankil.data.flow.mapper.MoneyflowThsMapper;
import com.zoutrankil.data.flow.application.MoneyflowDcJobService;
import com.zoutrankil.data.flow.storage.MoneyflowDcWritePort;
import com.zoutrankil.data.flow.mapper.MoneyflowDcMapper;
import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import com.zoutrankil.data.flow.application.MoneyflowHsgtPublication;
import com.zoutrankil.data.flow.storage.MoneyflowHsgtWritePort;
import com.zoutrankil.data.flow.mapper.MoneyflowHsgtMapper;

import com.zoutrankil.data.index.application.DcIndexJobService;
import com.zoutrankil.data.index.application.IndexCatalogJobService;
import com.zoutrankil.data.index.application.IndexCatalogPreparedWriteAdapter;
import com.zoutrankil.data.index.application.IndexDailyBasicJobService;
import com.zoutrankil.data.index.application.IndexDailyMarketJobService;
import com.zoutrankil.data.index.application.IndexMembershipJobService;
import com.zoutrankil.data.index.application.IndexMembershipPreparedWriteAdapter;
import com.zoutrankil.data.index.application.IndexMonthlyJobService;
import com.zoutrankil.data.index.application.IndexMonthlyPublication;
import com.zoutrankil.data.index.application.IndexWeightJobService;
import com.zoutrankil.data.index.application.ThsIndexJobService;
import com.zoutrankil.data.index.application.ThsIndexPreparedWriteAdapter;
import com.zoutrankil.data.index.application.ThsMemberJobService;
import com.zoutrankil.data.index.application.ThsMemberPreparedWriteAdapter;
import com.zoutrankil.data.index.storage.DcIndexWritePort;
import com.zoutrankil.data.index.storage.IndexDailyBasicWritePort;
import com.zoutrankil.data.index.storage.IndexDailyMarketWritePort;
import com.zoutrankil.data.index.storage.IndexMonthlyWritePort;
import com.zoutrankil.data.index.storage.IndexWeightWritePort;
import com.zoutrankil.data.index.mapper.DcIndexMapper;
import com.zoutrankil.data.index.mapper.IndexDailyBasicMapper;
import com.zoutrankil.data.index.mapper.IndexDailyMarketMapper;
import com.zoutrankil.data.index.mapper.IndexMonthlyMapper;
import com.zoutrankil.data.index.mapper.IndexWeightMapper;

import com.zoutrankil.data.stock.application.DailyJobService;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.application.StockFactorJobService;
import com.zoutrankil.data.stock.application.StockLimitJobService;
import com.zoutrankil.data.stock.application.StockStDailyJobService;
import com.zoutrankil.data.stock.application.StockSuspendJobService;
import com.zoutrankil.data.stock.storage.DailyWritePort;
import com.zoutrankil.data.stock.storage.DailyBasicWritePort;
import com.zoutrankil.data.stock.storage.StockFactorWritePort;
import com.zoutrankil.data.stock.storage.StockLimitWritePort;
import com.zoutrankil.data.stock.storage.StockStDailyWritePort;
import com.zoutrankil.data.stock.storage.StockSuspendWritePort;
import com.zoutrankil.data.stock.mapper.DailyMapper;
import com.zoutrankil.data.stock.mapper.DailyBasicMapper;
import com.zoutrankil.data.stock.mapper.StockFactorMapper;
import com.zoutrankil.data.stock.mapper.StockLimitMapper;
import com.zoutrankil.data.stock.mapper.StockStDailyMapper;
import com.zoutrankil.data.stock.mapper.StockSuspendMapper;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.stock.application.StaticStockDetailWriteAdapter;

import com.zoutrankil.data.etf.application.EtfBasicJobService;
import com.zoutrankil.data.etf.storage.EtfBasicWritePort;
import com.zoutrankil.data.etf.mapper.EtfBasicMapper;
import com.zoutrankil.data.etf.application.EtfPortfolioJobService;
import com.zoutrankil.data.etf.storage.EtfPortfolioWritePort;
import com.zoutrankil.data.etf.mapper.EtfPortfolioMapper;
import com.zoutrankil.data.etf.application.EtfShareJobService;
import com.zoutrankil.data.etf.storage.EtfShareWritePort;
import com.zoutrankil.data.etf.mapper.EtfShareMapper;

import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.storage.EtfDailyWritePort;
import com.zoutrankil.data.etf.mapper.EtfDailyMapper;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.storage.EtfAdjWritePort;
import com.zoutrankil.data.etf.mapper.EtfAdjMapper;
import com.zoutrankil.data.etf.application.EtfFactorJobService;
import com.zoutrankil.data.etf.storage.EtfFactorWritePort;
import com.zoutrankil.data.etf.mapper.EtfFactorMapper;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;
import com.zoutrankil.data.calendar.storage.ExchangeCalendarWritePort;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
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
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private EquityStyleMonthlyJobService equityStyleMonthlyTarget;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private MacroCoreMonthlyJobService macroCoreMonthlyTarget;
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
                new IndexMonthlyPublication(new com.zoutrankil.data.index.storage.QuestDbIndexMonthlyTables(jdbc),ledger).requireNoPendingPublication();
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
                new MoneyflowHsgtPublication(new com.zoutrankil.data.flow.storage.QuestDbMoneyflowHsgtTables(jdbc),ledger).requireNoPendingPublication();
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
            } else if(member.datasetId().equals(MacroCoreMonthlyDataset.DEFINITION.datasetId()) && macroCoreMonthlyTarget!=null) {
                var mapper=new com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper();
                com.zoutrankil.data.derived.domain.MacroCoreMonthlyRows.requireBatch(
                        member.rows().stream().map(mapper::fromValues).toList());
                macroCoreMonthlyTarget.requireNoPendingPublication();
                targets.put(member.datasetId(),macroCoreMonthlyTarget.targetId());
            } else if(member.datasetId().equals(EquityStyleMonthlyDataset.DEFINITION.datasetId()) && equityStyleMonthlyTarget!=null) {
                var mapper=new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper();
                com.zoutrankil.data.derived.domain.EquityStyleMonthlyRows.requireBatch(
                        member.rows().stream().map(mapper::fromValues).toList());
                equityStyleMonthlyTarget.requireNoPendingPublication();
                targets.put(member.datasetId(),equityStyleMonthlyTarget.targetId());
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
                var mapper=new com.zoutrankil.data.stock.mapper.DailyMapper();
                var port=new com.zoutrankil.data.stock.storage.DailyWritePort(
                        dailyTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.DailyWritePort.CODEC,port,()->{
                            try { return dailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(DailyBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.stock.mapper.DailyBasicMapper();
                var port=new com.zoutrankil.data.stock.storage.DailyBasicWritePort(
                        dailyBasicTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.DailyBasicWritePort.CODEC,port,()->{
                            try { return dailyBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve daily_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockFactorDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.stock.mapper.StockFactorMapper();
                var port=new com.zoutrankil.data.stock.storage.StockFactorWritePort(
                        stockFactorTarget.tableName(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.StockFactorWritePort.CODEC,port,()->{
                            try { return stockFactorTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_factor target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockLimitDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.stock.mapper.StockLimitMapper();
                var port=new com.zoutrankil.data.stock.storage.StockLimitWritePort(
                        stockLimitTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.StockLimitWritePort.CODEC,port,()->{
                            try { return stockLimitTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_limit target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfDailyMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfDailyWritePort(
                        etfDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfDailyWritePort.CODEC,port,()->{
                            try { return etfDailyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfAdjDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfAdjMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfAdjWritePort(
                        etfAdjTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfAdjWritePort.CODEC,port,()->{
                            try { return etfAdjTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_adj target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfShareDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfShareMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfShareWritePort(
                        etfShareTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfShareWritePort.CODEC,port,()->{
                            try { return etfShareTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_share target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfFactorDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfFactorMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfFactorWritePort(
                        etfFactorTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfFactorWritePort.CODEC,port,()->{
                            try { return etfFactorTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_factor target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowDcDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.flow.mapper.MoneyflowDcMapper();
                var port=new com.zoutrankil.data.flow.storage.MoneyflowDcWritePort(
                        moneyflowDcTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.flow.storage.MoneyflowDcWritePort.CODEC,port,()->{
                            try { return moneyflowDcTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_dc target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowThsDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.flow.mapper.MoneyflowThsMapper();
                var port=new com.zoutrankil.data.flow.storage.MoneyflowThsWritePort(
                        moneyflowThsTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.flow.storage.MoneyflowThsWritePort.CODEC,port,()->{
                            try { return moneyflowThsTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_ths target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.flow.mapper.MoneyflowMapper();
                var port=new com.zoutrankil.data.flow.storage.MoneyflowWritePort(
                        moneyflowTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.flow.storage.MoneyflowWritePort.CODEC,port,()->{
                            try { return moneyflowTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexDailyMarketDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.index.mapper.IndexDailyMarketMapper();
                var port=new com.zoutrankil.data.index.storage.IndexDailyMarketWritePort(
                        indexDailyMarketTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.index.storage.IndexDailyMarketWritePort.CODEC,port,()->{
                            try { return indexDailyMarketTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_daily_market target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexDailyBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.index.mapper.IndexDailyBasicMapper();
                var port=new com.zoutrankil.data.index.storage.IndexDailyBasicWritePort(
                        indexDailyBasicTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.index.storage.IndexDailyBasicWritePort.CODEC,port,()->{
                            try { return indexDailyBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_daily_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexWeightDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.index.mapper.IndexWeightMapper();
                var port=new com.zoutrankil.data.index.storage.IndexWeightWritePort(
                        indexWeightTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.index.storage.IndexWeightWritePort.CODEC,port,()->{
                            try { return indexWeightTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_weight target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfPortfolioDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfPortfolioMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfPortfolioWritePort(
                        etfPortfolioTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfPortfolioWritePort.CODEC,port,()->{
                            try { return etfPortfolioTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_portfolio target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EtfBasicDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.etf.mapper.EtfBasicMapper();
                var port=new com.zoutrankil.data.etf.storage.EtfBasicWritePort(
                        etfBasicTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.etf.storage.EtfBasicWritePort.CODEC,port,()->{
                            try { return etfBasicTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve etf_basic target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(IndexMonthlyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.index.mapper.IndexMonthlyMapper();
                var port=new com.zoutrankil.data.index.storage.IndexMonthlyWritePort(
                        indexMonthlyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.index.storage.IndexMonthlyWritePort.CODEC,port,()->{
                            try { new IndexMonthlyPublication(new com.zoutrankil.data.index.storage.QuestDbIndexMonthlyTables(jdbc),ledger).requireNoPendingPublication(); return indexMonthlyTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve index_monthly target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(DcIndexDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.index.mapper.DcIndexMapper();
                var port=new com.zoutrankil.data.index.storage.DcIndexWritePort(
                        dcIndexTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.index.storage.DcIndexWritePort.CODEC,port,()->{
                            try { dcIndexTarget.requireNoPendingPublication(); return dcIndexTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve dc_index target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MoneyflowHsgtDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.flow.mapper.MoneyflowHsgtMapper();
                var port=new com.zoutrankil.data.flow.storage.MoneyflowHsgtWritePort(
                        moneyflowHsgtTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.flow.storage.MoneyflowHsgtWritePort.CODEC,port,()->{
                            try { new MoneyflowHsgtPublication(new com.zoutrankil.data.flow.storage.QuestDbMoneyflowHsgtTables(jdbc),ledger).requireNoPendingPublication(); return moneyflowHsgtTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve moneyflow_hsgt target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockStDailyDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.stock.mapper.StockStDailyMapper();
                var port=new com.zoutrankil.data.stock.storage.StockStDailyWritePort(
                        stockStDailyTarget.tableName(),member.targetId(),jdbc,questdb);
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.StockStDailyWritePort.CODEC,port,()->{
                            try { stockStDailyTarget.requireNoPendingPublication(); return stockStDailyTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_st_daily target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(StockSuspendDataset.DEFINITION.datasetId())) {
                var mapper=new com.zoutrankil.data.stock.mapper.StockSuspendMapper();
                var port=new com.zoutrankil.data.stock.storage.StockSuspendWritePort(
                        stockSuspendTarget.tableName(),jdbc,questdb,member.targetId());
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        com.zoutrankil.data.stock.storage.StockSuspendWritePort.CODEC,port,()->{
                            try { stockSuspendTarget.requireNoPendingPublication(); return stockSuspendTarget.physicalTargetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve stk_suspend target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if (member.definition().datasetId().equals(EtfMarketOverviewDailyCacheDataset.DEFINITION.datasetId())) {
                if (etfMarketOverviewCacheTarget == null)
                    throw new IllegalArgumentException("Registered D101 delegated owner required");
                adapters.put(member.memberId(), new EtfMarketOverviewCachePreparedWriteAdapter(plan,
                        member.memberId(), etfMarketOverviewCacheTarget.delegatedPort(),
                        evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(MacroCoreMonthlyDataset.DEFINITION.datasetId())) {
                if(macroCoreMonthlyTarget==null)throw new IllegalArgumentException("Registered D104 write owner required");
                var mapper=new com.zoutrankil.data.derived.mapper.MacroCoreMonthlyMapper();
                var port=macroCoreMonthlyTarget.writePort();
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        port.codec(),port,
                        () -> {
                            try { return macroCoreMonthlyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve D104 target",failure); }
                        },evidence.resolve(run).resolve(member.memberId())));
            } else if(member.definition().datasetId().equals(EquityStyleMonthlyDataset.DEFINITION.datasetId())) {
                if(equityStyleMonthlyTarget==null)throw new IllegalArgumentException("Registered D103 write owner required");
                var mapper=new com.zoutrankil.data.derived.mapper.EquityStyleMonthlyMapper();
                var port=equityStyleMonthlyTarget.writePort();
                adapters.put(member.memberId(),new PreparedWriteAdapter<>(plan,member.memberId(),
                        mapper::fromValues,mapper::values,
                        port.codec(),port,
                        () -> {
                            try { return equityStyleMonthlyTarget.targetId(); }
                            catch(Exception failure) { throw new IllegalStateException("Cannot resolve D103 target",failure); }
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
