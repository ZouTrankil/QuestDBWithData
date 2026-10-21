package com.zoutrankil.market.btc.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.marketdata.CryptoPriceMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 日归档缺失时显式保留 REST 双频原件；不能将 JSON 冒充带官方校验和的归档。 */
final class BinanceFrozenRestDay {
    static final String FORMAT = "BINANCE_REST_DAY_V1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceFrozenRestDay() {}

    static boolean supports(String proof) throws Exception {
        return JSON.readTree(proof).path("sourceFormat").asText().equals(FORMAT);
    }

    static List<Map<String, Object>> read(
            Path root,
            byte[] hourly,
            String sha,
            Instant observed,
            String symbol,
            String instrument,
            LocalDate from,
            LocalDate through,
            String proof)
            throws Exception {
        var evidence = JSON.readTree(proof);
        if (!supports(proof)
                || !from.equals(through)
                || !Set.of("SPOT", "USDT_PERPETUAL").contains(instrument)
                || !evidence.path("requestedFrom").asText().equals(from.toString())
                || !evidence.path("requestedThrough").asText().equals(through.toString())
                || !VerifiedMarketDataStore.sha(hourly).equals(sha))
            throw new IllegalArgumentException("REST 单日原件或冻结范围不符");
        requireUrl(evidence.path("sourceUrl").asText(), symbol, instrument, from, "1h");
        var nativeSource = evidence.path("nativeDaily");
        requireUrl(nativeSource.path("sourceUrl").asText(), symbol, instrument, from, "1d");
        Path nativePath = Path.of(nativeSource.path("raw").asText()).toRealPath();
        if (!nativePath.startsWith(root.resolve("raw/binance").toRealPath()))
            throw new IllegalArgumentException("原生日线必须位于本来源的原件目录");
        byte[] nativeBytes = Files.readAllBytes(nativePath);
        String nativeSha = nativeSource.path("sha256").asText();
        if (!VerifiedMarketDataStore.sha(nativeBytes).equals(nativeSha))
            throw new IllegalArgumentException("REST 原生日线摘要变化");
        var rows = parse(hourly, symbol, instrument, sha, observed, from, 60);
        var daily =
                parse(
                        nativeBytes,
                        symbol,
                        instrument,
                        nativeSha,
                        Instant.parse(nativeSource.path("observedAt").asText()),
                        from,
                        1440);
        // 来源完整网格及独立日线均通过后，才交给原发布协议；不放宽停市或缺失门槛。
        CryptoPriceMapper.reconcileNativeDaily(rows, daily);
        return rows;
    }

    private static List<Map<String, Object>> parse(
            byte[] bytes,
            String symbol,
            String instrument,
            String sha,
            Instant observed,
            LocalDate day,
            int minutes)
            throws Exception {
        JsonNode source = JSON.readTree(bytes);
        if (!source.isArray() || source.size() != 1440 / minutes)
            throw new IllegalArgumentException("REST 单日根数不完整");
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : source) {
            if (!item.isArray()
                    || item.size() != 12
                    || !item.get(0).isIntegralNumber()
                    || !item.get(6).isIntegralNumber()
                    || item.get(6).asLong() != item.get(0).asLong() + minutes * 60000L - 1)
                throw new IllegalArgumentException("REST 字段或毫秒收盘边界不符");
            var numbers = new ArrayList<String>();
            for (int index : List.of(1, 2, 3, 4, 5, 7)) numbers.add(item.get(index).asText());
            rows.add(
                    CryptoPriceMapper.row(
                            Instant.ofEpochMilli(item.get(0).asLong()),
                            "BINANCE",
                            symbol,
                            instrument,
                            minutes,
                            numbers,
                            sha,
                            observed));
        }
        CryptoPriceMapper.requireContinuous(rows, day, day, minutes);
        return rows;
    }

    private static void requireUrl(
            String source, String symbol, String instrument, LocalDate day, String interval) {
        var uri = URI.create(source);
        boolean spot = instrument.equals("SPOT");
        if (!"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getPort() != -1
                || !(spot
                        ? Set.of("data-api.binance.vision", "api.binance.com")
                                .contains(uri.getHost())
                        : "fapi.binance.com".equals(uri.getHost()))
                || !(spot ? "/api/v3/klines" : "/fapi/v1/klines").equals(uri.getPath()))
            throw new IllegalArgumentException("REST 来源不是对应产品的官方 K 线接口");
        var query = new HashMap<String, String>();
        for (String pair : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2
                    || query.put(
                                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                            != null) throw new IllegalArgumentException("REST 参数缺失或重复");
        }
        long first = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        if (!query.keySet().equals(Set.of("symbol", "interval", "startTime", "endTime", "limit"))
                || !symbol.equals(query.get("symbol"))
                || !interval.equals(query.get("interval"))
                || !Long.toString(first).equals(query.get("startTime"))
                || !Long.toString(first + 86400000L - 1).equals(query.get("endTime"))
                || Integer.parseInt(query.get("limit")) < 24
                || Integer.parseInt(query.get("limit")) > 1000)
            throw new IllegalArgumentException("REST 产品、频率或 UTC 请求边界不符");
    }
}
