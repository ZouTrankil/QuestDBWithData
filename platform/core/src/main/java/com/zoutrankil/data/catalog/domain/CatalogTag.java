package com.zoutrankil.data.catalog.domain;

/** 目录标签由代码统一维护；一个对象可以同时具有多个标签。 */
public enum CatalogTag {
    CN("A股及国内"),
    US("美股"),
    BTC("BTC"),
    SHARED("共用"),
    EQUITY("股票"),
    ETF("ETF"),
    INDEX("指数"),
    FUTURES("期货"),
    GOLD("黄金"),
    MACRO("宏观"),
    RATES("利率"),
    EVENT("事件"),
    FINANCIAL("财务"),
    FUND_FLOW("资金流"),
    QMT("QMT行情"),
    L2("Level2"),
    DAILY("日频"),
    MONTHLY("月频"),
    MINUTE("分钟"),
    REALTIME("实时"),
    BACKTEST("回测"),
    BREADTH("市场广度"),
    SENTIMENT("市场情绪"),
    FACTOR("因子"),
    MODEL("模型"),
    VALUATION("估值"),
    REGIME("市场状态"),
    CALENDAR("日历"),
    COLLECTED("采集"),
    DERIVED("衍生"),
    GENERATED("运行生成");

    private final String label;

    CatalogTag(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
