package com.zoutrankil.batch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static com.zoutrankil.batch.StandardSourceCoveragePolicy.Code;
import static com.zoutrankil.batch.StandardSourceCoveragePolicy.Coverage;
import static com.zoutrankil.batch.StandardSourceCoveragePolicy.Period;
import static com.zoutrankil.batch.StandardSourceStoragePolicy.Technical;
import static com.zoutrankil.batch.StandardSourceStoragePolicy.Key;

/** Every supported source explicitly selects its request, row, coverage and storage behavior. */
final class SourceStrategies {
    private SourceStrategies() {}

    record Registration(String dataset, SourceStrategy strategy) {
        Registration { Objects.requireNonNull(dataset); Objects.requireNonNull(strategy); }
    }

    private static final SourceRowPolicy ENTITY_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.STANDARD,SourceRowScope.ENTITY);
    private static final SourceRowPolicy AGGREGATE_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.QUOTED_DECIMALS,SourceRowScope.AGGREGATE);
    private static final SourceRowPolicy MONEYFLOW_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.MONEYFLOW,SourceRowScope.ENTITY);
    private static final SourceRowPolicy MARGIN_ROWS = new NormalizingSourceRowPolicy(SourceRowPreparation.UNCHANGED,SourceValuePolicy.STANDARD,
            SourceRowScope.ENTITY,NormalizingSourceRowPolicy.Footer.MARGIN,NormalizingSourceRowPolicy.Observation.FROZEN_DATE);
    private static final SourceRowPolicy PORTFOLIO_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.QUOTED_DECIMALS,SourceRowScope.ENTITY);
    private static final SourceRowPolicy SUSPENSION_ROWS = rows(SourceRowPreparation.SUSPENSION,SourceValuePolicy.STANDARD,SourceRowScope.SUSPENSION);
    private static final SourceRowPolicy BOND_ROWS = rows(SourceRowPreparation.BOND_YIELD,SourceValuePolicy.QUOTED_DECIMALS,SourceRowScope.AGGREGATE);
    private static final SourceRowPolicy INDEX_ROWS = rows(SourceRowPreparation.INDEX_MARKET,SourceValuePolicy.STANDARD,SourceRowScope.ENTITY);
    private static final SourceRowPolicy CALENDAR_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.STANDARD,SourceRowScope.EXCHANGE);
    private static final SourceRowPolicy MAIN_BUSINESS_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.MAIN_BUSINESS,SourceRowScope.MAIN_BUSINESS);
    private static final SourceRowPolicy AUDIT_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.AUDIT,SourceRowScope.ENTITY);
    private static final SourceRowPolicy DIVIDEND_ROWS = rows(SourceRowPreparation.DIVIDEND,SourceValuePolicy.STANDARD,SourceRowScope.ENTITY);
    private static final SourceRowPolicy SHARE_FLOAT_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.SHARE_FLOAT,SourceRowScope.ENTITY);
    private static final SourceRowPolicy MONTH_ROWS = new NormalizingSourceRowPolicy(SourceRowPreparation.UNCHANGED,SourceValuePolicy.MONTH,
            SourceRowScope.AGGREGATE,NormalizingSourceRowPolicy.Footer.NONE,NormalizingSourceRowPolicy.Observation.MONTH);
    private static final SourceRowPolicy QUARTER_ROWS = new NormalizingSourceRowPolicy(SourceRowPreparation.QUARTER,SourceValuePolicy.QUOTED_DECIMALS,
            SourceRowScope.AGGREGATE,NormalizingSourceRowPolicy.Footer.NONE,NormalizingSourceRowPolicy.Observation.QUARTER);
    private static final SourceRowPolicy FUTURES_MAPPING_ROWS = rows(SourceRowPreparation.FUTURES_MAPPING,SourceValuePolicy.STANDARD,SourceRowScope.ENTITY);
    private static final SourceRowPolicy FUTURES_HOLDING_ROWS = rows(SourceRowPreparation.FUTURES_HOLDING,SourceValuePolicy.STANDARD,SourceRowScope.FUTURES_HOLDING);
    private static final SourceRowPolicy FUTURES_BASIC_ROWS = rows(SourceRowPreparation.FUTURES_BASIC,SourceValuePolicy.STANDARD,SourceRowScope.FUTURES_BASIC);
    private static final SourceRowPolicy ETF_BASIC_ROWS = rows(SourceRowPreparation.ETF_BASIC,SourceValuePolicy.STANDARD,SourceRowScope.ETF_BASIC);
    private static final SourceRowPolicy DISCLOSURE_ROWS = rows(SourceRowPreparation.UNCHANGED,SourceValuePolicy.DISCLOSURE,SourceRowScope.DISCLOSURE);
    private static final SourceRowPolicy ETF_SHARE_ROWS = rows(SourceRowPreparation.ETF_SHARE,SourceValuePolicy.QUOTED_DECIMALS,SourceRowScope.AGGREGATE);

    private static final SourceCoveragePolicy STOCK_COVERAGE = coverage(false,Code.STOCK,"ts_code",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy PORTFOLIO_COVERAGE = coverage(false,Code.ETF_PORTFOLIO,"ts_code",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy INDEX_COVERAGE = coverage(false,Code.INDEX,"ts_code",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy CALENDAR_COVERAGE = coverage(false,Code.EXCHANGE,"exchange",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy CHIP_COVERAGE = coverage(false,Code.STOCK,"ts_code",Coverage.REQUIRED_ENTITY,Period.NONE);
    private static final SourceCoveragePolicy FUTURES_COVERAGE = coverage(false,Code.FUTURES_CONTRACT,"ts_code",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy FUTURES_MAPPING_COVERAGE = coverage(false,Code.FUTURES_CONTINUOUS,"ts_code",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy FUTURES_HOLDING_COVERAGE = coverage(false,Code.FUTURES_PRODUCT,"symbol",Coverage.ENTITY,Period.NONE);
    private static final SourceCoveragePolicy FUTURES_BASIC_COVERAGE = coverage(false,Code.FUTURES_BASIC,"exchange",Coverage.SNAPSHOT,Period.NONE);
    private static final SourceCoveragePolicy ETF_BASIC_COVERAGE = coverage(false,Code.ETF_MARKET,"market",Coverage.SNAPSHOT,Period.NONE);
    private static final SourceCoveragePolicy SINGLE_AGGREGATE = coverage(true,Code.STOCK,"ts_code",Coverage.SINGLE_AGGREGATE,Period.NONE);
    private static final SourceCoveragePolicy BOND_COVERAGE = coverage(true,Code.STOCK,"ts_code",Coverage.BOND_CURVES,Period.NONE);
    private static final SourceCoveragePolicy RATE_COVERAGE = coverage(true,Code.STOCK,"ts_code",Coverage.RATE,Period.NONE);
    private static final SourceCoveragePolicy MONTH_COVERAGE = coverage(true,Code.STOCK,"ts_code",Coverage.MONTH,Period.MONTH);
    private static final SourceCoveragePolicy QUARTER_COVERAGE = coverage(true,Code.STOCK,"ts_code",Coverage.BOUNDED_AGGREGATE,Period.QUARTER);
    private static final SourceCoveragePolicy DISCLOSURE_COVERAGE = coverage(true,Code.STOCK,"ts_code",Coverage.BOUNDED_AGGREGATE,Period.NONE);
    private static final SourceCoveragePolicy NONEMPTY_AGGREGATE = coverage(true,Code.STOCK,"ts_code",Coverage.NONEMPTY_AGGREGATE,Period.NONE);

    private static final SourceStoragePolicy BUSINESS_STORAGE = new StandardSourceStoragePolicy(Technical.NONE,Key.BUSINESS,1000);
    private static final SourceStoragePolicy UPDATED_STORAGE = new StandardSourceStoragePolicy(Technical.UPDATE_TIME,Key.BUSINESS,1000);
    private static final SourceStoragePolicy PORTFOLIO_STORAGE = new StandardSourceStoragePolicy(Technical.UPDATE_TIME,Key.BUSINESS,50);
    private static final SourceStoragePolicy FUTURES_BASIC_STORAGE = new StandardSourceStoragePolicy(Technical.EPOCH_AND_UPDATE_TIME,Key.FUTURES_EPOCH,1000);
    private static final SourceStoragePolicy ETF_BASIC_STORAGE = new StandardSourceStoragePolicy(Technical.EPOCH_AND_UPDATE_TIME,Key.ETF_EPOCH,1000);
    private static final SourceStoragePolicy INDEX_CATALOG_STORAGE = new StandardSourceStoragePolicy(Technical.UPDATE_TIME,Key.STOCK_CODE,1000);

    private static final Map<String,SourceStrategy> REGISTRY = index(List.of(
            entry("daily",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("daily_basic",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("etf_daily",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("stk_limit",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("etf_adj",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("moneyflow",SourceRequestPolicies.STANDARD,MONEYFLOW_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("etf_factor",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("margin_detail",SourceRequestPolicies.STANDARD,MARGIN_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("moneyflow_hsgt",SourceRequestPolicies.MONEYFLOW_HSGT,AGGREGATE_ROWS,SINGLE_AGGREGATE,BUSINESS_STORAGE),
            entry("stk_suspend",SourceRequestPolicies.SUSPENSION,SUSPENSION_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("etf_portfolio",SourceRequestPolicies.STANDARD,PORTFOLIO_ROWS,PORTFOLIO_COVERAGE,PORTFOLIO_STORAGE),
            entry("stk_factor",SourceRequestPolicies.STANDARD,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("stk_st_daily",SourceRequestPolicies.ST_HISTORY,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("cn_bond_yield_curve",SourceRequestPolicies.BOND_YIELD,BOND_ROWS,BOND_COVERAGE,BUSINESS_STORAGE),
            entry("cyq_perf",SourceRequestPolicies.CHIP,ENTITY_ROWS,CHIP_COVERAGE,BUSINESS_STORAGE),
            entry("index_daily_market",SourceRequestPolicies.INDEX_MARKET,INDEX_ROWS,INDEX_COVERAGE,UPDATED_STORAGE),
            entry("index_daily_basic",SourceRequestPolicies.INDEX_BASIC,ENTITY_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("exchange_calendar",SourceRequestPolicies.CALENDAR,CALENDAR_ROWS,CALENDAR_COVERAGE,BUSINESS_STORAGE),
            entry("fina_mainbz",SourceRequestPolicies.MAIN_BUSINESS,MAIN_BUSINESS_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("fina_audit",SourceRequestPolicies.AUDIT,AUDIT_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("dividend",SourceRequestPolicies.DIVIDEND,DIVIDEND_ROWS,STOCK_COVERAGE,UPDATED_STORAGE),
            entry("share_float",SourceRequestPolicies.STANDARD,SHARE_FLOAT_ROWS,STOCK_COVERAGE,BUSINESS_STORAGE),
            entry("shibor",SourceRequestPolicies.RATES,AGGREGATE_ROWS,RATE_COVERAGE,BUSINESS_STORAGE),
            entry("shibor_lpr",SourceRequestPolicies.RATES,AGGREGATE_ROWS,RATE_COVERAGE,BUSINESS_STORAGE),
            entry("hibor",SourceRequestPolicies.RATES,AGGREGATE_ROWS,RATE_COVERAGE,BUSINESS_STORAGE),
            entry("cn_cpi",SourceRequestPolicies.MONTHLY,MONTH_ROWS,MONTH_COVERAGE,BUSINESS_STORAGE),
            entry("cn_ppi",SourceRequestPolicies.MONTHLY,MONTH_ROWS,MONTH_COVERAGE,BUSINESS_STORAGE),
            entry("cn_pmi",SourceRequestPolicies.MONTHLY,MONTH_ROWS,MONTH_COVERAGE,BUSINESS_STORAGE),
            entry("cn_m",SourceRequestPolicies.MONTHLY,MONTH_ROWS,MONTH_COVERAGE,BUSINESS_STORAGE),
            entry("cn_gdp",SourceRequestPolicies.QUARTERLY,QUARTER_ROWS,QUARTER_COVERAGE,BUSINESS_STORAGE),
            entry("fut_daily",SourceRequestPolicies.FUTURES_SINGLE,ENTITY_ROWS,FUTURES_COVERAGE,UPDATED_STORAGE),
            entry("fut_settle",SourceRequestPolicies.FUTURES_SINGLE,ENTITY_ROWS,FUTURES_COVERAGE,UPDATED_STORAGE),
            entry("fut_mapping",SourceRequestPolicies.FUTURES_SINGLE,FUTURES_MAPPING_ROWS,FUTURES_MAPPING_COVERAGE,UPDATED_STORAGE),
            entry("ft_limit",SourceRequestPolicies.FUTURES_SINGLE,ENTITY_ROWS,FUTURES_COVERAGE,UPDATED_STORAGE),
            entry("fut_holding",SourceRequestPolicies.FUTURES_HOLDING,FUTURES_HOLDING_ROWS,FUTURES_HOLDING_COVERAGE,UPDATED_STORAGE),
            entry("fut_basic",SourceRequestPolicies.FUTURES_BASIC,FUTURES_BASIC_ROWS,FUTURES_BASIC_COVERAGE,FUTURES_BASIC_STORAGE),
            entry("etf_basic",SourceRequestPolicies.ETF_BASIC,ETF_BASIC_ROWS,ETF_BASIC_COVERAGE,ETF_BASIC_STORAGE),
            entry("disclosure_date",SourceRequestPolicies.DISCLOSURE,DISCLOSURE_ROWS,DISCLOSURE_COVERAGE,UPDATED_STORAGE),
            entry("ths_index",SourceRequestPolicies.THS_INDEX,AGGREGATE_ROWS,NONEMPTY_AGGREGATE,INDEX_CATALOG_STORAGE),
            entry("etf_share",SourceRequestPolicies.ETF_SHARE,ETF_SHARE_ROWS,NONEMPTY_AGGREGATE,UPDATED_STORAGE),
            entry("us_tbr",SourceRequestPolicies.US_TBR,AGGREGATE_ROWS,RATE_COVERAGE,BUSINESS_STORAGE)));

    static {
        if (!REGISTRY.keySet().equals(SourceContract.SUPPORTED))
            throw new IllegalStateException("Source strategies must cover every supported dataset exactly once");
    }

    static SourceStrategy require(String dataset) {
        var strategy = REGISTRY.get(dataset);
        if (strategy == null) throw new IllegalArgumentException("Unregistered source: "+dataset);
        return strategy;
    }
    static Set<String> datasets() { return REGISTRY.keySet(); }
    static Map<String,SourceStrategy> index(List<Registration> registrations) {
        var result = new LinkedHashMap<String,SourceStrategy>();
        for (var registration : Objects.requireNonNull(registrations)) {
            Objects.requireNonNull(registration);
            if (!SourceContract.SUPPORTED.contains(registration.dataset()))
                throw new IllegalArgumentException("Unregistered source: "+registration.dataset());
            if (result.putIfAbsent(registration.dataset(),registration.strategy()) != null)
                throw new IllegalArgumentException("Duplicate source strategy: "+registration.dataset());
        }
        return Collections.unmodifiableMap(result);
    }
    private static Registration entry(String dataset, SourceRequestPolicy request, SourceRowPolicy rows,
                                      SourceCoveragePolicy coverage, SourceStoragePolicy storage) {
        return new Registration(dataset,new SourceStrategy(request,rows,coverage,storage));
    }
    private static SourceRowPolicy rows(SourceRowPreparation preparation, SourceValuePolicy values, SourceRowScope scope) {
        return new NormalizingSourceRowPolicy(preparation,values,scope,
                NormalizingSourceRowPolicy.Footer.NONE,NormalizingSourceRowPolicy.Observation.FROZEN_DATE);
    }
    private static SourceCoveragePolicy coverage(boolean aggregate, Code code, String entity, Coverage coverage, Period period) {
        return new StandardSourceCoveragePolicy(aggregate,code,entity,coverage,period);
    }
}
