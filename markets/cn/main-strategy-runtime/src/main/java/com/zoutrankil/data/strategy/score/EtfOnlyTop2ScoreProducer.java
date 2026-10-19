package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.data.strategy.score.ScoreMath.*;

/** 用纯 Java 生成已验收方案的日频评分；不训练模型、不请求供应商，也不发布产物。 */
public final class EtfOnlyTop2ScoreProducer {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(EtfOnlyTop2ScoreProducer.class);
    public static final Map<String, String> FACTOR_COLUMNS;
    private static final Set<String> NEGATIVE_FACTORS = Set.of("top10_limit_touch", "top3_concentration", "ret_std60", "chip_breakdown");
    static {
        var columns = new LinkedHashMap<String, String>();
        columns.put("component_l2", "component_l2_score_smooth"); columns.put("buy_breadth", "component_buy_breadth_smooth");
        columns.put("trend", "trend_score"); columns.put("liquidity", "liquidity_score"); columns.put("crowding", "component_crowding_score");
        columns.put("top10_high_turnover", "top10_high_turnover_weight"); columns.put("top10_limit_touch", "top10_limit_touch_weight");
        columns.put("top3_concentration", "top3_weight_concentration"); columns.put("pos60", "pos_60"); columns.put("ret5", "ret_5");
        columns.put("rsi6", "rsi_bfq_6"); columns.put("ret_std60", "ret_std_60"); columns.put("chip_activity", "component_accumulation_score");
        columns.put("chip_chase", "component_chase_risk_score"); columns.put("chip_breakdown", "component_downside_risk_score");
        FACTOR_COLUMNS = Collections.unmodifiableMap(columns);
    }
    public record EtfReference(String symbol, String name, String category, String broadFamily, double annualFee, boolean lof, boolean activeLof) {
        public EtfReference {
            if (symbol == null || symbol.isBlank() || category == null || category.isBlank() || !Double.isFinite(annualFee))
                throw new IllegalArgumentException("Prepared ETF reference required");
        }
    }
    public record Holding(String etf, String stock, LocalDate announced, LocalDate periodEnd, double marketValueRatio, double marketValue) {
        public Holding { Objects.requireNonNull(etf); Objects.requireNonNull(stock); Objects.requireNonNull(announced); Objects.requireNonNull(periodEnd); }
    }
    public record Settings(int minComponentHits, double minAmount, int liquidityWindow, int liquidityMinPeriods, double minBuyBreadth, double minTop100Weight) {
        public Settings { if (minComponentHits < 1 || !Double.isFinite(minAmount) || minAmount < 0 || liquidityWindow < 1 || liquidityMinPeriods < 1 || liquidityMinPeriods > liquidityWindow
                    || !Double.isFinite(minBuyBreadth) || !Double.isFinite(minTop100Weight)) throw new IllegalArgumentException("Invalid daily scoring settings"); }
        public static Settings canonical() { return new Settings(10, 1000, 3, 1, .25, .40); }
    }
    /** 仅接收标准来源的标量字段；stockDaily 价格是原 Python 适配器的 close，不额外生成复权价格。 */
    public record Inputs(List<ScoreRow> stockDaily, List<ScoreRow> l2Raw, Map<String, L2StockScoreProducer.StockMetadata> stockMetadata,
            List<ScoreRow> etfFactors, List<EtfReference> references, List<Holding> holdings, List<ScoreRow> chipRows,
            List<ScoreRow> marketSentiment, List<LocalDate> signalDates, Map<String, String> sourceVersions,
            LocalDate maxCompleteDate, boolean sourceComplete) {
        public Inputs {
            stockDaily = List.copyOf(stockDaily); l2Raw = List.copyOf(l2Raw); stockMetadata = Map.copyOf(stockMetadata);
            etfFactors = List.copyOf(etfFactors); references = List.copyOf(references); holdings = List.copyOf(holdings);
            chipRows = List.copyOf(chipRows); marketSentiment = List.copyOf(marketSentiment); signalDates = List.copyOf(signalDates); sourceVersions = Map.copyOf(sourceVersions);
            Objects.requireNonNull(maxCompleteDate);
            if (!sourceComplete || signalDates.isEmpty() || new HashSet<>(signalDates).size() != signalDates.size()
                    || !signalDates.equals(signalDates.stream().sorted().toList()) || maxCompleteDate.isBefore(signalDates.getLast()))
                throw new IllegalArgumentException("Complete source proof and increasing unique signal date grid required");
            for (String source : List.of("stock_daily", "l2", "stock_metadata", "etf_factor", "etf_reference", "etf_portfolio", "market_sentiment", "chip"))
                if (Objects.toString(sourceVersions.get(source), "").isBlank()) throw new IllegalArgumentException("Source version proof required: " + source);
            var sentimentDates = new HashSet<LocalDate>();
            for (var row : marketSentiment) {
                if (!sentimentDates.add(row.tradeDate())) throw new IllegalArgumentException("Duplicate market sentiment date");
                for (String field : List.of("sentiment_score", "sentiment_quintile"))
                    if (!row.values().containsKey(field) || row.values().get(field) == null || row.values().get(field).toString().isBlank())
                        throw new IllegalArgumentException("Market sentiment lacks " + field);
                if (!Double.isFinite(row.number("sentiment_score"))) throw new IllegalArgumentException("Market sentiment score is missing/nonfinite");
            }
            if (!sentimentDates.containsAll(signalDates)) throw new IllegalArgumentException("Market sentiment history does not cover signal date grid");
        }
    }
    public record Output(List<ScoreRow> panel, List<ScoreRow> coverage,
            List<FrozenPromotedRecipe.DatedValues> factorWeights, List<FrozenPromotedRecipe.DatedValues> factorStates,
            Map<String, String> provenance, LocalDate maxCompleteDate) {
        public Output { panel = List.copyOf(panel); coverage = List.copyOf(coverage); factorWeights = List.copyOf(factorWeights); factorStates = List.copyOf(factorStates); provenance = Map.copyOf(provenance); }
    }

