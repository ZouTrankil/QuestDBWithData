package com.zoutrankil.data.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.zoutrankil.data.domain.DatasetDefinition.StorageType;

/** Frozen, typed Python model fields for D086; source nulls remain null. */
public enum L2DailyFeatureField {
    TS("ts", StorageType.TIMESTAMP, false, "交易日期时间戳", true),
    SYMBOL("symbol", StorageType.SYMBOL, false, "股票代码", false),
    HAS_DEAL("has_deal", StorageType.BOOLEAN, true, "当日是否存在成交数据", false),
    HAS_ORDER("has_order", StorageType.BOOLEAN, true, "当日是否存在委托数据", false),
    HAS_SNAPSHOT("has_snapshot", StorageType.BOOLEAN, true, "当日是否存在快照数据", false),
    DEAL_RECORDS("deal_records", StorageType.LONG, true, "原始成交记录数", false),
    ORDER_RECORDS("order_records", StorageType.LONG, true, "原始委托记录数", false),
    SNAPSHOT_RECORDS("snapshot_records", StorageType.LONG, true, "原始快照记录数", false),
    CONTINUOUS_AUCTION_RATIO("continuous_auction_ratio", StorageType.DOUBLE, true, "连续竞价记录占比", false),
    CROSSED_QUOTE_RATIO("crossed_quote_ratio", StorageType.DOUBLE, true, "盘口倒挂占比", false),
    LOCKED_QUOTE_RATIO("locked_quote_ratio", StorageType.DOUBLE, true, "盘口锁定占比", false),
    MISSING_TOP5_QUOTE_RATIO("missing_top5_quote_ratio", StorageType.DOUBLE, true, "前五档盘口缺失占比", false),
    FEATURE_VERSION("feature_version", StorageType.STRING, true, "特征管线版本号", false),
    PARSER_VERSION("parser_version", StorageType.STRING, true, "解析器版本号", false),
    ORDER_LINK_COVERAGE("order_link_coverage", StorageType.DOUBLE, true, "可建立订单链路的委托记录占比", false),
    DEAL_ORDER_MATCH_RATIO("deal_order_match_ratio", StorageType.DOUBLE, true, "成交可回连到委托单号的占比", false),
    NONNEGATIVE_DEPTH_RATIO("nonnegative_depth_ratio", StorageType.DOUBLE, true, "盘口深度非负记录占比", false),
    VALID_SPREAD_RATIO("valid_spread_ratio", StorageType.DOUBLE, true, "买一卖一价差有效记录占比", false),
    ORPHAN_EXECUTION_RATIO("orphan_execution_ratio", StorageType.DOUBLE, true, "无法回连委托的成交占比", false),
    CANCEL_WITHOUT_FILL_RATIO("cancel_without_fill_ratio", StorageType.DOUBLE, true, "未成交即撤销的订单占比", false),
    PARTIAL_FILL_RATIO("partial_fill_ratio", StorageType.DOUBLE, true, "部分成交后终止的订单占比", false),
    MEDIAN_CANCEL_TIME_MS("median_cancel_time_ms", StorageType.DOUBLE, true, "提交到取消的中位耗时(毫秒)", false),
    MEDIAN_FIRST_FILL_TIME_MS("median_first_fill_time_ms", StorageType.DOUBLE, true, "提交到首次成交的中位耗时(毫秒)", false),
    PASSIVE_FILL_RATIO("passive_fill_ratio", StorageType.DOUBLE, true, "被动成交量占总成交量比例", false),
    LARGE_ORDER_FILL_RATE("large_order_fill_rate", StorageType.DOUBLE, true, "大额订单成交量占提交量比例", false),
    PARTIAL_CANCEL_RATIO("partial_cancel_ratio", StorageType.DOUBLE, true, "发生部分撤单的订单占比", false),
    REPLACE_LIKE_RATIO("replace_like_ratio", StorageType.DOUBLE, true, "撤旧挂新类行为占比(近似代理)", false),
    OPEN_30M_SPREAD_MEAN("open_30m_spread_mean", StorageType.DOUBLE, true, "开盘前30分钟平均相对价差", false),
    CLOSE_30M_OBI_MEAN("close_30m_obi_mean", StorageType.DOUBLE, true, "尾盘前30分钟盘口失衡均值", false),
    MIDDAY_LIQUIDITY_DROP("midday_liquidity_drop", StorageType.DOUBLE, true, "午间相对开盘的流动性下滑幅度", false),
    AFTERNOON_OFI_REVERSAL("afternoon_ofi_reversal", StorageType.DOUBLE, true, "午后OFI相对上午是否反转", false),
    OPEN_CLOSE_VOL_RATIO("open_close_vol_ratio", StorageType.DOUBLE, true, "开盘成交量与尾盘成交量比", false),
    CLOSE_30M_IMPACT_MEAN("close_30m_impact_mean", StorageType.DOUBLE, true, "尾盘30秒价格冲击均值", false),
    AGGRESSOR_LABEL_MATCH_RATIO("aggressor_label_match_ratio", StorageType.DOUBLE, true, "供应商主动方向与盘口推断方向一致占比", false),
    MID_CROSS_MATCH_RATIO("mid_cross_match_ratio", StorageType.DOUBLE, true, "中间价附近成交经tick rule判向后的一致占比", false),
    TICK_RULE_FALLBACK_RATIO("tick_rule_fallback_ratio", StorageType.DOUBLE, true, "需要tick rule回退判向的成交占比", false),
    UNCLASSIFIED_TRADE_RATIO("unclassified_trade_ratio", StorageType.DOUBLE, true, "无法稳定归类主动方向的成交占比", false),
    DEPTH_RECOVERY_5S("depth_recovery_5s", StorageType.DOUBLE, true, "最优盘口深度受冲击后5秒恢复比例", false),
    BEST_QUOTE_DEPLETION_RATE("best_quote_depletion_rate", StorageType.DOUBLE, true, "买一卖一深度被显著耗尽的频率", false),
    ADD_CANCEL_EXECUTE_RATIO_TOP1("add_cancel_execute_ratio_top1", StorageType.DOUBLE, true, "近端新增挂单相对撤单与成交消耗的比例", false),
    DEPTH_TURNOVER_TOP5("depth_turnover_top5", StorageType.DOUBLE, true, "前五档盘口深度换手速度", false),
    BOOK_PRESSURE_DECAY("book_pressure_decay", StorageType.DOUBLE, true, "盘口压力一阶衰减强度", false),
    QUEUE_DEPLETION_SPEED("queue_depletion_speed", StorageType.DOUBLE, true, "最优档位队列消耗速度", false),
    MEAN_REL_AGGRO("mean_rel_aggro", StorageType.DOUBLE, true, "平均主买/主卖侵略性 (0~1)", false),
    MEDIAN_INTER_ARRIVAL_MS("median_inter_arrival_ms", StorageType.DOUBLE, true, "同方向订单到达毫秒级中位数耗时 (评判高频竞争度)", false),
    ALGO_WINDOWS("algo_windows", StorageType.LONG, true, "被判定为机器/算法主导的 5 分钟时间窗数量 (低熵)", false),
    TOTAL_WINDOWS("total_windows", StorageType.LONG, true, "全天有效的 5 分钟时间窗总数", false),
    MEAN_ALGO_ENTROPY("mean_algo_entropy", StorageType.DOUBLE, true, "算法单区域的平均香农时间熵", false),
    MEAN_RETAIL_ENTROPY("mean_retail_entropy", StorageType.DOUBLE, true, "散户单区域的平均香农时间熵", false),
    ALGO_TOTAL_AMOUNT("algo_total_amount", StorageType.DOUBLE, true, "算法(低熵)时段内促成的总成交金额", false),
    RETAIL_TOTAL_AMOUNT("retail_total_amount", StorageType.DOUBLE, true, "散户(高熵)时段内促成的总成交金额", false),
    WASH_TRADE_RATIO("wash_trade_ratio", StorageType.DOUBLE, true, "全天虚假对倒交易占总成交的比例", false),
    WASH_AMOUNT("wash_amount", StorageType.DOUBLE, true, "判定为虚假对倒成交的总金额", false),
    CLEAN_AMOUNT("clean_amount", StorageType.DOUBLE, true, "剥离水分后的真实有效成交金额", false),
    SPOOF_COUNT("spoof_count", StorageType.LONG, true, "发生严重撤单欺诈的时间窗总数", false),
    FAKE_PRESSURE_COUNT("fake_pressure_count", StorageType.LONG, true, "假压单(上方大额挂单后撤单，诱导卖出)的发生次数", false),
    FAKE_SUPPORT_COUNT("fake_support_count", StorageType.LONG, true, "假托底(下方大额挂单后撤单，诱导买入)的发生次数", false),
    MEAN_SELL_OTR("mean_sell_otr", StorageType.DOUBLE, true, "卖方全天平均 OTR 撤单比", false),
    MEAN_BUY_OTR("mean_buy_otr", StorageType.DOUBLE, true, "买方全天平均 OTR 撤单比", false),
    MEAN_CANCEL_DISTANCE_TICKS("mean_cancel_distance_ticks", StorageType.DOUBLE, true, "均值撤单价格距离最优盘口的偏离跳数 (鉴别真假做市)", false),
    TOTAL_NET_FLOW("total_net_flow", StorageType.DOUBLE, true, "取消噪音后的全天真实中枢流向", false),
    Q2_ACCUMULATION("q2_accumulation", StorageType.DOUBLE, true, "仅在 Q2静默吸筹(主力进+小阴跌) 时期的净买入量", false),
    MEAN_VWAP_SKEW("mean_vwap_skew", StorageType.DOUBLE, true, "VWAP 偏度 (正值表示当日量价重心在上方)", false),
    Q1_COUNT("q1_count", StorageType.LONG, true, "出现 主动拉升(流入+涨) 的时间窗数量", false),
    Q2_COUNT("q2_count", StorageType.LONG, true, "出现 静默吸筹(流入+跌) 的时间窗数量", false),
    Q3_COUNT("q3_count", StorageType.LONG, true, "出现 主动出货(流出+跌) 的时间窗数量", false),
    Q4_COUNT("q4_count", StorageType.LONG, true, "出现 拉高出货(流出+涨) 的时间窗数量", false),
    MAIN_NET_INFLOW("main_net_inflow", StorageType.DOUBLE, true, "超大单(>100万)净流入金额", false),
    RETAIL_FUNDS_NET_INFLOW("retail_funds_net_inflow", StorageType.DOUBLE, true, "散户小单净流入金额 (反向参考)", false),
    MAIN_FUNDS_BUY_AMOUNT("main_funds_buy_amount", StorageType.DOUBLE, true, "主动扫盘的超大单总买额", false),
    MAIN_FUNDS_SELL_AMOUNT("main_funds_sell_amount", StorageType.DOUBLE, true, "主动砸盘的超大单总卖额", false),
    MID_TIER_NET_INFLOW("mid_tier_net_inflow", StorageType.DOUBLE, true, "中单(20w~100w)净买入，防范 TWAP 拆单隐藏", false),
    TRADE_SIZE_GINI("trade_size_gini", StorageType.DOUBLE, true, "成交分配基尼系数 (0极散，1极度寡头包揽)", false),
    OPEN_AUCTION_NET_INFLOW("open_auction_net_inflow", StorageType.DOUBLE, true, "集合竞价开盘净流入", false),
    CLOSE_AUCTION_NET_INFLOW("close_auction_net_inflow", StorageType.DOUBLE, true, "集合竞价收盘净流入", false),
    MEAN_SPREAD("mean_spread", StorageType.DOUBLE, true, "全天平均相对买卖价差 Spread", false),
    MEAN_OBI_TOP5("mean_obi_top5", StorageType.DOUBLE, true, "前五档买压 vs 卖压失衡比 (OBI, 短期极强预测)", false),
    AMIHUD_ILLIQUIDITY("amihud_illiquidity", StorageType.DOUBLE, true, "Amihud流动性冲击因子 (全天均值，厚度反指标)", false),
    OBI_TOP1_MEAN("obi_top1_mean", StorageType.DOUBLE, true, "买一卖一失衡均值", false),
    OBI_TOP5_STD("obi_top5_std", StorageType.DOUBLE, true, "前五档失衡波动", false),
    OBI_TOP1_STD("obi_top1_std", StorageType.DOUBLE, true, "买一卖一失衡波动", false),
    DEPTH_TOP1_MEAN("depth_top1_mean", StorageType.DOUBLE, true, "一档总深度均值", false),
    DEPTH_TOP5_MEAN("depth_top5_mean", StorageType.DOUBLE, true, "前五档总深度均值", false),
    DEPTH_SLOPE("depth_slope", StorageType.DOUBLE, true, "深度曲线相对斜率", false),
    DEPTH_CONVEXITY("depth_convexity", StorageType.DOUBLE, true, "深度曲线相对凸性", false),
    MICROPRICE_MINUS_MID_MEAN("microprice_minus_mid_mean", StorageType.DOUBLE, true, "微价格相对中间价偏移均值", false),
    MICROPRICE_MINUS_MID_STD("microprice_minus_mid_std", StorageType.DOUBLE, true, "微价格相对中间价偏移波动", false),
    EFFECTIVE_SPREAD_MEAN("effective_spread_mean", StorageType.DOUBLE, true, "成交相对中间价的有效价差均值", false),
    REALIZED_SPREAD_1M("realized_spread_1m", StorageType.DOUBLE, true, "1分钟实现价差均值", false),
    IMPACT_30S("impact_30s", StorageType.DOUBLE, true, "30秒价格冲击均值", false),
    IMPACT_5M("impact_5m", StorageType.DOUBLE, true, "5分钟价格冲击均值", false),
    ORDERBOOK_REPLENISH_SPEED("orderbook_replenish_speed", StorageType.DOUBLE, true, "盘口被吃后恢复速度", false),
    OFI_MEAN("ofi_mean", StorageType.DOUBLE, true, "盘口 OFI 均值", false),
    OFI_STD("ofi_std", StorageType.DOUBLE, true, "盘口 OFI 波动", false),
    OFI_PERSISTENCE("ofi_persistence", StorageType.DOUBLE, true, "盘口 OFI 一阶持续性", false),
    OFI_POSITIVE_RATIO("ofi_positive_ratio", StorageType.DOUBLE, true, "OFI 为正占比", false),
    OFI_NEGATIVE_RATIO("ofi_negative_ratio", StorageType.DOUBLE, true, "OFI 为负占比", false),
    NEAR_TOUCH_CANCEL_ADD_RATIO("near_touch_cancel_add_ratio", StorageType.DOUBLE, true, "最优盘口附近撤单与新增挂单之比", false),
    GMM_MAIN_FORCE_RATIO("gmm_main_force_ratio", StorageType.DOUBLE, true, "主力资金成交量占比 (GMM 识别)", false),
    GMM_HFT_RATIO("gmm_hft_ratio", StorageType.DOUBLE, true, "高频/算法成交量占比 (GMM 识别)", false),
    GMM_RETAIL_RATIO("gmm_retail_ratio", StorageType.DOUBLE, true, "散户成交量占比 (GMM 识别)", false),
    GMM_MAIN_FORCE_NET_INFLOW("gmm_main_force_net_inflow", StorageType.DOUBLE, true, "GMM 识别后的主力净流入金额", false),
    MFI_PULSE("mfi_pulse", StorageType.DOUBLE, true, "能量脉冲 (由极其激进的扫盘成交聚合而来)", false),
    MFI_ESCORT("mfi_escort", StorageType.DOUBLE, true, "护盘底气 (巨额买单一挡二挡承接力测试)", false),
    MFI_PRECIP("mfi_precip", StorageType.DOUBLE, true, "资金沉淀 (累积定单流失衡正向溢价)", false),
    MFI_SCORE("mfi_score", StorageType.DOUBLE, true, "综合主力进场强度打分(0-1)", false),
    OFI_SLOPE("ofi_slope", StorageType.DOUBLE, true, "订单流不平衡 (OFI) 斜率 (5分钟窗口 OLS Beta)", false),
    TOTAL_RECORDS("total_records", StorageType.LONG, true, "原始逐笔成交总数", false),
    CLEAN_RECORDS("clean_records", StorageType.LONG, true, "清洗过滤后有效总数", false),
    LARGE_ORDER_RECORDS("large_order_records", StorageType.LONG, true, "符合大单的发生次数", false);

