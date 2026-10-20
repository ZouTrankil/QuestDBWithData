package com.zoutrankil.data.group.storage;

import com.zoutrankil.data.calendar.storage.ExchangeCalendarWritePort;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.domain.fund.EtfFactor;
import com.zoutrankil.data.domain.fund.EtfFactorKey;
import com.zoutrankil.data.domain.fund.EtfPortfolio;
import com.zoutrankil.data.domain.fund.EtfPortfolioKey;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshot;
import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshotKey;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.domain.stock.feature.StockFactor;
import com.zoutrankil.data.domain.stock.feature.StockFactorKey;
import com.zoutrankil.data.domain.stock.market.DailyBasic;
import com.zoutrankil.data.domain.stock.market.DailyBasicKey;
import com.zoutrankil.data.domain.stock.market.StockLimit;
import com.zoutrankil.data.domain.stock.market.StockLimitKey;
import com.zoutrankil.data.etf.port.EtfAdjWriteSession;
import com.zoutrankil.data.etf.port.EtfWriteSession;
import com.zoutrankil.data.etf.storage.EtfAdjWritePort;
import com.zoutrankil.data.etf.storage.EtfBasicWritePort;
import com.zoutrankil.data.etf.storage.EtfDailyWritePort;
import com.zoutrankil.data.etf.storage.EtfFactorWritePort;
import com.zoutrankil.data.etf.storage.EtfPortfolioWritePort;
import com.zoutrankil.data.etf.storage.EtfShareWritePort;
import com.zoutrankil.data.flow.port.MoneyflowHsgtStagingPort;
import com.zoutrankil.data.flow.port.MoneyflowHsgtWriteSession;
import com.zoutrankil.data.flow.port.MoneyflowThsWriteSession;
import com.zoutrankil.data.flow.port.MoneyflowWriteSession;
import com.zoutrankil.data.flow.storage.MoneyflowHsgtWritePort;
import com.zoutrankil.data.flow.storage.MoneyflowThsWritePort;
import com.zoutrankil.data.flow.storage.MoneyflowWritePort;
import com.zoutrankil.data.flow.storage.QuestDbMoneyflowHsgtTables;
import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.index.port.DcIndexWriteSession;
import com.zoutrankil.data.index.port.IndexDailyBasicWriteSession;
import com.zoutrankil.data.index.port.IndexDailyMarketWriteSession;
import com.zoutrankil.data.index.port.IndexMonthlyTables;
import com.zoutrankil.data.index.port.IndexMonthlyWriteSession;
import com.zoutrankil.data.index.port.IndexWeightWriteSession;
import com.zoutrankil.data.index.storage.DcIndexWritePort;
import com.zoutrankil.data.index.storage.IndexDailyBasicWritePort;
import com.zoutrankil.data.index.storage.IndexDailyMarketWritePort;
import com.zoutrankil.data.index.storage.IndexMonthlyWritePort;
import com.zoutrankil.data.index.storage.IndexWeightWritePort;
import com.zoutrankil.data.index.storage.QuestDbIndexMonthlyTables;
import com.zoutrankil.data.stock.port.DailyWriteSession;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.data.stock.storage.DailyBasicWritePort;
import com.zoutrankil.data.stock.storage.DailyWritePort;
import com.zoutrankil.data.stock.storage.StockBasicWritePort;
import com.zoutrankil.data.stock.storage.StockFactorWritePort;
import com.zoutrankil.data.stock.storage.StockLimitWritePort;
import com.zoutrankil.data.stock.storage.StockStDailyWritePort;
import com.zoutrankil.data.stock.storage.StockSuspendWritePort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

/** Physical construction only; publication decisions remain with the group owner. */
public final class QuestDbWriteGroupWriters implements WriteGroupWriters {
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbWriteGroupWriters(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = jdbc;
        this.questdb = questdb;
    }

    @Override
    public VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> stockBasic(
            String table) {
        return new StockBasicWritePort(table, jdbc, questdb);
    }