    public Output produce(Inputs inputs, FrozenPromotedRecipe promoted) { return produce(inputs, promoted, Settings.canonical()); }
    public static List<EtfReference> prepareEtfReference(List<Map<String, Object>> raw, boolean includeActiveLof) {
        return EtfSourcePreparation.references(raw, includeActiveLof);
    }
    public static List<ScoreRow> prepareEtfFactor(List<ScoreRow> factor, List<ScoreRow> daily, Set<String> codes, LocalDate requestedEnd) {
        return EtfSourcePreparation.factor(factor, daily, codes, requestedEnd);
    }
    public static List<ScoreRow> prepareMarketSentiment(List<ScoreRow> raw, LocalDate start, LocalDate end) {
        return EtfSourcePreparation.sentiment(raw, start, end);
    }
    /**
     * 先计算股票 L2 分数，再按各信号日可见的持仓公告聚合到 ETF，结合技术面、市场状态与流动性评分。
     * 最后应用固定方案的权重、状态及诊断规则；输出评分面板和覆盖证据，不执行交易。
     */
    public Output produce(Inputs inputs, FrozenPromotedRecipe promoted, Settings settings) {
        if (inputs.signalDates().getLast().isBefore(promoted.recipe().effectiveFrom())) throw new IllegalArgumentException("Promoted recipe is not effective on requested target");
        boolean usableL2Weights = inputs.signalDates().stream().map(date -> FrozenPromotedRecipe.asOf(promoted.l2Weights(), date))
                .anyMatch(weights -> L2StockScoreProducer.POSITIVE_FACTORS.stream().anyMatch(name -> !Double.isNaN(number(weights.get("w_" + name)))));
        if (!usableL2Weights) throw new IllegalArgumentException("Promoted L2 factor weights have no usable aligned weight columns");
        var signalSet = new HashSet<>(inputs.signalDates());
        var l2 = inputs.l2Raw().stream().filter(row -> signalSet.contains(row.tradeDate())).toList();
        if (!new HashSet<>(l2.stream().map(ScoreRow::tradeDate).toList()).containsAll(signalSet)) throw new IllegalArgumentException("L2 source date grid incomplete");
        var stock = new L2StockScoreProducer().produceForEtfAggregation(l2, inputs.stockDaily(), inputs.stockMetadata(), inputs.chipRows(), true, promoted);
        var stocksByDate = byDate(mutable(stock)); var factors = technical(inputs.etfFactors(), inputs.signalDates().getFirst(), settings); var etfsByDate = byDate(factors);
        LOG.info("Prepared complete ETF technical history: rows={}; signal dates={}",factors.size(),inputs.signalDates().size());
        var reference = new LinkedHashMap<String, EtfReference>();
        for (var row : inputs.references()) if (reference.put(row.symbol(), row) != null) throw new IllegalArgumentException("Duplicate prepared ETF reference");
        var holdings = inputs.holdings().stream().filter(row -> reference.containsKey(row.etf()) && L2StockScoreProducer.tradable(row.stock())
                && !row.announced().isAfter(inputs.signalDates().getLast())).toList();
        boolean ratioPresent = holdings.stream().anyMatch(row -> !Double.isNaN(row.marketValueRatio()));
        var scored = new ArrayList<MutableRow>(); var coverage = new ArrayList<ScoreRow>();
        int completedDates=0;
        for (LocalDate date : inputs.signalDates()) {
            var allEtfs = etfsByDate.getOrDefault(date, List.of()).stream().filter(row -> reference.containsKey(row.symbol)).toList();
            var stockDay = stocksByDate.getOrDefault(date, List.of());
            var baskets = baskets(holdings, date, ratioPresent); var aggregate = aggregate(baskets, stockDay); var day = new ArrayList<MutableRow>();
            if (allEtfs.isEmpty() || stockDay.isEmpty() || baskets.isEmpty()) throw new IllegalArgumentException("ETF score inputs do not cover " + date);
            for (var source : allEtfs) {
                var row = new MutableRow(source.freeze()); var meta = reference.get(row.symbol); var basket = aggregate.get(row.symbol);
                if (basket != null) row.values.putAll(basket);
                for (String name : List.of("component_l2_score", "component_buy_breadth", "top30_l2_weight", "top100_l2_weight"))
                    if (row.n("hit_n") < settings.minComponentHits() || Double.isNaN(row.n("hit_n"))) row.put(name, Double.NaN);
                row.put("name", meta.name()); row.put("etf_category", meta.category()); row.put("broad_family", meta.broadFamily());
                row.put("annual_fee", meta.annualFee()); row.put("etf_is_lof", meta.lof()); row.put("etf_is_active_lof", meta.activeLof());
                row.put("liquidity_amount", row.n("amount_mean_" + settings.liquidityWindow())); day.add(row);
            }
            marketScores(day); metadataScores(day); rankRegime(day);
            day.forEach(row -> row.put("liquidity_ok", row.f("liquidity_amount", 0) >= settings.minAmount()));
            var filtered = day.stream().filter(row -> row.f("liquidity_amount", 0) >= settings.minAmount() && !Double.isNaN(row.n("etf_score"))).toList();
            allocations(filtered); scored.addAll(filtered);
            double hit = aggregate.values().stream().mapToDouble(row -> number(row.get("component_hit_rate"))).filter(v -> !Double.isNaN(v)).average().orElse(0);
            var values = new LinkedHashMap<String, Object>(); values.put("etf_universe_n", allEtfs.size()); values.put("etf_with_portfolio_n", aggregate.size());
            values.put("etf_scored_n", filtered.size()); values.put("component_hit_rate", hit); values.put("liquidity_filtered_n", allEtfs.size() - filtered.size());
            values.put("liquidity_window", settings.liquidityWindow()); values.put("liquidity_min_periods", settings.liquidityMinPeriods()); values.put("liquidity_min_amount", settings.minAmount());
            coverage.add(new ScoreRow(date, "ETF_UNIVERSE", values));
            if (filtered.isEmpty()) throw new IllegalArgumentException("No eligible ETF score rows on " + date);
            if(++completedDates%20==0||completedDates==inputs.signalDates().size())LOG.info("Completed full ETF score aggregation: {}/{} dates; rows={}",completedDates,inputs.signalDates().size(),scored.size());
        }
        smooth(scored, settings); comboDiagnostics(scored); chip(scored, inputs.chipRows()); overlay(scored, promoted);
        LOG.info("Completed full ETF score postprocessing: rows={}",scored.size());
        scored.sort(Comparator.comparing((MutableRow row) -> row.symbol).thenComparing(row -> row.date));
        var appliedWeights = new ArrayList<FrozenPromotedRecipe.DatedValues>(); var appliedStates = new ArrayList<FrozenPromotedRecipe.DatedValues>();
        for (LocalDate date : inputs.signalDates()) {
            appliedWeights.add(new FrozenPromotedRecipe.DatedValues(date, FrozenPromotedRecipe.asOf(promoted.factorWeights(), date)));
            appliedStates.add(new FrozenPromotedRecipe.DatedValues(date, FrozenPromotedRecipe.asOf(promoted.factorStates(), date)));
        }
        var provenance = new TreeMap<>(inputs.sourceVersions()); promoted.artifactSha256().forEach((k, v) -> provenance.put("promoted_" + k, v));
        provenance.put("score_recipe_id", promoted.recipe().scoreRecipeId()); provenance.put("code_revision", promoted.recipe().codeRevision());
        provenance.put("training_cutoff", promoted.recipe().trainingCutoff().toString()); provenance.put("scorer", "java.etf_only_top2.promoted_daily.v1");
        return new Output(scored.stream().map(MutableRow::freeze).toList(), coverage, appliedWeights, appliedStates, provenance, inputs.signalDates().getLast());
    }