    private static final Map<String, L2DailyFeatureField> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(L2DailyFeatureField::name, Function.identity()));
    private final String name;
    private final StorageType storageType;
    private final boolean nullable;
    private final String meaning;
    private final boolean temporal;

    L2DailyFeatureField(String name, StorageType storageType, boolean nullable, String meaning, boolean temporal) {
        this.name = name;
        this.storageType = storageType;
        this.nullable = nullable;
        this.meaning = meaning;
        this.temporal = temporal;
    }

    public String fieldName() { return name; }
    public StorageType storageType() { return storageType; }
    public boolean nullable() { return nullable; }
    public String meaning() { return meaning; }
    public boolean temporal() { return temporal; }
    public boolean identity() { return this == TS || this == SYMBOL; }
    public static L2DailyFeatureField named(String name) { return BY_NAME.get(name); }

    public Object decode(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            if (!nullable) throw new IOException("Required D086 field is missing: " + name);
            return null;
        }
        return switch (storageType) {
            case BOOLEAN -> {
                if (!node.isBoolean()) throw new IOException("D086 field is not BOOLEAN: " + name);
                yield node.booleanValue();
            }
            case LONG -> {
                if (!node.isIntegralNumber() || !node.canConvertToLong())
                    throw new IOException("D086 field is not LONG: " + name);
                yield node.longValue();
            }
            case DOUBLE -> {
                if (!node.isNumber()) throw new IOException("D086 field is not DOUBLE: " + name);
                double value = node.doubleValue();
                if (!Double.isFinite(value)) throw new IOException("D086 field is non-finite: " + name);
                yield value;
            }
            case STRING, SYMBOL -> {
                if (!node.isTextual()) throw new IOException("D086 field is not text: " + name);
                yield node.textValue();
            }
            case TIMESTAMP -> throw new IOException("D086 timestamp is decoded from its business date partition");
            default -> throw new IOException("Unsupported D086 field type: " + name);
        };
    }

    public Object normalize(Object value) {
        if (value == null) {
            if (!nullable) throw new IllegalArgumentException("Required D086 field is null: " + name);
            return null;
        }
        return switch (storageType) {
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("D086 BOOLEAN type mismatch: " + name);
                yield value;
            }
            case LONG -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D086 LONG type mismatch: " + name);
                yield number.longValue();
            }
            case DOUBLE -> {
                if (!(value instanceof Number number)) throw new IllegalArgumentException("D086 DOUBLE type mismatch: " + name);
                double normalized = number.doubleValue();
                if (Double.isNaN(normalized) && nullable) yield null;
                if (!Double.isFinite(normalized)) throw new IllegalArgumentException("D086 DOUBLE is non-finite: " + name);
                yield normalized;
            }
            case STRING, SYMBOL -> {
                if (!(value instanceof String)) throw new IllegalArgumentException("D086 text type mismatch: " + name);
                yield value;
            }
            case TIMESTAMP -> throw new IllegalArgumentException("D086 timestamp is derived from tradeDate");
            default -> throw new IllegalArgumentException("Unsupported D086 field type: " + name);
        };
    }
}