    @Override
    public VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> exchangeCalendar(
            String table) {
        return new ExchangeCalendarWritePort(table, jdbc, questdb);
    }

    @Override
    public DailyWriteSession daily(String table) {
        return new DailyWritePort(table, jdbc, questdb);
    }

    @Override
    public VerifiedWriteSession<DailyBasic, DailyBasicKey> dailyBasic(String table) {
        return new DailyBasicWritePort(table, jdbc, questdb);
    }

    @Override
    public VerifiedWriteSession<StockFactor, StockFactorKey> stockFactor(String table) {
        return new StockFactorWritePort(table, jdbc, questdb);
    }

    @Override
    public StockDateWriteSession<StockLimit, StockLimitKey> stockLimit(
            String table, String targetId) {
        return new StockLimitWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public EtfWriteSession<EtfDaily, EtfDailyKey> etfDaily(String table, String targetId) {
        return new EtfDailyWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public EtfWriteSession<EtfDaily, EtfDailyKey> etfDaily(
            String table, String targetId, int version) {
        return new EtfDailyWritePort(
                table, targetId, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public EtfAdjWriteSession etfAdj(String table, String targetId) {
        return new EtfAdjWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public EtfAdjWriteSession etfAdj(String table, String targetId, int schemaVersion) {
        return new EtfAdjWritePort(
                table, targetId, jdbc, questdb, CnTradeDateLayout.fromVersion(schemaVersion));
    }

    @Override
    public EtfWriteSession<EtfShare, EtfShareKey> etfShare(String table, String targetId) {
        return new EtfShareWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public EtfWriteSession<EtfShare, EtfShareKey> etfShare(
            String table, String targetId, int version) {
        return new EtfShareWritePort(
                table, targetId, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public EtfWriteSession<EtfFactor, EtfFactorKey> etfFactor(String table, String targetId) {
        return new EtfFactorWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public MoneyflowThsWriteSession moneyflowThs(String table, String targetId) {
        return new MoneyflowThsWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public MoneyflowWriteSession moneyflow(String table, String targetId) {
        return new MoneyflowWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public IndexDailyMarketWriteSession indexDailyMarket(String table, String targetId) {
        return new IndexDailyMarketWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public IndexDailyBasicWriteSession indexDailyBasic(String table, String targetId) {
        return new IndexDailyBasicWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public IndexWeightWriteSession indexWeight(String table, String targetId) {
        return new IndexWeightWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public EtfWriteSession<EtfPortfolio, EtfPortfolioKey> etfPortfolio(
            String table, String targetId) {
        return new EtfPortfolioWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public VerifiedWriteSession<EtfBasic, EtfBasicKey> etfBasic(String table, String targetId) {
        return new EtfBasicWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public IndexMonthlyWriteSession indexMonthly(String table, String targetId) {
        return new IndexMonthlyWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public DcIndexWriteSession dcIndex(String table, String targetId) {
        return new DcIndexWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public MoneyflowHsgtWriteSession moneyflowHsgt(String table, String targetId) {
        return new MoneyflowHsgtWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public StockDateWriteSession<StockStDaily, StockStDailyKey> stockStDaily(
            String table, String targetId) {
        return new StockStDailyWritePort(table, targetId, jdbc, questdb);
    }

    @Override
    public StockDateWriteSession<StockStDaily, StockStDailyKey> stockStDaily(
            String table, String targetId, int version) {
        return new StockStDailyWritePort(
                table, targetId, jdbc, questdb, CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public StockSuspendWriteSession stockSuspend(String table, String targetId) {
        return new StockSuspendWritePort(table, jdbc, questdb, targetId);
    }

    @Override
    public StockSuspendWriteSession stockSuspend(String table, String targetId, int version) {
        return new StockSuspendWritePort(
                table, jdbc, questdb, targetId, CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public IndexMonthlyTables indexMonthlyTables() {
        return new QuestDbIndexMonthlyTables(jdbc);
    }

    @Override
    public MoneyflowHsgtStagingPort moneyflowHsgtTables() {
        return new QuestDbMoneyflowHsgtTables(jdbc);
    }
}
