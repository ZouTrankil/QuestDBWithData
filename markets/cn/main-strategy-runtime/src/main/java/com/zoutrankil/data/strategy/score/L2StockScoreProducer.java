package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.data.strategy.score.ScoreMath.*;

/** 供已验收日频评分器使用的确定性特征排名流程，不使用标签。 */
public final class L2StockScoreProducer {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(L2StockScoreProducer.class);
    public static final List<String> POSITIVE_FACTORS = List.of("q2_accum_alpha", "orderbook_breadth_alpha", "auction_flow_alpha", "activity_alpha", "crowding_context_alpha", "liquidity_alpha");
    public static final Map<String, Double> RISK_FACTORS;
    static {
        var risk = new LinkedHashMap<String, Double>(); risk.put("distribution_risk", .08); risk.put("orderbook_fragility_risk", .06);
        risk.put("breakdown_risk", .06); risk.put("overheat_risk", .05); RISK_FACTORS = Collections.unmodifiableMap(risk);
    }
    public record StockMetadata(String name, String industry) {}
    private record Key(LocalDate date, String symbol) {}
    private static final Set<String> ENRICHED_RAW = Set.of("clean_amount", "q2_count", "q2_accumulation", "active_buy_ratio",
            "gmm_main_force_net_inflow", "obi_top5_std", "orderbook_replenish_speed", "depth_slope", "continuous_auction_ratio",
            "close_auction_net_inflow", "open_close_vol_ratio", "q4_count", "q4_distribution_ratio", "active_sell_ratio",
            "large_active_sell_amount", "depth_top5_mean", "ofi_persistence", "ofi_std", "mfi_escort", "mean_obi_top5",
            "daily_amount", "amount", "turnover_rate");

