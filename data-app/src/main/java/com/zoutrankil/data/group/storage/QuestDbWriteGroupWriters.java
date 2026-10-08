package com.zoutrankil.data.group.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.etf.port.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.stock.storage.*;
import com.zoutrankil.data.calendar.storage.*;
import com.zoutrankil.data.etf.storage.*;
import com.zoutrankil.data.index.storage.*;
import com.zoutrankil.data.flow.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.questdb.client.QuestDB;

/** Physical construction only; publication decisions remain with the group owner. */
public final class QuestDbWriteGroupWriters implements WriteGroupWriters {
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    public QuestDbWriteGroupWriters(JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc=jdbc; this.questdb=questdb;
    }
    @Override public VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> stockBasic(String table) {
        return new StockBasicWritePort(table,jdbc,questdb);
    }
    @Override public VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> exchangeCalendar(String table) {
        return new ExchangeCalendarWritePort(table,jdbc,questdb);
    }
    @Override public DailyWriteSession daily(String table) {
        return new DailyWritePort(table,jdbc,questdb);
    }
    @Override public VerifiedWriteSession<DailyBasic, DailyBasicKey> dailyBasic(String table) {
        return new DailyBasicWritePort(table,jdbc,questdb);
    }
    @Override public VerifiedWriteSession<StockFactor,StockFactorKey> stockFactor(String table) {
        return new StockFactorWritePort(table,jdbc,questdb);
    }
    @Override public StockDateWriteSession<StockLimit, StockLimitKey> stockLimit(String table, String targetId) {
        return new StockLimitWritePort(table,targetId,jdbc,questdb);
    }
    @Override public com.zoutrankil.data.etf.port.EtfWriteSession<EtfDaily, EtfDailyKey> etfDaily(String table, String targetId) {
        return new EtfDailyWritePort(table,targetId,jdbc,questdb);
    }
    @Override public com.zoutrankil.data.etf.port.EtfAdjWriteSession etfAdj(String table, String targetId) {
        return new EtfAdjWritePort(table,targetId,jdbc,questdb);
    }
    @Override public EtfWriteSession<EtfShare, EtfShareKey> etfShare(String table, String targetId) {
        return new EtfShareWritePort(table,targetId,jdbc,questdb);
    }
    @Override public com.zoutrankil.data.etf.port.EtfWriteSession<EtfFactor, EtfFactorKey> etfFactor(String table, String targetId) {
        return new EtfFactorWritePort(table,targetId,jdbc,questdb);
    }
    @Override public MoneyflowDcWriteSession moneyflowDc(String table, String targetId) {
        return new MoneyflowDcWritePort(table,targetId,jdbc,questdb);
    }
    @Override public MoneyflowThsWriteSession moneyflowThs(String table, String targetId) {
        return new MoneyflowThsWritePort(table,targetId,jdbc,questdb);
    }
    @Override public MoneyflowWriteSession moneyflow(String table, String targetId) {
        return new MoneyflowWritePort(table,targetId,jdbc,questdb);
    }
    @Override public IndexDailyMarketWriteSession indexDailyMarket(String table, String targetId) {
        return new IndexDailyMarketWritePort(table,targetId,jdbc,questdb);
    }
    @Override public IndexDailyBasicWriteSession indexDailyBasic(String table, String targetId) {
        return new IndexDailyBasicWritePort(table,targetId,jdbc,questdb);
    }
    @Override public IndexWeightWriteSession indexWeight(String table, String targetId) {
        return new IndexWeightWritePort(table,targetId,jdbc,questdb);
    }
    @Override public EtfWriteSession<EtfPortfolio, EtfPortfolioKey> etfPortfolio(String table, String targetId) {
        return new EtfPortfolioWritePort(table,targetId,jdbc,questdb);
    }
    @Override public VerifiedWriteSession<EtfBasic, EtfBasicKey> etfBasic(String table, String targetId) {
        return new EtfBasicWritePort(table,targetId,jdbc,questdb);
    }
    @Override public IndexMonthlyWriteSession indexMonthly(String table, String targetId) {
        return new IndexMonthlyWritePort(table,targetId,jdbc,questdb);
    }
    @Override public DcIndexWriteSession dcIndex(String table, String targetId) {
        return new DcIndexWritePort(table,targetId,jdbc,questdb);
    }
    @Override public MoneyflowHsgtWriteSession moneyflowHsgt(String table, String targetId) {
        return new MoneyflowHsgtWritePort(table,targetId,jdbc,questdb);
    }
    @Override public StockDateWriteSession<StockStDaily,StockStDailyKey> stockStDaily(String table, String targetId) {
        return new StockStDailyWritePort(table,targetId,jdbc,questdb);
    }
    @Override public com.zoutrankil.data.stock.port.StockSuspendWriteSession stockSuspend(String table, String targetId) {
        return new StockSuspendWritePort(table,jdbc,questdb,targetId);
    }
    @Override public IndexMonthlyTables indexMonthlyTables() { return new QuestDbIndexMonthlyTables(jdbc); }
    @Override public MoneyflowHsgtStagingPort moneyflowHsgtTables() { return new QuestDbMoneyflowHsgtTables(jdbc); }
}
