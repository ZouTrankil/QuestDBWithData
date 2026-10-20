package com.zoutrankil.data.group.port;

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
import com.zoutrankil.data.flow.port.MoneyflowHsgtStagingPort;
import com.zoutrankil.data.flow.port.MoneyflowHsgtWriteSession;
import com.zoutrankil.data.flow.port.MoneyflowThsWriteSession;
import com.zoutrankil.data.flow.port.MoneyflowWriteSession;
import com.zoutrankil.data.index.port.DcIndexWriteSession;
import com.zoutrankil.data.index.port.IndexDailyBasicWriteSession;
import com.zoutrankil.data.index.port.IndexDailyMarketWriteSession;
import com.zoutrankil.data.index.port.IndexMonthlyTables;
import com.zoutrankil.data.index.port.IndexMonthlyWriteSession;
import com.zoutrankil.data.index.port.IndexWeightWriteSession;
import com.zoutrankil.data.stock.port.DailyWriteSession;
import com.zoutrankil.data.stock.port.StockDateWriteSession;
import com.zoutrankil.data.stock.port.StockSuspendWriteSession;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

/** Fresh physical sessions for the admitted shared write group. */
public interface WriteGroupWriters {
    VerifiedWriteSession<StockBasicSnapshot, StockBasicSnapshotKey> stockBasic(String table);

    VerifiedWriteSession<ExchangeCalendar, ExchangeCalendar.Key> exchangeCalendar(String table);

    DailyWriteSession daily(String table);

    VerifiedWriteSession<DailyBasic, DailyBasicKey> dailyBasic(String table);

    VerifiedWriteSession<StockFactor, StockFactorKey> stockFactor(String table);

    StockDateWriteSession<StockLimit, StockLimitKey> stockLimit(String table, String targetId);

    EtfWriteSession<EtfDaily, EtfDailyKey> etfDaily(String table, String targetId);

    EtfAdjWriteSession etfAdj(String table, String targetId);

    /** 旧实现只支持 v1；v2 必须由存储端口显式声明，不能降级发送。 */
    default EtfAdjWriteSession etfAdj(String table, String targetId, int schemaVersion) {
        if (schemaVersion != 1)
            throw new IllegalArgumentException("ETF adjustment writer version is not supported");
        return etfAdj(table, targetId);
    }

    EtfWriteSession<EtfShare, EtfShareKey> etfShare(String table, String targetId);

    EtfWriteSession<EtfFactor, EtfFactorKey> etfFactor(String table, String targetId);

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

    StockDateWriteSession<StockStDaily, StockStDailyKey> stockStDaily(
            String table, String targetId);

    StockSuspendWriteSession stockSuspend(String table, String targetId);

    IndexMonthlyTables indexMonthlyTables();

    MoneyflowHsgtStagingPort moneyflowHsgtTables();
}