    public List<ScoreRow> produce(List<ScoreRow> rawL2, List<ScoreRow> stockDaily,
            Map<String, StockMetadata> metadata, List<ScoreRow> chipRows, boolean sentimentPresent,
            FrozenPromotedRecipe promoted) {
        return producePrepared(rawL2,dailyFeatures(stockDaily,sentimentPresent),metadata,chipRows,promoted);
    }
    private List<ScoreRow> producePrepared(List<ScoreRow> rawL2,List<MutableRow> daily,
            Map<String,StockMetadata> metadata,List<ScoreRow> chipRows,FrozenPromotedRecipe promoted) {
        var dailyByKey = index(daily);
        var chipByKey = index(mutable(chipRows)); var base = new ArrayList<MutableRow>();
        for (var raw : mutable(rawL2)) {
            if (!tradable(raw.symbol)) continue;
            var diag = dailyByKey.get(new Key(raw.date, raw.symbol));
            // 原评分器用左连接；未观察到行情的行保持空值，不补造价格或状态。
            boolean missingMarket = diag == null;
            if (diag == null) {
                diag=new MutableRow(new ScoreRow(raw.date,raw.symbol,Map.of()));
                dailyByKey.put(new Key(raw.date,raw.symbol),diag);
            }
            var row = new MutableRow(raw.freeze()); row.values.putAll(diag.values);
            // build_daily_diagnostics 不会向基础评分器暴露日频 turnover_rate。
            if (raw.values.containsKey("turnover_rate")) row.put("turnover_rate", raw.values.get("turnover_rate"));
            else row.values.remove("turnover_rate");
            // 正式历史输入按日期取名称和行业；旧的纯计算调用仍可提供固定元数据。
            var meta = metadata.get(raw.date+"|"+raw.symbol);
            if(meta==null)meta=metadata.get(raw.symbol);
            if (meta == null && missingMarket) meta=new StockMetadata("","");
            if (meta == null) throw new IllegalArgumentException("L2 stock metadata missing: " + raw.symbol);
            row.put("name", Objects.toString(meta.name(), "")); row.put("industry_l2", Objects.toString(meta.industry(), ""));
            if (raw.values.containsKey("main_funds_buy_amount")) row.put("buy_aggressiveness", raw.n("main_funds_buy_amount") / (diag.n("amount") == 0 ? Double.NaN : diag.n("amount")));
            row.put("daily_amount", diag.n("amount")); row.put("pct_chg", diag.n("ret") * 100);
            row.put("prev_pct_chg", diag.n("prev_ret") * 100); base.add(row);
        }
        if (base.isEmpty()) throw new IllegalArgumentException("No tradable L2 observations");
        meanRank(base, "__acc", "q2_count", "q2_accumulation", "q2_accumulation_ratio", "active_buy_ratio", "gmm_main_force_net_inflow", "buy_aggressiveness");
        meanRank(base, "__risk", "q3_count", "q4_count", "q4_distribution", "q4_distribution_ratio", "active_sell_ratio", "large_active_sell_amount");
        meanRank(base, "__liquidity", "daily_amount", "amount", "turnover_rate"); meanRank(base, "__pct", "pct_chg");
        for (var row : base) {
            row.put("prob_acc", clip(.20 + .65 * row.n("__acc") + .15 * row.n("__liquidity"), 0, 1));
            boolean hot = row.n("pct_chg") >= 6 && (row.n("prev_pct_chg") > 5 || row.f("5d_chg", 0) > .20);
            row.put("prob_risk", clip(.15 + .70 * row.n("__risk") + .15 * row.n("__pct") + (hot ? .30 : 0), 0, 1));
            row.put("exp_ret", fill(.60 * row.n("__acc") + .25 * row.n("__liquidity") - .35 * row.n("__risk"), 0));
            var reasons = new ArrayList<String>();
            if (row.s("name").toUpperCase(Locale.ROOT).contains("ST") || row.f("is_st", 0) == 1) reasons.add("ST");
            if (row.f("is_suspended", 0) == 1) reasons.add("Suspended");
            if (!Double.isNaN(row.n("up_limit")) && !Double.isNaN(row.n("raw_close")) && row.n("raw_close") >= row.n("up_limit") - .01) reasons.add("LimitUp");
            if (row.f("5d_chg", 0) >= .40) reasons.add("Surge");
            if (row.f("5d_turnover_mean", 0) < 1 || row.f("5d_volatility", 0) < 1.5) reasons.add("DeadWater");
            if (hot) reasons.add("OverHeated");
            row.put("filter_reason", reasons.isEmpty() ? "Clean" : String.join("|", reasons));
        }
        rank(base, "exp_ret", "exp_ret_rank", true, Double.NaN);
        for (var row : base) row.put("final_score", .30 * row.n("prob_acc") + .30 * (1 - row.n("prob_risk")) + .40 * row.n("exp_ret_rank"));
        base.sort(Comparator.comparing((MutableRow row) -> row.date).thenComparing((MutableRow row) -> row.n("final_score"), Comparator.reverseOrder()));
        var rawByKey = index(mutable(rawL2)); var scored = new ArrayList<MutableRow>();
        for (var source : base) {
            var row = new MutableRow(new ScoreRow(source.date, source.symbol, Map.of()));
            for (String name : List.of("name", "industry_l2", "prob_acc", "prob_risk", "exp_ret_rank", "final_score", "filter_reason")) row.put(name, source.values.get(name));
            var raw = rawByKey.get(new Key(row.date, row.symbol)); var diag = dailyByKey.get(new Key(row.date, row.symbol));
            for (String name : ENRICHED_RAW) if (raw.values.containsKey(name)) row.put(name, raw.values.get(name));
            double denominator = raw.n("clean_amount");
            for (String name : List.of("q2_accumulation", "gmm_main_force_net_inflow", "close_auction_net_inflow"))
                if (raw.values.containsKey(name)) row.put(name + "_to_clean", ratio(raw.n(name), denominator));
            for (String name : List.of("crowding_score", "component_vol60", "component_ret20", "component_turnover", "component_limit_touch20", "component_high_vol_flag", "component_high_turnover_flag",
                    "guard_ret_20d", "guard_ret_60d", "guard_ret_120d", "guard_pos_60d", "guard_limit_up_flag", "guard_limit_touch_20d", "guard_ret20_rank", "guard_turnover_rank")) row.put(name, diag.values.get(name));
            var chip = chipByKey.get(new Key(row.date, row.symbol));
            for (String name : List.of("l2_activity_score", "l2_chase_risk_score", "l2_breakdown_risk_score")) row.put(name, chip == null ? .5 : chip.f(name, .5));
            double ret20 = row.f("guard_ret_20d", 0), ret120 = row.f("guard_ret_120d", 0), position = row.f("guard_pos_60d", 0), touches = row.f("guard_limit_touch_20d", 0);
            row.put("guard_hard_no_buy", row.b("guard_limit_up_flag") || touches >= 1 && ret20 > .15 || position > .95 && ret20 > .25);
            row.put("guard_soft_overheat", ret120 > 1 && position > .90 || position > .90 && ret20 > .20);
            row.put("guard_risk_flag_count", (row.b("guard_limit_up_flag") ? 1 : 0) + (touches >= 1 ? 1 : 0) + (position > .90 ? 1 : 0) + (ret20 > .20 ? 1 : 0) + (ret120 > 1 ? 1 : 0));
            row.put("__clean_score", row.s("filter_reason").equals("Clean") ? row.n("final_score") : Double.NaN); scored.add(row);
        }
        rank(scored, "__clean_score", "base_score_rank", true, 0);
        meanRank(scored, "q2_accum_alpha", "q2_count", "q2_accumulation_to_clean", "active_buy_ratio", "gmm_main_force_net_inflow_to_clean");
        meanRank(scored, "orderbook_breadth_alpha", "obi_top5_std", "orderbook_replenish_speed", "depth_slope", "continuous_auction_ratio");
        meanRank(scored, "auction_flow_alpha", "close_auction_net_inflow_to_clean", "open_close_vol_ratio", "continuous_auction_ratio", "gmm_main_force_net_inflow_to_clean");
        rank(scored, "l2_activity_score", "activity_alpha", true, .5); rank(scored, "crowding_score", "crowding_context_alpha", true, .5);
        meanRank(scored, "liquidity_alpha", "daily_amount", "amount", "turnover_rate");
        meanRank(scored, "distribution_risk", "q4_count", "q4_distribution_ratio", "active_sell_ratio", "large_active_sell_amount");
        meanRank(scored, "orderbook_fragility_risk", "depth_top5_mean", "ofi_persistence", "ofi_std", "mfi_escort", "mean_obi_top5");
        rank(scored, "l2_breakdown_risk_score", "breakdown_risk", true, .5);
        meanRank(scored, "overheat_risk", "guard_ret_20d", "guard_pos_60d", "guard_limit_touch_20d", "guard_limit_up_flag");
        double[] fixed = {.25, .30, .15, .10, .10, .10};
        for (var row : scored) {
            double positive = 0, staticRisk = 0, dynamicRisk = 0, weighted = 0, sum = 0;
            var weights = FrozenPromotedRecipe.asOf(promoted.l2Weights(), row.date);
            for (int i = 0; i < POSITIVE_FACTORS.size(); i++) {
                String name = POSITIVE_FACTORS.get(i); positive += fixed[i] * row.n(name);
                double weight = fill(number(weights.get("w_" + name)), 0); weighted += weight * row.n(name); sum += weight; row.put("w_" + name, weight);
            }
            for (var factor : RISK_FACTORS.entrySet()) {
                double active = fill(number(weights.get("risk_active_" + factor.getKey())), 0);
                staticRisk += factor.getValue() * row.n(factor.getKey()); dynamicRisk += factor.getValue() * active * row.n(factor.getKey());
                row.put("risk_active_" + factor.getKey(), active);
            }
            double alpha = sum > 0 ? weighted / sum : row.n("base_score_rank");
            row.put("positive_alpha_static", positive); row.put("risk_penalty_static", staticRisk); row.put("regime_positive_alpha", alpha); row.put("dynamic_guard_penalty", dynamicRisk);
            double finalScore = .70 * row.n("base_score_rank") + .30 * alpha - dynamicRisk;
            row.put("feature_rank_v2_dynamic_score", row.s("filter_reason").equals("Clean") ? finalScore : 0);
            row.put("final_score", row.n("feature_rank_v2_dynamic_score"));
        }
        // 候选百分位先包含非 Clean 样本的零分，再将其 final_score 屏蔽为 NaN。
        rank(scored, "final_score", "__candidate_pct", true, Double.NaN);
        for (var row : scored) {
            boolean clean = row.s("filter_reason").equals("Clean"); row.put("clean", clean);
            row.put("l2_buy_candidate", clean && row.n("__candidate_pct") >= .70 && row.f("risk_penalty_static", 0) <= .16);
            if (!clean) row.put("final_score", Double.NaN);
        }
        for (var group : byDate(scored).values()) {
            var ranked = group.stream().filter(row -> !Double.isNaN(row.n("final_score")))
                    .sorted(Comparator.comparingDouble((MutableRow row) -> row.n("final_score")).reversed()).toList();
            group.forEach(row -> row.put("l2_rank", Double.NaN));
            for (int i = 0; i < ranked.size(); i++) ranked.get(i).put("l2_rank", i + 1);
        }
        return scored.stream().map(MutableRow::freeze).toList();
    }