    private static List<MutableRow> technical(List<ScoreRow> source, LocalDate start, Settings settings) {
        var rows = new ArrayList<MutableRow>();
        for (var group : bySymbol(mutable(source)).values()) {
            var history = group.stream().filter(row -> row.date.isBefore(start)).toList();
            rows.addAll(history.subList(Math.max(0, history.size() - Math.max(60, settings.liquidityWindow() - 1)), history.size()));
            rows.addAll(group.stream().filter(row -> !row.date.isBefore(start)).toList());
        }
        for (var row : rows) for (String name : List.of("open", "close", "pct_change", "amount", "rsi_bfq_6"))
            if (!row.values.containsKey(name)) throw new IllegalArgumentException("Canonical ETF factor source lacks " + name);
        for (int period : new int[]{3, 5, 20}) change(rows, "close", "ret_" + period, period, true);
        rolling(rows, "pct_change", "ret_std_20", 20, 10, true); rolling(rows, "pct_change", "ret_std_60", 60, 30, true);
        extreme(rows, "close", "roll_min_60", 60, 20, false); extreme(rows, "close", "roll_max_60", 60, 20, true);
        for (var row : rows) { double denominator = row.n("roll_max_60") - row.n("roll_min_60"); row.put("pos_60", denominator == 0 ? Double.NaN : (row.n("close") - row.n("roll_min_60")) / denominator); }
        rolling(rows, "amount", "amount_mean_20", 20, 5, false); rolling(rows, "amount", "amount_mean_3", 3, 1, false);
        if (settings.liquidityWindow() != 3 && settings.liquidityWindow() != 20) rolling(rows, "amount", "amount_mean_" + settings.liquidityWindow(), settings.liquidityWindow(), settings.liquidityMinPeriods(), false);
        return rows;
    }
    private record Member(String stock, double weight) {}
    /** 按信号日已公告的最近持仓构造成分篮子；整列选择权重口径，不能逐行混用比例与市值。 */
    private static Map<String, List<Member>> baskets(List<Holding> source, LocalDate date, boolean ratioPresent) {
        var latest = new HashMap<String, LocalDate>();
        // Python 先处理整列权重并删除缺失权重，再按最近公告选择记录。
        for (var row : source) if (!row.announced().isAfter(date) && !Double.isNaN(ratioPresent ? row.marketValueRatio() : row.marketValue()))
            latest.merge(row.etf(), row.announced(), (a, b) -> a.isAfter(b) ? a : b);
        var selected = new TreeMap<String, List<Holding>>();
        for (var row : source) if (row.announced().equals(latest.get(row.etf())) && !Double.isNaN(ratioPresent ? row.marketValueRatio() : row.marketValue()))
            selected.computeIfAbsent(row.etf(), ignored -> new ArrayList<>()).add(row);
        var result = new TreeMap<String, List<Member>>();
        selected.forEach((etf, rows) -> {
            rows.sort(Comparator.comparing(Holding::stock)); double total = rows.stream().mapToDouble(row -> ratioPresent ? row.marketValueRatio() : row.marketValue()).sum();
            if (total > 0) result.put(etf, rows.stream().map(row -> new Member(row.stock(), (ratioPresent ? row.marketValueRatio() : row.marketValue()) / total)).toList());
        }); return result;
    }
    private static Map<String, Map<String, Object>> aggregate(Map<String, List<Member>> baskets, List<MutableRow> stockDay) {
        var stocks = new HashMap<String, MutableRow>(); stockDay.forEach(row -> stocks.put(row.symbol, row)); var result = new TreeMap<String, Map<String, Object>>();
        baskets.forEach((etf, members) -> {
            int hits = 0; double maximum = Double.NEGATIVE_INFINITY;
            var l2 = new GroupSum(); var buy = new GroupSum(); var top30 = new GroupSum(); var top100 = new GroupSum();
            var volWeight = new GroupSum(); var vol = new GroupSum(); var ret20 = new GroupSum();
            var values = new LinkedHashMap<String, Object>(); var sorted = new ArrayList<>(members); sorted.sort(Comparator.comparingDouble(Member::weight).reversed());
            var topVol = new GroupSum(); var topHighVol = new GroupSum(); var topTurnover = new GroupSum(); var topTouch = new GroupSum(); var top3 = new GroupSum();
            for (var member : members) {
                var stock = stocks.get(member.stock()); double score = stock == null ? Double.NaN : stock.n("final_score"), weight = member.weight();
                if (!Double.isNaN(score)) { hits++; l2.add(weight * score); }
                buy.add(stock != null && stock.b("l2_buy_candidate") ? weight : 0);
                if (stock != null && stock.n("l2_rank") <= 30) top30.add(weight);
                if (stock != null && stock.n("l2_rank") <= 100) top100.add(weight);
                maximum = Math.max(maximum, fill(weight * score, 0));
                if (stock != null && !Double.isNaN(stock.n("component_vol60"))) { volWeight.add(weight); vol.add(weight * stock.n("component_vol60")); }
                ret20.add(stock == null ? 0 : weight * stock.f("component_ret20", 0));
            }
            for (int i = 0; i < sorted.size(); i++) {
                var member = sorted.get(i); var stock = stocks.get(member.stock());
                if (i < 3) top3.add(member.weight());
                if (i < 10 && stock != null) { topVol.add(member.weight() * stock.f("component_vol60", 0)); topHighVol.add(member.weight() * stock.f("component_high_vol_flag", 0));
                    topTurnover.add(member.weight() * stock.f("component_high_turnover_flag", 0)); topTouch.add(member.weight() * stock.f("component_limit_touch20", 0)); }
            }
            values.put("component_n", members.size()); values.put("hit_n", hits); values.put("component_l2_score", l2.sum()); values.put("component_buy_breadth", buy.sum());
            values.put("top30_l2_weight", top30.sum()); values.put("top100_l2_weight", top100.sum()); values.put("top_component_contrib", maximum); values.put("component_valid_vol_weight", volWeight.sum());
            values.put("weighted_component_vol60", volWeight.sum() == 0 ? Double.NaN : vol.sum() / volWeight.sum()); values.put("weighted_component_ret20", ret20.sum());
            values.put("component_hit_rate", (double) hits / members.size()); values.put("component_symbols", String.join("|", members.stream().map(Member::stock).distinct().sorted().toList()));
            values.put("top10_component_vol60", topVol.sum()); values.put("top10_high_vol_weight", topHighVol.sum()); values.put("top10_high_turnover_weight", topTurnover.sum());
            values.put("top10_limit_touch_weight", topTouch.sum()); values.put("top3_weight_concentration", top3.sum()); result.put(etf, values);
        }); return result;
    }
    private static void marketScores(List<MutableRow> day) {
        rank(day, "ret_20", "__trend20", true, .5); rank(day, "ret_5", "__trend5", true, .5); rank(day, "pos_60", "__pos60", true, .5);
        rank(day, "liquidity_amount", "liquidity_score", true, 0); rank(day, "ret_std_20", "__stability", true, .5);
        rank(day, "weighted_component_vol60", "component_vol60_risk_rank", true, .5);
        for (var row : day) {
            row.put("trend_score", .25 * row.n("__trend20") + .15 * row.n("__trend5") + .10 * (1 - row.n("__pos60")));
            row.put("stability_score", 1 - row.n("__stability"));
            row.put("component_risk_budget_score", .60 * row.n("component_vol60_risk_rank") + .20 * row.f("top10_high_vol_weight", 0) + .10 * row.f("top10_limit_touch_weight", 0) + .10 * row.f("top3_weight_concentration", 0));
            row.put("etf_score", .55 * row.f("component_l2_score", 0) + .20 * row.f("component_buy_breadth", 0) + .20 * row.f("trend_score", 0) + .05 * row.f("liquidity_score", 0));
        }
    }
    private static void metadataScores(List<MutableRow> day) {
        var nonBroad = day.stream().filter(row -> !row.s("etf_category").equals("broad")).toList();
        rank(nonBroad, "ret_20", "__legacy20", true, .5); rank(nonBroad, "ret_5", "__legacy5", true, .5);
        rank(nonBroad, "pos_60", "__legacyPos", true, .5); rank(nonBroad, "liquidity_amount", "__legacyLiquidity", true, 0);
        for (var row : day) {
            boolean broad = row.s("etf_category").equals("broad");
            double trend = broad ? row.n("trend_score") : .25 * row.n("__legacy20") + .15 * row.n("__legacy5") + .10 * (1 - row.n("__legacyPos"));
            double liquidity = broad ? row.n("liquidity_score") : row.n("__legacyLiquidity");
            row.put("trend_score_legacy", trend); row.put("liquidity_score_legacy", liquidity);
            row.put("etf_score_legacy", .55 * row.f("component_l2_score", 0) + .20 * row.f("component_buy_breadth", 0) + .20 * fill(trend, 0) + .05 * fill(liquidity, 0));
            row.put("active_lof_score", .60 * row.f("trend_score", 0) + .25 * row.f("liquidity_score", 0) + .15 * row.f("stability_score", 0));
            row.put("active_lof_score_legacy", .60 * fill(trend, 0) + .25 * fill(liquidity, 0) + .15 * row.f("stability_score", 0));
            if (row.b("etf_is_active_lof") && Double.isNaN(row.n("component_l2_score"))) { row.put("etf_score", row.n("active_lof_score")); row.put("etf_score_legacy", row.n("active_lof_score_legacy")); }
        }
    }
    private static void rankRegime(List<MutableRow> day) {
        rank(day, "component_l2_score", "component_l2_rank_pct", true, 0); rank(day, "component_buy_breadth", "component_buy_rank_pct", true, 0); rank(day, "top100_l2_weight", "top100_rank_pct", true, 0);
        double median = median(day.stream().mapToDouble(row -> row.n("component_buy_breadth")).toArray());
        for (var row : day) {
            boolean eligible = !row.s("etf_category").equals("broad") || row.n("component_l2_rank_pct") >= .50 && row.n("component_buy_rank_pct") >= .50 && row.n("top100_rank_pct") >= .50 && row.n("liquidity_score") >= .50;
            row.put("broad_eligible", eligible); row.put("broad_adjusted_score", row.n("etf_score") + (row.s("etf_category").equals("broad") && eligible ? .03 * row.f("stability_score", 0) + .02 * row.f("liquidity_score", 0) : 0));
            String regime = row.n("pos_60") <= .35 && row.n("ret_3") > 0 && row.n("component_buy_breadth") >= median ? "low_reversal" : "neutral";
            if (row.n("pos_60") >= .80 && (row.n("ret_5") > .08 || row.n("rsi_bfq_6") > 80)) regime = "overheated";
            row.put("regime", regime);
        }
    }
    private static double median(double[] source) {
        double[] values = Arrays.stream(source).filter(v -> !Double.isNaN(v)).sorted().toArray(); int n = values.length;
        return n == 0 ? Double.NaN : n % 2 == 1 ? values[n / 2] : (values[n / 2 - 1] + values[n / 2]) / 2;
    }
    private static void allocations(List<MutableRow> day) {
        var specs = Map.of("invvol60", "ret_std_60", "component_invvol60", "weighted_component_vol60", "component_risk_budget", "component_risk_budget_score");
        for (var spec : specs.entrySet()) {
            double[] raw = day.stream().mapToDouble(row -> row.n(spec.getValue()) > 0 ? 1 / row.n(spec.getValue()) : Double.NaN).toArray();
            double fallback = fill(median(raw), 1), total = 0;
            for (int i = 0; i < raw.length; i++) { raw[i] = fill(raw[i], fallback); total += raw[i]; }
            for (int i = 0; i < day.size(); i++) { double weight = total > 0 ? raw[i] / total : 1.0 / day.size(); day.get(i).put(spec.getKey() + "_raw_weight", weight); day.get(i).put(spec.getKey() + "_target_weight", Math.min(.25, .70 * weight)); }
        }
    }
    private static void smooth(List<MutableRow> rows, Settings settings) {
        for (String column : List.of("component_l2_score", "component_buy_breadth", "active_lof_score")) {
            rolling(rows, column, column + "_ma3", 3, 1, false); rolling(rows, column, column + "_ma5", 5, 1, false);
            for (var row : rows) row.put(column + "_smooth", .50 * row.f(column, 0) + .30 * row.f(column + "_ma3", 0) + .20 * row.f(column + "_ma5", 0));
        }
        for (var row : rows) {
            row.put("etf_quality_ok", row.n("component_buy_breadth_smooth") >= settings.minBuyBreadth() && row.n("top100_l2_weight") >= settings.minTop100Weight());
            row.put("etf_score_smooth", .55 * row.f("component_l2_score_smooth", 0) + .20 * row.f("component_buy_breadth_smooth", 0) + .20 * row.f("trend_score", 0) + .05 * row.f("liquidity_score", 0));
            row.put("etf_score_smooth_quality", row.n("etf_score_smooth") - .12 * Math.max(0, settings.minBuyBreadth() - row.f("component_buy_breadth_smooth", 0)) - .08 * Math.max(0, settings.minTop100Weight() - row.f("top100_l2_weight", 0)));
            if (row.b("etf_is_active_lof") && Double.isNaN(row.n("component_l2_score"))) { row.put("etf_quality_ok", true); row.put("etf_score_smooth", row.n("active_lof_score_smooth")); row.put("etf_score_smooth_quality", row.n("active_lof_score_smooth")); }
        }
    }
    private static void comboDiagnostics(List<MutableRow> rows) {
        for (var row : rows) {
            row.put("self_overheat_flag", row.f("ret_5", 0) >= .08 || row.f("pos_60", 0) >= .88 || row.f("rsi_bfq_6", 0) >= 82);
            row.put("component_crowding_score", clip(.30 * row.f("top10_high_turnover_weight", 0) / .25 + .25 * row.f("top10_limit_touch_weight", 0) / .08 + .25 * row.f("top3_weight_concentration", 0) / .50 + .20 * row.f("top10_high_vol_weight", 0) / .35, 0, 2));
            row.put("component_crowding_flag", row.f("top10_high_turnover_weight", 0) >= .20 || row.f("top10_limit_touch_weight", 0) >= .06 || row.f("top3_weight_concentration", 0) >= .46 || row.f("top10_high_vol_weight", 0) >= .32);
            row.put("overheat_score", clip(.35 * Math.max(0, row.f("ret_5", 0)) / .10 + .30 * clip(row.f("pos_60", 0), 0, 1) + .35 * Math.max(0, row.f("rsi_bfq_6", 50) - 50) / 40, 0, 2));
            row.put("strategy", "feature_rank_v2_dynamic");
        }
    }
    private static void chip(List<MutableRow> rows, List<ScoreRow> chips) {
        rank(rows, "component_l2_score_smooth", "component_accumulation_score", true, .5); rank(rows, "component_buy_breadth_smooth", "__chip_breadth", true, .5);
        rank(rows, "component_crowding_score", "component_chase_risk_score", true, .5); rank(rows, "weighted_component_vol60", "__chip_vol", true, .5);
        rank(rows, "top10_high_vol_weight", "__chip_high_vol", true, .5); rank(rows, "top10_limit_touch_weight", "__chip_touch", true, .5);
        var byKey = new HashMap<List<Object>, ScoreRow>();
        for (var row : chips) if (byKey.put(List.of(row.tradeDate(), row.symbol()), row) != null) throw new IllegalArgumentException("Duplicate chip observation");
        for (var row : rows) {
            row.put("component_executable_breadth", clip(.65 * row.n("__chip_breadth") + .35 * clip(row.f("component_hit_rate", 0), 0, 1), 0, 1));
            row.put("component_distribution_risk_score", row.n("component_chase_risk_score"));
            row.put("component_downside_risk_score", clip(.45 * row.n("__chip_vol") + .35 * row.n("__chip_high_vol") + .20 * row.n("__chip_touch"), 0, 1));
            var symbols = Arrays.stream(row.s("component_symbols").split("[|;,]")).map(String::strip).filter(s -> !s.isBlank()).distinct().toList();
            int matched = 0, activityCount = 0, chaseCount = 0, breakdownCount = 0, activityFlags = 0, chaseFlags = 0, breakdownFlags = 0, flagCount = 0;
            var activity = new GroupSum(); var chase = new GroupSum(); var breakdown = new GroupSum();
            for (String symbol : symbols) {
                var part = byKey.get(List.of(row.date, symbol)); if (part == null) continue; flagCount++;
                if (!Double.isNaN(part.number("l2_activity_score"))) { matched++; activity.add(part.number("l2_activity_score")); activityCount++; }
                if (!Double.isNaN(part.number("l2_chase_risk_score"))) { chase.add(part.number("l2_chase_risk_score")); chaseCount++; }
                if (!Double.isNaN(part.number("l2_breakdown_risk_score"))) { breakdown.add(part.number("l2_breakdown_risk_score")); breakdownCount++; }
                activityFlags += part.flag("l2_activity_top20_flag") ? 1 : 0; chaseFlags += part.flag("l2_chase_risk_top20_flag") ? 1 : 0; breakdownFlags += part.flag("l2_breakdown_top10_flag") ? 1 : 0;
            }
            double match = symbols.isEmpty() ? 0 : (double) matched / symbols.size(); row.put("component_l2_match_rate", match);
            double af = flagCount == 0 ? Double.NaN : (double) activityFlags / flagCount, cf = flagCount == 0 ? Double.NaN : (double) chaseFlags / flagCount, bf = flagCount == 0 ? Double.NaN : (double) breakdownFlags / flagCount;
            row.put("component_activity_top20_weight", af); row.put("component_chase_top20_weight", cf); row.put("component_breakdown_top10_weight", bf);
            if (match >= .35) {
                if (activityCount > 0) row.put("component_accumulation_score", activity.mean());
                if (chaseCount > 0) { row.put("component_chase_risk_score", chase.mean()); row.put("component_distribution_risk_score", chase.mean()); }
                if (breakdownCount > 0) row.put("component_downside_risk_score", breakdown.mean());
                row.put("component_executable_breadth", clip(fill(af, row.n("component_executable_breadth")) * (1 - .50 * fill(cf, 0)) * (1 - .70 * fill(bf, 0)), 0, 1));
            }
        }
    }
    private static void overlay(List<MutableRow> rows, FrozenPromotedRecipe promoted) {
        for (var factor : FACTOR_COLUMNS.entrySet()) {
            if (rows.stream().noneMatch(row -> row.values.containsKey(factor.getValue()))) rows.forEach(row -> row.put(factor.getValue(), 0));
            rank(rows, factor.getValue(), factor.getKey() + "_rank", !NEGATIVE_FACTORS.contains(factor.getKey()), .5);
        }
        var recipe = promoted.recipe();
        for (var row : rows) {
            row.put("score_component_l2_contrib", row.f("component_l2_score_smooth", 0) * recipe.componentL2Weight()); row.put("score_buy_breadth_contrib", row.f("component_buy_breadth_smooth", 0) * recipe.buyBreadthWeight());
            row.put("score_trend_contrib", row.f("trend_score", 0) * recipe.trendWeight()); row.put("score_liquidity_contrib", row.f("liquidity_score", 0) * recipe.liquidityWeight());
            double base = row.n("score_component_l2_contrib") + row.n("score_buy_breadth_contrib") + row.n("score_trend_contrib") + row.n("score_liquidity_contrib");
            double penalty = .12 * Math.max(0, .25 - row.f("component_buy_breadth_smooth", 0)) + .08 * Math.max(0, .40 - row.f("top100_l2_weight", 0));
            row.put("optimized_score_smooth", base); row.put("score_quality_penalty", penalty); row.put("optimized_score_quality", base - penalty);
            row.put("static_etf_factor_score_base",base-penalty);row.put("static_optimized_score_quality",base-penalty);
            row.put("weight_component_l2", recipe.componentL2Weight()); row.put("weight_buy_breadth", recipe.buyBreadthWeight()); row.put("weight_trend", recipe.trendWeight()); row.put("weight_liquidity", recipe.liquidityWeight());
            var weights = FrozenPromotedRecipe.asOf(promoted.factorWeights(), row.date); double score = 0, sum = 0;
            for (String factor : FACTOR_COLUMNS.keySet()) { double weight = fill(number(weights.get("w_" + factor)), 0); row.put("w_" + factor, weight); score += weight * row.f(factor + "_rank", .5); sum += weight; }
            row.put("regime_weighted_score_raw", sum > 0 ? score / sum : score); row.put("dynamic_evidence_sum", fill(number(weights.get("dynamic_evidence_sum")), 0));
        }
        for (var group : byDate(rows).values()) {
            double low = group.stream().mapToDouble(row -> row.n("optimized_score_quality")).min().orElse(Double.NaN), high = group.stream().mapToDouble(row -> row.n("optimized_score_quality")).max().orElse(Double.NaN);
            for (var row : group) {
                double regime = high > low ? low + row.n("regime_weighted_score_raw") * (high - low) : row.n("optimized_score_quality");
                double active = row.n("dynamic_evidence_sum") > 0 ? recipe.dynamicOverlay() : 0;
                row.put("regime_weighted_score", regime); row.put("dynamic_overlay", active);
                row.put("etf_factor_score_base", (1 - active) * row.n("optimized_score_quality") + active * regime);
                row.put("optimized_score_quality", row.n("etf_factor_score_base")); row.put("weight_spec", "dynamic_overlay_10_on_" + recipe.scoreRecipeId());
            }
        }
    }
}
