package com.zoutrankil.market.us.marketdata;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalContract;
import com.zoutrankil.data.domain.DatasetDefinition.TemporalKind;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** 供应商历史证券目录只证明目录和来源窗口；实际价格、退市覆盖及授权另行逐证券核验。 */
public final class UsHistoricalSecurityCatalog {
    private UsHistoricalSecurityCatalog() {}

    private static final Set<String> US_EXCHANGES =
            Set.of(
                    "AMEX",
                    "BATS",
                    "EXPM",
                    "NASDAQ",
                    "NYSE",
                    "NYSE ARCA",
                    "NYSE MKT",
                    "NYSE NAT",
                    "OTCBB",
                    "OTCCE",
                    "OTCD",
                    "OTCGREY",
                    "OTCMKTS",
                    "OTCQB",
                    "OTCQX",
                    "PINK");
    public static final DatasetDefinition SECURITIES =
            new DatasetDefinition(
                    "us_security_history_catalog",
                    1,
                    "tiingo.supported_tickers",
                    "java.market.us",
                    "us_security_history_catalog",
                    ObjectKind.TABLE,
                    List.of(
                            field(
                                    "source_row",
                                    StorageType.LONG,
                                    false,
                                    "完整 CSV 的原始数据行序号；保留同代码的不同历史窗口",
                                    false),
                            field("observed_at", StorageType.TIMESTAMP, false, "目录实际取得时间", false),
                            field(
                                    "symbol",
                                    StorageType.SYMBOL,
                                    false,
                                    "Tiingo 原始证券代码，可能包含已退市或预留代码",
                                    false),
                            field(
                                    "exchange",
                                    StorageType.SYMBOL,
                                    false,
                                    "来源记录的美国挂牌或 OTC 场所",
                                    false),
                            field("asset_type", StorageType.SYMBOL, false, "Stock 或 ETF", false),
                            field("currency", StorageType.SYMBOL, true, "来源价格币种，未知保持空值", false),
                            field(
                                    "source_start_date",
                                    StorageType.TIMESTAMP,
                                    true,
                                    "来源声称的首个可用日期，不代表本地已取得",
                                    true),
                            field(
                                    "source_end_date",
                                    StorageType.TIMESTAMP,
                                    true,
                                    "来源声称的最后可用日期，不等于正式退市日",
                                    true),
                            field(
                                    "daily_candidate",
                                    StorageType.BOOLEAN,
                                    false,
                                    "是否纳入股票历史价格请求",
                                    false),
                            field("scope_reason", StorageType.SYMBOL, false, "候选、窗口或映射排除原因", false),
                            field(
                                    "yahoo_symbol",
                                    StorageType.SYMBOL,
                                    true,
                                    "符合明确规则的 Yahoo 请求代码，不能猜测历史代码别名",
                                    false),
                            field(
                                    "source_sha256",
                                    StorageType.SYMBOL,
                                    false,
                                    "完整供应商目录 ZIP 的 SHA-256",
                                    false)),
                    List.of("observed_at", "source_sha256", "source_row"),
                    List.of("observed_at", "source_sha256", "source_row"),
                    "observed_at",
                    Partition.YEAR,
                    true,
                    Set.of(Capability.READ, Capability.WRITE),
                    List.of(),
                    "保留完整美国 Stock/ETF 目录及排除原因；历史日期是供应商声明的可用窗口，不能当作上市退市事件或已取得价格的证据。");

    public static List<Map<String, Object>> rows(
            byte[] zip, String sha, Instant observed, LocalDate requestedFrom, LocalDate through)
            throws Exception {
        if (through.isBefore(requestedFrom)) throw new IllegalArgumentException("历史证券请求窗口倒置");
        byte[] csv;
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var entry = input.getNextEntry();
            if (entry == null || !entry.getName().endsWith(".csv"))
                throw new IllegalArgumentException("历史证券目录不是单 CSV ZIP");
            csv = input.readNBytes(32 * 1024 * 1024 + 1);
            if (csv.length > 32 * 1024 * 1024 || input.getNextEntry() != null)
                throw new IllegalArgumentException("历史证券目录超限或多文件");
        }
        var lines = new String(csv, StandardCharsets.UTF_8).split("\\R");
        if (!lines[0].equals("ticker,exchange,assetType,priceCurrency,startDate,endDate"))
            throw new IllegalArgumentException("历史证券目录字段改变");
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            var c = lines[i].split(",", -1);
            if (c.length != 6) throw new IllegalArgumentException("历史证券 CSV 字段不足或不支持的引号格式");
            if (!US_EXCHANGES.contains(c[1]) || !Set.of("Stock", "ETF").contains(c[2])) continue;
            if (c[0].isBlank()) throw new IllegalArgumentException("历史证券代码为空");
            LocalDate from = c[4].isBlank() ? null : LocalDate.parse(c[4]),
                    end = c[5].isBlank() ? null : LocalDate.parse(c[5]);
            if (from != null && end != null && from.isAfter(end))
                throw new IllegalArgumentException("供应商历史证券日期倒置");
            boolean mapping = c[0].matches("[A-Z0-9]+(?:[.-][A-Z0-9]+)?");
            String reason =
                    from == null || end == null
                            ? "SOURCE_HISTORY_DATES_MISSING"
                            : end.isBefore(requestedFrom)
                                    ? "BEFORE_REQUESTED_HISTORY"
                                    : from.isAfter(through)
                                            ? "AFTER_CUTOFF"
                                            : c[2].equals("ETF")
                                                    ? "ETF_SEPARATE_SCOPE"
                                                    : !c[3].equals("USD")
                                                            ? "NON_USD_MAPPING_REQUIRED"
                                                            : !mapping
                                                                    ? "SYMBOL_MAPPING_REQUIRED"
                                                                    : "HISTORICAL_STOCK_CANDIDATE";
            var row = new LinkedHashMap<String, Object>();
            row.put("source_row", (long) i);
            row.put("observed_at", observed.toString());
            row.put("symbol", c[0]);
            row.put("exchange", c[1]);
            row.put("asset_type", c[2]);
            row.put("currency", c[3].isBlank() ? null : c[3]);
            row.put("source_start_date", date(from));
            row.put("source_end_date", date(end));
            row.put("daily_candidate", reason.equals("HISTORICAL_STOCK_CANDIDATE"));
            row.put("scope_reason", reason);
            row.put("yahoo_symbol", mapping ? c[0].replace('.', '-') : null);
            row.put("source_sha256", sha);
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("完整目录没有美国证券记录");
        return rows;
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    private static Column field(
            String name, StorageType type, boolean nullable, String meaning, boolean date) {
        return new Column(
                name,
                name,
                name,
                type,
                nullable,
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
