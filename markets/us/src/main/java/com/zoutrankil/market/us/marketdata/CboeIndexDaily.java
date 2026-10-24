package com.zoutrankil.market.us.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 官方页面确认的是收盘点位；原 CSV 的 OHLC 另留原件，不按股票成交 K 线解释。 */
public final class CboeIndexDaily {
    public static final String SYMBOL = "VIX";
    public static final String PROVIDER = "CBOE";
    public static final String UNIT = "INDEX_POINTS";
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);
    public static final DatasetDefinition DEFINITION =
            new DatasetDefinition(
                    "us_index_daily",
                    1,
                    "cboe.index_close",
                    "java.market.us",
                    "us_index_daily",
                    ObjectKind.TABLE,
                    List.of(
                            field("trade_date", StorageType.TIMESTAMP, "来源纽约业务日期，编码为 UTC 零点", true),
                            field("symbol", StorageType.SYMBOL, "来源指数代码，目前仅 VIX", false),
                            field("provider", StorageType.SYMBOL, "实际提供者 CBOE，不冒充 FRED", false),
                            field("close", StorageType.DOUBLE, "官方指数收盘点位，不是可交易资产价格", false),
                            field(
                                    "value_unit",
                                    StorageType.SYMBOL,
                                    "INDEX_POINTS，不是币种或百分比收益",
                                    false),
                            field("source_sha256", StorageType.SYMBOL, "完整 CSV 原件摘要", false),
                            field(
                                    "observed_at",
                                    StorageType.TIMESTAMP,
                                    "原件取得时刻，不是历史当时可用时刻",
                                    false)),
                    List.of("trade_date", "symbol", "provider"),
                    List.of("trade_date", "symbol", "provider"),
                    "trade_date",
                    Partition.YEAR,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "独立来源及去重键；只存已核对的指数收盘点位，不覆盖既有 FRED 表。");

    private CboeIndexDaily() {}

    public record Parsed(
            List<Map<String, Object>> rows,
            int sourceRows,
            LocalDate sourceFirstDate,
            LocalDate sourceLastDate) {}

    public static Parsed parse(
            byte[] bytes, String sha, Instant observed, LocalDate from, LocalDate through) {
        if (bytes.length == 0
                || bytes.length > 4 * 1024 * 1024
                || !sha.matches("[a-f0-9]{64}")
                || through.isBefore(from)) throw new IllegalArgumentException("指数原件、摘要或窗口不符");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals("DATE,OPEN,HIGH,LOW,CLOSE"))
            throw new IllegalArgumentException("CBOE CSV 表头不符，不能把维护页当作行情");
        var rows = new ArrayList<Map<String, Object>>();
        LocalDate first = null, last = null;
        int count = 0;
        LocalDate observedDay = observed.atZone(ZoneId.of("America/New_York")).toLocalDate();
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != 5 || ++count >= 100000)
                throw new IllegalArgumentException("CBOE CSV 列数或原件行数不符");
            LocalDate day = LocalDate.parse(fields[0], DATE);
            if (day.isAfter(observedDay) || (last != null && !day.isAfter(last)))
                throw new IllegalArgumentException("指数日期重复、倒序或晚于取得日期");
            double close = Double.parseDouble(fields[4]);
            if (!Double.isFinite(close) || close <= 0)
                throw new IllegalArgumentException("指数收盘缺失或非法，不能补零");
            if (first == null) first = day;
            last = day;
            // 只映射官方页面明确提供的收盘值；不修补历史 OPEN/HIGH/LOW 差异。
            if (!day.isBefore(from) && !day.isAfter(through))
                rows.add(
                        Map.of(
                                "trade_date",
                                day.atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                                "symbol",
                                SYMBOL,
                                "provider",
                                PROVIDER,
                                "close",
                                close,
                                "value_unit",
                                UNIT,
                                "source_sha256",
                                sha,
                                "observed_at",
                                observed.toString()));
        }
        // 完整原件必须覆盖所请求的头尾；假日截止应由调用方选择真实的来源尾日。
        if (first == null || first.isAfter(from) || last.isBefore(through) || rows.isEmpty())
            throw new IllegalArgumentException("指数原件未覆盖请求窗口，不能声明回补完成");
        return new Parsed(List.copyOf(rows), count, first, last);
    }

    private static Column field(String name, StorageType type, String meaning, boolean date) {
        return new Column(
                name,
                name,
                name,
                type,
                false,
                meaning,
                type == StorageType.TIMESTAMP
                        ? new TemporalContract(
                                date ? TemporalKind.BUSINESS_DATE : TemporalKind.INSTANT,
                                "ISO-8601",
                                date ? "calendar" : "UTC",
                                "MICROS",
                                meaning)
                        : null);
    }
}
