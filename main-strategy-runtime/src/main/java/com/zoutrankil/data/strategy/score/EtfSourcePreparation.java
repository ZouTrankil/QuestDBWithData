package com.zoutrankil.data.strategy.score;

import java.time.LocalDate;
import java.util.*;
import static com.zoutrankil.data.strategy.score.ScoreMath.*;

/** Scalar adapter preparation matching etf_market.py, including the whole-column holdings fallback elsewhere. */
public final class EtfSourcePreparation {
    private EtfSourcePreparation() {}
    private static final List<String> FOREIGN = List.of("货币", "现金", "黄金", "白银", "豆粕", "原油", "商品", "纳指", "标普", "恒生", "港股", "沪港深", "港美", "海外", "印度", "全球", "香港", "H股", "HK", "日经", "德国", "法国", "美国", "QDII", "REIT");
    public static List<EtfOnlyTop2ScoreProducer.EtfReference> references(List<Map<String, Object>> raw, boolean includeActiveLof) {
        var result = new LinkedHashMap<String, EtfOnlyTop2ScoreProducer.EtfReference>();
        var activeCodes = new HashSet<String>();
        for (var row : raw) if (text(row, "status").equals("L") && text(row, "market").equals("E")
                && activeLof(text(row, "name"), text(row, "fund_type"), text(row, "invest_type"))) activeCodes.add(text(row, "ts_code"));
        for (var row : raw) {
            if (!text(row, "status").equals("L") || !text(row, "market").equals("E")) continue;
            String name = text(row, "name"), fund = text(row, "fund_type"), invest = text(row, "invest_type"), benchmark = text(row, "benchmark");
            String description = name + " " + benchmark + " " + fund + " " + invest;
            boolean stockish = fund.contains("股票") || contains(invest, List.of("指数", "股票", "成长", "价值", "科技", "主题"), false);
            boolean lof = name.toUpperCase(Locale.ROOT).contains("LOF");
            boolean active = activeLof(name, fund, invest);
            boolean foreign = contains(description, FOREIGN, true);
            if (!(stockish && !description.contains("债") && !foreign || includeActiveLof && active && !foreign)) continue;
            String symbol = text(row, "ts_code"); if (symbol.isBlank()) throw new IllegalArgumentException("ETF reference ts_code missing");
            var classification = classify(description);
            double fee = (fill(number(row.get("m_fee")), .50) + fill(number(row.get("c_fee")), .10)) / 100;
            boolean activeCode = activeCodes.contains(symbol);
            result.putIfAbsent(symbol, new EtfOnlyTop2ScoreProducer.EtfReference(symbol, name, activeCode ? "active_lof" : classification[0], classification[1], fee, lof, activeCode));
        }
        return List.copyOf(result.values());
    }
    private static boolean activeLof(String name, String fund, String invest) {
        return name.toUpperCase(Locale.ROOT).contains("LOF") && (fund.contains("混合")
                || contains(invest, List.of("混合", "灵活", "成长型", "价值型", "稳定型", "主题型"), false));
    }
    private static String text(Map<String, Object> row, String name) { return Objects.toString(row.get(name), ""); }
    /** prepare_market_sentiment ranks ties in date order, then cuts right-closed percentile bins. */
    public static List<ScoreRow> sentiment(List<ScoreRow> raw, LocalDate start, LocalDate end) {
        var rows = raw.stream().filter(row -> !row.tradeDate().isBefore(start) && !row.tradeDate().isAfter(end)
                && !Double.isNaN(row.number("sentiment_score"))).sorted(Comparator.comparing(ScoreRow::tradeDate)).toList();
        if (rows.stream().anyMatch(row -> !Double.isFinite(row.number("sentiment_score"))))
            throw new IllegalArgumentException("Nonfinite sentiment score");
        var unique = new HashSet<Double>(); rows.forEach(row -> unique.add(row.number("sentiment_score")));
        var ranks = new ArrayList<Integer>(); for (int i = 0; i < rows.size(); i++) ranks.add(i);
        ranks.sort(Comparator.comparingDouble(i -> rows.get(i).number("sentiment_score")));
        int[] positions = new int[rows.size()]; for (int i = 0; i < ranks.size(); i++) positions[ranks.get(i)] = i + 1;
        var result = new ArrayList<ScoreRow>(); String[] labels = {"Q1_low", "Q2", "Q3", "Q4", "Q5_high"};
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i); var values = new LinkedHashMap<>(row.values());
            int bin = Math.max(0, (int)Math.ceil(positions[i] * 5.0 / rows.size()) - 1);
            values.put("sentiment_score", row.number("sentiment_score")); values.put("sentiment_quintile", unique.size() < 5 ? "Q3" : labels[bin]);
            result.add(new ScoreRow(row.tradeDate(), row.symbol(), values));
        }
        return List.copyOf(result);
    }
    private static boolean contains(String text, List<String> words, boolean insensitive) {
        String value = insensitive ? text.toLowerCase(Locale.ROOT) : text;
        return words.stream().anyMatch(word -> value.contains(insensitive ? word.toLowerCase(Locale.ROOT) : word));
    }
    private static String[] classify(String text) {
        String[][] entries = {
            {"style","红利低波","红利低波","低波红利"},{"style","红利质量","红利质量","质量红利","红利优选","优选红利"},
            {"style","红利","红利","高股息","高分红"},{"style","低波","低波","低波动","稳健"},{"style","质量","质量","高质量","质量成长"},
            {"style","成长","成长"},{"style","价值","价值"},{"style","现金流","现金流"},{"style","基本面","基本面"},{"style","等权","等权"},
            {"style","高beta","高贝塔","高Beta","高BETA","高beta"},{"style","低beta","低贝塔","低Beta","低BETA","低beta"},{"style","动量","动量","趋势"},{"style","央国企","央企","国企","央国企"},
            {"broad","A500","中证A500","A500"},{"broad","A50","中证A50","A50ETF","A50增强","中国A50","富时A50","MSCI中国A50"},
            {"broad","科创50","科创50"},{"broad","科创100","科创100"},{"broad","双创50","双创50","科创创业50"},{"broad","创业板","创业板","创业板ETF"},
            {"broad","沪深300","沪深300","300ETF"},{"broad","中证500","中证500","500ETF"},{"broad","中证1000","中证1000","1000ETF"},{"broad","中证2000","中证2000","2000ETF"},
            {"broad","上证50","上证50"},{"broad","深证50","深证50","深证主板50"},{"broad","深证100","深证100"},{"broad","MSCI","MSCI"},{"broad","宽基","宽基"}
        };
        for (var entry : entries) for (int i = 2; i < entry.length; i++) if (text.contains(entry[i])) return new String[]{entry[0], entry[1]};
        return new String[]{contains(text, List.of("芯片", "半导体", "通信", "人工智能", "机器人", "新能源", "光伏", "军工", "医药", "消费", "有色", "稀有", "电池", "证券", "旅游"), false) ? "theme" : "sector", ""};
    }
    public static List<ScoreRow> factor(List<ScoreRow> factor, List<ScoreRow> daily, Set<String> codes, LocalDate requestedEnd) {
        if (codes.isEmpty() || factor.isEmpty()) return List.of();
        var rows = mutable(factor.stream().filter(row -> codes.contains(row.symbol()) && !row.tradeDate().isAfter(requestedEnd)).toList());
        if (rows.isEmpty()) return List.of();
        LocalDate latest = rows.stream().map(row -> row.date).max(LocalDate::compareTo).orElseThrow();
        var atEnd = new HashSet<String>(); rows.stream().filter(row -> row.date.equals(requestedEnd)).forEach(row -> atEnd.add(row.symbol));
        var technical = List.of("ma_bfq_20", "ma_bfq_60", "ma_bfq_250", "rsi_bfq_6");
        if (latest.isBefore(requestedEnd) || !atEnd.containsAll(codes)) {
            LocalDate from = (latest.isBefore(requestedEnd) ? latest : requestedEnd).minusDays(5);
            var keys = new HashSet<List<Object>>(); rows.forEach(row -> keys.add(List.of(row.date, row.symbol)));
            var columns = new LinkedHashSet<String>(); rows.forEach(row -> columns.addAll(row.values.keySet()));
            int appended = 0;
            for (var row : daily) if (codes.contains(row.symbol()) && !row.tradeDate().isBefore(from) && !row.tradeDate().isAfter(requestedEnd) && keys.add(List.of(row.tradeDate(), row.symbol()))) {
                var values = new LinkedHashMap<String, Object>();
                for (String column : columns) values.put(column, technical.contains(column) ? Double.NaN : row.values().getOrDefault(column, Double.NaN));
                if (columns.contains("pct_change") && !row.values().containsKey("pct_change")) values.put("pct_change", row.values().getOrDefault("pct_chg", Double.NaN));
                rows.add(new MutableRow(new ScoreRow(row.tradeDate(), row.symbol(), values))); appended++;
            }
            if (appended > 0) for (var group : bySymbol(rows).values()) for (String column : technical) {
                double previous = Double.NaN;
                for (var row : group) { double value = row.n(column); if (!Double.isNaN(value)) previous = value; row.put(column, Double.isNaN(value) ? previous : value); }
            }
        }
        rows.sort(Comparator.comparing((MutableRow row) -> row.symbol).thenComparing(row -> row.date)); return rows.stream().map(MutableRow::freeze).toList();
    }
}
