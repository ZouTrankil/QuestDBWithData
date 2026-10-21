package com.zoutrankil.market.btc.archive.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只映射来源值和事件时间；间隔未知留空，不访问网络或数据库。 */
public final class BinanceFundingMapper {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceFundingMapper() {}

    public static List<Map<String, Object>> map(
            byte[] bytes, LocalDate from, LocalDate through, String sha, Instant observed)
            throws Exception {
        var data = JSON.readTree(bytes);
        if (!data.isArray() || data.isEmpty() || data.size() >= 1000)
            throw new IllegalArgumentException("资金费率响应为空、失败或达到截断上限");
        Instant lower = from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                upper = through.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                previous = null;
        var rows = new ArrayList<Map<String, Object>>();
        for (var event : data) {
            if (!event.path("symbol").asText().equals("BTCUSDT")
                    || !event.path("fundingTime").isIntegralNumber()
                    || event.has("rateType") && !event.path("rateType").asText().equals("Regular"))
                throw new IllegalArgumentException("币对、事件时间或资金费率类型不符");
            Instant time = Instant.ofEpochMilli(event.path("fundingTime").asLong());
            if (time.isBefore(lower)
                    || !time.isBefore(upper)
                    || previous != null && !time.isAfter(previous))
                throw new IllegalArgumentException("资金费率事件越界、重复或未升序");
            double rate = new BigDecimal(event.path("fundingRate").asText()).doubleValue();
            if (!Double.isFinite(rate)) throw new IllegalArgumentException("资金费率不是有限值");
            var row = new LinkedHashMap<String, Object>();
            row.put("funding_time", time.toString());
            row.put("exchange", "BINANCE");
            row.put("symbol", "BTCUSDT");
            row.put("instrument", "USDT_PERPETUAL");
            row.put("funding_rate", rate);
            row.put("funding_interval_hours", null);
            row.put("source_sha256", sha);
            row.put("observed_at", observed.toString());
            rows.add(Collections.unmodifiableMap(row));
            previous = time;
        }
        return List.copyOf(rows);
    }
}
