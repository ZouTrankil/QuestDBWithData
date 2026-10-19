package com.zoutrankil.data.group.port;

import com.zoutrankil.data.domain.stock.market.StockLimitKey;
import com.zoutrankil.data.domain.stock.market.StockLimit;
import com.zoutrankil.data.domain.stock.market.DailyBasicKey;
import com.zoutrankil.data.domain.stock.market.DailyBasic;
import com.zoutrankil.data.domain.stock.feature.StockFactorKey;
import com.zoutrankil.data.domain.stock.feature.StockFactor;
import com.zoutrankil.data.domain.stock.basic.StockStDailyKey;
import com.zoutrankil.data.domain.stock.basic.StockStDaily;
import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshotKey;
import com.zoutrankil.data.domain.stock.basic.StockBasicSnapshot;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendar;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfPortfolioKey;
import com.zoutrankil.data.domain.fund.EtfPortfolio;
import com.zoutrankil.data.domain.fund.EtfFactorKey;
import com.zoutrankil.data.domain.fund.EtfFactor;
import com.zoutrankil.data.domain.fund.EtfDailyKey;
import com.zoutrankil.data.domain.fund.EtfDaily;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.stock.port.*;
import com.zoutrankil.data.etf.port.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.flow.port.*;

/** Fresh physical sessions for the admitted shared write group. */
public interface WriteGroupWriters {
    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> stockBasic(String table);
    VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> exchangeCalendar(String table);
    DailyWriteSession daily(String table);
    VerifiedWriteSession<DailyBasic, DailyBasicKey> dailyBasic(String table);
    VerifiedWriteSession<StockFactor,StockFactorKey> stockFactor(String table);
    StockDateWriteSession<StockLimit, StockLimitKey> stockLimit(String table, String targetId);
    com.zoutrankil.data.etf.port.EtfWriteSession<EtfDaily, EtfDailyKey> etfDaily(String table, String targetId);
    com.zoutrankil.data.etf.port.EtfAdjWriteSession etfAdj(String table, String targetId);
    EtfWriteSession<EtfShare, EtfShareKey> etfShare(String table, String targetId);
    com.zoutrankil.data.etf.port.EtfWriteSession<EtfFactor, EtfFactorKey> etfFactor(String table, String targetId);
    MoneyflowDcWriteSession moneyflowDc(String table, String targetId);
    MoneyflowThsWriteSession moneyflowThs(String table, String targetId);
    MoneyflowWriteSession moneyflow(String table, String targetId);
    IndexDailyMarketWriteSession indexDailyMarket(String table, String targetId);
    IndexDailyBasicWriteSession indexDailyBasic(String table, String targetId);
    IndexWeightWriteSession indexWeight(String table, String targetId);
    EtfWriteSession<EtfPortfolio, EtfPortfolioKey> etfPortfolio(String table, String targetId);
    VerifiedWriteSession<EtfBasic, EtfBasicKey> etfBasic(String table, String targetId);
    IndexMonthlyWriteSession indexMonthly(String table, String targetId);
    DcIndexWriteSession dcIndex(String table, String targetId);
    MoneyflowHsgtWriteSession moneyflowHsgt(String table, String targetId);
    StockDateWriteSession<StockStDaily,StockStDailyKey> stockStDaily(String table, String targetId);
    com.zoutrankil.data.stock.port.StockSuspendWriteSession stockSuspend(String table, String targetId);
    IndexMonthlyTables indexMonthlyTables();
    MoneyflowHsgtStagingPort moneyflowHsgtTables();
}
