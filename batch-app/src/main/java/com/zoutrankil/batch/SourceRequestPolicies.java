package com.zoutrankil.batch;

/** Reusable request policies. Dataset selection is declared only by SourceStrategies. */
final class SourceRequestPolicies {
    private SourceRequestPolicies() {}
    static final SourceRequestPolicy STANDARD=new StandardSourceRequestPolicy();
    static final SourceRequestPolicy MAIN_BUSINESS=new MainBusinessSourceRequestPolicy();
    static final SourceRequestPolicy DIVIDEND=new DividendSourceRequestPolicy();
    static final SourceRequestPolicy AUDIT=new AuditSourceRequestPolicy();
    static final SourceRequestPolicy FUTURES_SINGLE=new SingleFuturesSourceRequestPolicy();
    static final SourceRequestPolicy FUTURES_HOLDING=new FuturesHoldingSourceRequestPolicy();
    static final SourceRequestPolicy FUTURES_BASIC=new FuturesBasicSourceRequestPolicy();
    static final SourceRequestPolicy ETF_BASIC=new EtfBasicSourceRequestPolicy();
    static final SourceRequestPolicy ETF_SHARE=new EtfShareSourceRequestPolicy();
    static final SourceRequestPolicy THS_INDEX=new ThsIndexSourceRequestPolicy();
    static final SourceRequestPolicy DISCLOSURE=new DisclosureSourceRequestPolicy();
    static final SourceRequestPolicy RATES=new UnpagedDateRangeSourceRequestPolicy();
    static final SourceRequestPolicy US_TBR=new UsTreasurySourceRequestPolicy();
    static final SourceRequestPolicy MONTHLY=new MonthlySourceRequestPolicy();
    static final SourceRequestPolicy QUARTERLY=new QuarterlySourceRequestPolicy();
    static final SourceRequestPolicy CALENDAR=new CalendarSourceRequestPolicy();
    static final SourceRequestPolicy CHIP=new ChipSourceRequestPolicy();
    static final SourceRequestPolicy INDEX_MARKET=new IndexMarketSourceRequestPolicy();
    static final SourceRequestPolicy INDEX_BASIC=new IndexSourceRequestPolicy();
    static final SourceRequestPolicy BOND_YIELD=new UnpagedDateRangeSourceRequestPolicy();
    static final SourceRequestPolicy ST_HISTORY=new StHistorySourceRequestPolicy();
    static final SourceRequestPolicy SUSPENSION=new SuspensionSourceRequestPolicy();
    static final SourceRequestPolicy MONEYFLOW_HSGT=new EndDateSourceRequestPolicy();
}