    private static Map<Key, MutableRow> index(List<MutableRow> rows) {
        var result = new HashMap<Key, MutableRow>(); for (var row : rows) result.put(new Key(row.date, row.symbol), row); return result;
    }
    public static boolean tradable(String symbol) { return (symbol.endsWith(".SH") || symbol.endsWith(".SZ")) && Set.of('0', '3', '6').contains(symbol.charAt(0)); }
    private static List<MutableRow> dailyFeatures(List<ScoreRow> source, boolean sentimentPresent) {
        var rows=dailySeries(source);completeCrossSection(rows,sentimentPresent);return rows;
    }
    private static List<MutableRow> dailySeries(List<ScoreRow> source) {
        var rows = mutable(source);
        for (var row : rows) for (String column : List.of("price", "raw_close", "amount", "turnover_rate", "pb", "up_limit", "down_limit", "is_suspended", "is_st"))
            if (!row.values.containsKey(column)) throw new IllegalArgumentException("Canonical stock daily source lacks " + column);
        change(rows, "price", "__computed_ret", 1, true);
        for (var row : rows) row.put("ret", row.f("ret", row.n("__computed_ret")));
        change(rows, "price", "5d_chg", 5, true);
        for (var group : bySymbol(rows).values()) for (int i = 0; i < group.size(); i++) {
            var row = group.get(i); row.put("prev_ret", i == 0 ? Double.NaN : group.get(i - 1).n("ret"));
            row.put("__pct", row.n("ret") * 100);
            row.put("guard_limit_up_flag", !Double.isNaN(row.n("up_limit")) && !Double.isNaN(row.n("raw_close")) && Math.abs(row.n("raw_close") - row.n("up_limit")) <= 1e-6);
            row.put("component_turnover", row.n("turnover_rate"));
        }
        rolling(rows, "__pct", "5d_volatility", 5, 3, true); rolling(rows, "turnover_rate", "5d_turnover_mean", 5, 3, false);
        for (int window : new int[]{20, 60, 120}) change(rows, "price", "guard_ret_" + window + "d", window, true);
        extreme(rows, "price", "__high60", 60, 20, true); extreme(rows, "price", "__low60", 60, 20, false);
        rolling(rows, "guard_limit_up_flag", "__touch_mean20", 20, 1, false);
        for (var group : bySymbol(rows).values()) for (int i = 0; i < group.size(); i++) {
            var row = group.get(i); double denominator = row.n("__high60") - row.n("__low60");
            row.put("guard_pos_60d", denominator == 0 ? Double.NaN : (row.n("price") - row.n("__low60")) / denominator);
            row.put("guard_limit_touch_20d", row.n("__touch_mean20") * Math.min(20, i + 1));
        }
        rolling(rows, "ret", "component_vol60", 60, 30, true); rolling(rows, "guard_limit_up_flag", "component_limit_touch20", 20, 10, false);
        for (var row : rows) row.put("component_ret20", row.n("guard_ret_20d"));
        change(rows, "price", "__crowd_ret1", 1, true); rolling(rows, "__crowd_ret1", "__crowd_vol20", 20, 10, true);
        rolling(rows, "guard_limit_up_flag", "__crowd_touch", 20, 10, false);
        return rows;
    }
    private static void completeCrossSection(List<MutableRow> rows,boolean sentimentPresent) {
        rank(rows, "guard_ret_20d", "guard_ret20_rank", true, Double.NaN); rank(rows, "turnover_rate", "guard_turnover_rank", true, Double.NaN);
        rank(rows, "component_vol60", "__component_vol_rank", true, Double.NaN); rank(rows, "component_turnover", "__turnover_rank", true, Double.NaN);
        var crowdSources = List.of("pb", "turnover_rate", "__crowd_vol20", "guard_ret_20d", "__crowd_touch");
        for (int i = 0; i < crowdSources.size(); i++) rank(rows, crowdSources.get(i), "__crowd_rank" + i, true, Double.NaN);
        double[] crowdWeights = {.25, .25, .20, .20, .10};
        for (var row : rows) {
            row.put("component_high_vol_flag", row.n("__component_vol_rank") >= .80 ? 1.0 : 0.0);
            row.put("component_high_turnover_flag", row.n("__turnover_rank") >= .80 ? 1.0 : 0.0);
            double sum = 0, available = 0;
            for (int i = 0; i < crowdWeights.length; i++) if (!Double.isNaN(row.n("__crowd_rank" + i))) { sum += crowdWeights[i] * row.n("__crowd_rank" + i); available += crowdWeights[i]; }
            row.put("crowding_score", sentimentPresent && available > 0 ? sum / available : Double.NaN);
        }
    }

