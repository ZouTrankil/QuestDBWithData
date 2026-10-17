package com.zoutrankil.data.strategy.score;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EtfOnlyTop2ScoreProducerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode golden() throws IOException {
        try (var stream = EtfOnlyTop2ScoreProducerTest.class.getResourceAsStream("/score/python-promoted-golden.json")) {
            return JSON.readTree(Objects.requireNonNull(stream));
        }
    }
    static Map<String, Object> values(JsonNode row, Set<String> excluded) {
        var values = new LinkedHashMap<String, Object>();
        row.fields().forEachRemaining(entry -> {
            if (!excluded.contains(entry.getKey())) {
                var node = entry.getValue(); values.put(entry.getKey(), node.isNull() ? null : node.isNumber() ? node.asDouble()
                        : node.isBoolean() ? node.asBoolean() : node.asText());
            }
        }); return values;
    }
    static List<ScoreRow> rows(JsonNode data, String symbolColumn) {
        var result = new ArrayList<ScoreRow>();
        for (var row : data) result.add(new ScoreRow(LocalDate.parse(row.get("trade_date").asText()),
                row.has(symbolColumn) ? row.get(symbolColumn).asText() : "MARKET", values(row, Set.of("trade_date", symbolColumn))));
        return result;
    }
    static List<FrozenPromotedRecipe.DatedValues> dated(JsonNode data) {
        var result = new ArrayList<FrozenPromotedRecipe.DatedValues>();
        for (var row : data) result.add(new FrozenPromotedRecipe.DatedValues(LocalDate.parse(row.get("trade_date").asText()), values(row, Set.of("trade_date"))));
        return result;
    }
    static FrozenPromotedRecipe promoted(JsonNode golden) {
        var cutoff = LocalDate.parse(golden.get("training_cutoff").asText());
        var hash = FrozenPromotedRecipe.sha256(golden.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FrozenPromotedRecipe(new FrozenPromotedRecipe.Recipe("ETF_ONLY_TOP2", "oracle", cutoff.plusDays(1), cutoff,
                "etf_only_top2_score_panel_v1", "frozen-oracle", .55, .20, .20, .05, .10, 2, .50),
                dated(golden.get("factor_weights")), dated(golden.get("factor_states")), dated(golden.get("l2_weights")),
                Map.of("recipe", hash, "factor_weights", hash, "factor_states", hash, "l2_factor_weights", hash));
    }
    static EtfOnlyTop2ScoreProducer.Inputs inputs(JsonNode golden) {
        var metadata = new HashMap<String, L2StockScoreProducer.StockMetadata>();
        for (var row : golden.get("stock_metadata")) metadata.put(row.get("symbol").asText(), new L2StockScoreProducer.StockMetadata(row.get("name").asText(), row.get("industry_l2").asText()));
        var referenceRaw = new ArrayList<Map<String, Object>>(); for (var row : golden.get("etf_basic_raw")) referenceRaw.add(values(row, Set.of()));
        var holdings = new ArrayList<EtfOnlyTop2ScoreProducer.Holding>();
        for (var row : golden.get("holdings")) holdings.add(new EtfOnlyTop2ScoreProducer.Holding(row.get("ts_code").asText(), row.get("symbol").asText(),
                LocalDate.parse(row.get("ann_date").asText()), LocalDate.parse(row.get("end_date").asText()), row.get("stk_mkv_ratio").asDouble(), row.get("mkv").asDouble()));
        var dates = new ArrayList<LocalDate>(); for (var date : golden.get("signal_dates")) dates.add(LocalDate.parse(date.asText()));
        var versions = new HashMap<String, String>(); for (String name : List.of("stock_daily", "l2", "stock_metadata", "etf_factor", "etf_reference", "etf_portfolio", "market_sentiment", "chip")) versions.put(name, "offline-oracle-pinned");
        var sentiment = EtfOnlyTop2ScoreProducer.prepareMarketSentiment(rows(golden.get("sentiment_raw"), "symbol"), dates.getFirst(), dates.getLast());
        return new EtfOnlyTop2ScoreProducer.Inputs(rows(golden.get("stock_daily"), "ts_code"), rows(golden.get("l2_raw"), "symbol"), metadata,
                rows(golden.get("etf_factor"), "ts_code"), EtfOnlyTop2ScoreProducer.prepareEtfReference(referenceRaw, true), holdings,
                rows(golden.get("chip"), "symbol"), sentiment, dates, versions, dates.getLast(), true);
    }
    private static Map<String, ScoreRow> index(List<ScoreRow> rows) {
        var result = new HashMap<String, ScoreRow>(); rows.forEach(row -> assertNull(result.put(row.tradeDate() + "/" + row.symbol(), row))); return result;
    }
    private static void assertFields(List<ScoreRow> actual, JsonNode expected, String symbol, Collection<String> fields) {
        var indexed = index(actual); assertEquals(expected.size(), indexed.size());
        for (var row : expected) {
            String key = row.get("trade_date").asText() + "/" + (row.has(symbol) ? row.get(symbol).asText() : "ETF_UNIVERSE");
            var result = indexed.get(key); assertNotNull(result, key);
            for (String field : fields) {
                assertTrue(row.has(field), "oracle lacks " + field); assertTrue(result.values().containsKey(field), key + " Java lacks " + field);
                var value = row.get(field);
                if (value.isNull()) assertTrue(Double.isNaN(result.number(field)), key + " " + field + " should be NaN");
                else if (value.isNumber()) assertEquals(value.asDouble(), result.number(field), 2e-10, key + " " + field);
                else if (value.isBoolean()) assertEquals(value.asBoolean(), result.flag(field), key + " " + field);
                else assertEquals(value.asText(), result.text(field), key + " " + field);
            }
        }
    }
    @Test void stockCoreAndPromotedFactorsMatchOriginalPython() throws Exception {
        var fixture = golden(); var input = inputs(fixture);
        var output = new L2StockScoreProducer().produce(input.l2Raw(), input.stockDaily(), input.stockMetadata(), input.chipRows(), true, promoted(fixture));
        var featureFields = new ArrayList<>(List.of("prob_acc", "prob_risk", "exp_ret_rank", "filter_reason", "base_score_rank", "crowding_score", "positive_alpha_static", "risk_penalty_static", "regime_positive_alpha", "dynamic_guard_penalty", "feature_rank_v2_dynamic_score"));
        featureFields.addAll(L2StockScoreProducer.POSITIVE_FACTORS); featureFields.addAll(L2StockScoreProducer.RISK_FACTORS.keySet());
        assertFields(output, fixture.get("expected_features"), "symbol", featureFields);
        assertFields(output, fixture.get("expected_stock"), "symbol", List.of("final_score", "l2_rank", "l2_buy_candidate", "clean", "filter_reason", "guard_ret_20d", "guard_ret_60d", "guard_ret_120d", "guard_pos_60d", "guard_limit_up_flag", "guard_limit_touch_20d", "guard_hard_no_buy", "guard_soft_overheat", "guard_risk_flag_count", "component_vol60", "component_ret20", "component_turnover", "component_limit_touch20", "component_high_vol_flag", "component_high_turnover_flag"));
    }
    @Test void pitEtfAggregatesCoverageMetadataAndOverlayMatchOriginalPython() throws Exception {
        var fixture = golden(); var input = inputs(fixture); var output = new EtfOnlyTop2ScoreProducer().produce(input, promoted(fixture));
        var fields = new ArrayList<>(List.of("name", "etf_category", "broad_family", "annual_fee", "etf_is_lof", "etf_is_active_lof", "component_n", "hit_n", "component_symbols", "component_l2_score", "component_buy_breadth", "component_l2_score_smooth", "component_buy_breadth_smooth", "component_hit_rate", "top30_l2_weight", "top100_l2_weight", "top_component_contrib", "component_valid_vol_weight", "weighted_component_vol60", "weighted_component_ret20", "top10_component_vol60", "top10_high_vol_weight", "top10_high_turnover_weight", "top10_limit_touch_weight", "top3_weight_concentration", "ret_3", "ret_5", "ret_20", "ret_std_20", "ret_std_60", "pos_60", "liquidity_amount", "liquidity_score", "liquidity_ok", "trend_score", "stability_score", "component_risk_budget_score", "broad_eligible", "broad_adjusted_score", "regime", "etf_score", "etf_score_legacy", "etf_score_smooth", "etf_score_smooth_quality", "active_lof_score", "etf_quality_ok", "component_crowding_score", "component_crowding_flag", "self_overheat_flag", "overheat_score", "component_l2_match_rate", "component_activity_top20_weight", "component_chase_top20_weight", "component_breakdown_top10_weight", "component_executable_breadth", "component_accumulation_score", "component_chase_risk_score", "component_distribution_risk_score", "component_downside_risk_score", "score_quality_penalty", "optimized_score_smooth", "regime_weighted_score_raw", "regime_weighted_score", "dynamic_overlay", "etf_factor_score_base", "optimized_score_quality", "weight_spec"));
        for (String factor : EtfOnlyTop2ScoreProducer.FACTOR_COLUMNS.keySet()) fields.add(factor + "_rank");
        for (String name : List.of("invvol60", "component_invvol60", "component_risk_budget")) { fields.add(name + "_raw_weight"); fields.add(name + "_target_weight"); }
        assertFields(output.panel(), fixture.get("expected_panel"), "ts_code", fields);
        assertFields(output.coverage(), fixture.get("expected_coverage"), "symbol", List.of("etf_universe_n", "etf_with_portfolio_n", "etf_scored_n", "component_hit_rate", "liquidity_filtered_n", "liquidity_window", "liquidity_min_periods", "liquidity_min_amount"));
        assertEquals(input.signalDates().getLast(), output.maxCompleteDate()); assertEquals(input.signalDates().size(), output.factorWeights().size());
        assertEquals("frozen-oracle", output.provenance().get("code_revision"));
    }
    @Test void sentimentQuintilesAndReferenceClassificationMatchOriginalPython() throws Exception {
        var fixture = golden(); var input = inputs(fixture);
        var sentiment = index(input.marketSentiment());
        for (var row : fixture.get("sentiment")) assertEquals(row.get("sentiment_quintile").asText(), sentiment.get(row.get("trade_date").asText() + "/MARKET").text("sentiment_quintile"));
        var references = new HashMap<String, EtfOnlyTop2ScoreProducer.EtfReference>(); input.references().forEach(row -> references.put(row.symbol(), row));
        assertEquals(fixture.get("etf_reference").size(), references.size());
        for (var row : fixture.get("etf_reference")) { var result = references.get(row.get("ts_code").asText()); assertEquals(row.get("etf_category").asText(), result.category()); assertEquals(row.get("broad_family").asText(), result.broadFamily()); assertEquals(row.get("annual_fee").asDouble(), result.annualFee(), 1e-14); }
    }
    @Test void incompleteDateProofMissingSourcesOrMissingSentimentFailClosed() throws Exception {
        var fixture = golden(); var input = inputs(fixture);
        assertThrows(IllegalArgumentException.class, () -> new EtfOnlyTop2ScoreProducer.Inputs(input.stockDaily(), input.l2Raw(), input.stockMetadata(), input.etfFactors(), input.references(), input.holdings(), input.chipRows(), input.marketSentiment(), input.signalDates(), input.sourceVersions(), input.maxCompleteDate(), false));
        assertThrows(IllegalArgumentException.class, () -> new EtfOnlyTop2ScoreProducer.Inputs(input.stockDaily(), input.l2Raw(), input.stockMetadata(), input.etfFactors(), input.references(), input.holdings(), input.chipRows(), List.of(), input.signalDates(), input.sourceVersions(), input.maxCompleteDate(), true));
        assertThrows(IllegalArgumentException.class, () -> new EtfOnlyTop2ScoreProducer.Inputs(input.stockDaily(), input.l2Raw(), input.stockMetadata(), input.etfFactors(), input.references(), input.holdings(), input.chipRows(), input.marketSentiment(), input.signalDates(), Map.of(), input.maxCompleteDate(), true));
    }
    @Test void dailyFallbackAddsOnlyMissingKeysAndNeverCoalescesExistingFactorNulls() {
        var date = LocalDate.of(2026, 9, 30); String code = "510300.SH";
        var factor = new ScoreRow(date.minusDays(1), code, new LinkedHashMap<>(Map.of("close", 2., "open", 1.9, "pct_change", 1., "amount", 1000., "rsi_bfq_6", 65.)));
        var currentValues = new LinkedHashMap<>(factor.values()); currentValues.put("close", null); currentValues.put("rsi_bfq_6", null);
        var current = new ScoreRow(date, code, currentValues); var fallback = new ScoreRow(date, code, Map.of("close", 9., "open", 8., "pct_chg", 4., "amount", 2000.));
        var retained = EtfOnlyTop2ScoreProducer.prepareEtfFactor(List.of(factor, current), List.of(fallback), Set.of(code), date);
        assertTrue(Double.isNaN(retained.getLast().number("close"))); assertTrue(Double.isNaN(retained.getLast().number("rsi_bfq_6")));
        var appended = EtfOnlyTop2ScoreProducer.prepareEtfFactor(List.of(factor), List.of(fallback), Set.of(code), date);
        assertEquals(9., appended.getLast().number("close")); assertEquals(65., appended.getLast().number("rsi_bfq_6")); assertEquals(4., appended.getLast().number("pct_change"));
    }
    @Test void sentimentTiesUseFirstRanksAndRightClosedBinsAndFewDistinctScoresUseQ3() {
        var start = LocalDate.of(2026, 9, 1); var source = new ArrayList<ScoreRow>();
        for (int i = 0; i < 10; i++) source.add(new ScoreRow(start.plusDays(i), "MARKET", Map.of("sentiment_score", (double)(i / 2))));
        var cut = EtfOnlyTop2ScoreProducer.prepareMarketSentiment(source, start, start.plusDays(9));
        for (int i = 0; i < cut.size(); i++) assertEquals(List.of("Q1_low", "Q2", "Q3", "Q4", "Q5_high").get(i / 2), cut.get(i).text("sentiment_quintile"));
        var few = EtfOnlyTop2ScoreProducer.prepareMarketSentiment(source.subList(0, 8), start, start.plusDays(9));
        assertTrue(few.stream().allMatch(row -> row.text("sentiment_quintile").equals("Q3")));
    }
}
