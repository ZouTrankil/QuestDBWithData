package com.zoutrankil.data.cli;

import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.application.EtfFactorJobService;
import com.zoutrankil.data.etf.application.EtfPortfolioJobService;
import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import com.zoutrankil.data.flow.application.MoneyflowJobService;
import com.zoutrankil.data.margin.application.MarginDetailJobService;
import com.zoutrankil.data.service.CnBondYieldCurveJobService;
import com.zoutrankil.data.service.FormalSourceReadinessInspection;
import com.zoutrankil.data.service.FormalSourceReadinessInspection.Source;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import com.zoutrankil.data.stock.application.DailyJobService;
import com.zoutrankil.data.stock.application.StockFactorJobService;
import com.zoutrankil.data.stock.application.StockLimitJobService;
import com.zoutrankil.data.stock.application.StockStDailyJobService;
import com.zoutrankil.data.stock.application.StockSuspendJobService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnNotWebApplication
public final class FormalSourceReadinessCommands implements CliCommandFamily {
    private final FormalSourceReadinessInspection inspection;

    public FormalSourceReadinessCommands(
            DailyJobService daily,
            DailyBasicJobService basic,
            StockLimitJobService limit,
            EtfDailyJobService etfDaily,
            EtfFactorJobService etfFactor,
            EtfAdjJobService etfAdj,
            EtfPortfolioJobService etfPortfolio,
            StockStDailyJobService st,
            StockFactorJobService stockFactor,
            MoneyflowJobService moneyflow,
            MarginDetailJobService margin,
            CnBondYieldCurveJobService bond,
            ExchangeCalendarJobService calendar,
            MoneyflowHsgtJobService hsgt,
            StockSuspendJobService suspend,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        inspection =
                new FormalSourceReadinessInspection(
                        Path.of(ledger),
                        List.of(
                                new Source(
                                        "daily",
                                        "data.daily",
                                        daily::tableName,
                                        daily::targetId,
                                        daily::revalidate),
                                new Source(
                                        "daily_basic",
                                        "data.daily_basic",
                                        basic::tableName,
                                        basic::targetId,
                                        basic::revalidate),
                                new Source(
                                        "stk_limit",
                                        "data.stk_limit",
                                        limit::tableName,
                                        limit::targetId,
                                        limit::revalidate),
                                new Source(
                                        "etf_daily",
                                        "data.etf_daily",
                                        etfDaily::tableName,
                                        etfDaily::targetId,
                                        etfDaily::revalidate),
                                new Source(
                                        "etf_factor",
                                        "data.etf_factor",
                                        etfFactor::tableName,
                                        etfFactor::targetId,
                                        etfFactor::revalidate),
                                new Source(
                                        "etf_adj",
                                        "data.etf_adj",
                                        etfAdj::tableName,
                                        etfAdj::targetId,
                                        etfAdj::revalidate),
                                new Source(
                                        "etf_portfolio",
                                        "data.etf_portfolio",
                                        etfPortfolio::tableName,
                                        etfPortfolio::targetId,
                                        etfPortfolio::revalidate),
                                new Source(
                                        "stk_st_daily",
                                        "data.stk_st_daily",
                                        st::tableName,
                                        st::targetId,
                                        st::revalidate),
                                new Source(
                                        "stk_factor",
                                        "data.stk_factor",
                                        stockFactor::tableName,
                                        stockFactor::targetId,
                                        stockFactor::revalidate),
                                new Source(
                                        "moneyflow",
                                        "data.moneyflow",
                                        moneyflow::tableName,
                                        moneyflow::targetId,
                                        moneyflow::revalidate),
                                new Source(
                                        "margin_detail",
                                        "data.margin_detail",
                                        margin::tableName,
                                        margin::targetId,
                                        margin::revalidate),
                                new Source(
                                        "cn_bond_yield_curve",
                                        "data.cn_bond_yield_curve",
                                        bond::tableName,
                                        bond::targetId,
                                        bond::revalidate),
                                new Source(
                                        "exchange_calendar",
                                        "data.exchange_calendar",
                                        calendar::tableName,
                                        calendar::targetId,
                                        calendar::revalidate),
                                new Source(
                                        "moneyflow_hsgt",
                                        "data.moneyflow_hsgt",
                                        hsgt::tableName,
                                        hsgt::targetId,
                                        hsgt::revalidate),
                                new Source(
                                        "stk_suspend",
                                        "data.stk_suspend",
                                        suspend::tableName,
                                        suspend::targetId,
                                        suspend::revalidate)));
    }

    @Override
    public Set<String> commands() {
        return Set.of("inspect-formal-source-readiness");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().contains("--date")
                || !Set.of("--date", "--revalidate").containsAll(options.keySet())
                || options.containsKey("--revalidate")
                        && !Set.of("true", "false").contains(options.get("--revalidate")))
            throw new IllegalArgumentException("Expected --date DATE [--revalidate true|false]");
        CliOutput.printJson(
                inspection.inspect(
                        LocalDate.parse(options.get("--date")),
                        Boolean.parseBoolean(options.getOrDefault("--revalidate", "false"))),
                CliOutput.Profile.JOB_DEFINITION,
                true);
    }
}