    private static final List<String> SERIES_DIAGNOSTICS=List.of("price","raw_close","amount","turnover_rate","pb",
            "up_limit","down_limit","is_suspended","is_st","ret","5d_chg","prev_ret","5d_volatility","5d_turnover_mean",
            "guard_ret_20d","guard_ret_60d","guard_ret_120d","guard_pos_60d","guard_limit_up_flag","guard_limit_touch_20d",
            "component_turnover","component_vol60","component_limit_touch20","component_ret20","__crowd_vol20","__crowd_touch");
    static final List<String> ETF_AGGREGATION=List.of("final_score","l2_buy_candidate","l2_rank","component_vol60",
            "component_ret20","component_high_vol_flag","component_high_turnover_flag","component_limit_touch20");

    /** Keep full per-symbol time series, then score one complete cross-section at a time. */
    public List<ScoreRow> produceForEtfAggregation(List<ScoreRow> rawL2,List<ScoreRow> stockDaily,
            Map<String,StockMetadata> metadata,List<ScoreRow> chipRows,boolean sentimentPresent,FrozenPromotedRecipe promoted) {
        if(rawL2.isEmpty())throw new IllegalArgumentException("No tradable L2 observations");
        var rawByDate=new TreeMap<LocalDate,List<ScoreRow>>();
        for(var row:rawL2)rawByDate.computeIfAbsent(row.tradeDate(),ignored->new ArrayList<>()).add(row);
        var bySymbol=new TreeMap<String,List<ScoreRow>>();
        for(var row:stockDaily)bySymbol.computeIfAbsent(row.symbol(),ignored->new ArrayList<>()).add(row);
        var diagnostics=new TreeMap<LocalDate,List<ScoreRow>>();
        // Series rolling state starts at the original warmup boundary for every symbol.
        // No window is truncated or reinitialized at a month or signal boundary.
        int symbolCount=0;
        for(var group:bySymbol.values()) {
            for(var row:dailySeries(group))if(rawByDate.containsKey(row.date)) {
                var values=new LinkedHashMap<String,Object>();for(String column:SERIES_DIAGNOSTICS)values.put(column,row.values.get(column));
                diagnostics.computeIfAbsent(row.date,ignored->new ArrayList<>()).add(new ScoreRow(row.date,row.symbol,values));
            }
            if(++symbolCount%500==0)LOG.info("Prepared full-history stock series: {}/{} symbols",symbolCount,bySymbol.size());
        }
        var chips=new TreeMap<LocalDate,List<ScoreRow>>();
        for(var row:chipRows)chips.computeIfAbsent(row.tradeDate(),ignored->new ArrayList<>()).add(row);
        var result=new ArrayList<ScoreRow>();
        int dateCount=0;
        for(var entry:rawByDate.entrySet()) {
            var observed=diagnostics.remove(entry.getKey());var day=mutable(observed==null?List.of():observed);
            completeCrossSection(day,sentimentPresent);
            for(var scored:producePrepared(entry.getValue(),day,metadata,chips.getOrDefault(entry.getKey(),List.of()),promoted)) {
                var values=new LinkedHashMap<String,Object>();for(String column:ETF_AGGREGATION)values.put(column,scored.values().get(column));
                result.add(new ScoreRow(scored.tradeDate(),scored.symbol(),values));
            }
            if(++dateCount%20==0||dateCount==rawByDate.size())LOG.info("Completed exact stock score cross-sections: {}/{} dates; rows={}",dateCount,rawByDate.size(),result.size());
        }
        return List.copyOf(result);
    }
}
