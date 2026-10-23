package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveMapper;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 只冻结质量问题涉及的官方来源，不创建正式写入意图或改变原队列。 */
public final class BinanceQualitySourceReview {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BinanceQualitySourceReview() {}

    public static Path run(Path root, BinanceArchiveSource source, Path plan) throws Exception {
        var bytes = Files.readAllBytes(plan);
        var requests = validatePlan(JSON.readTree(bytes));
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("archive-queue.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("原归档任务或备份正在运行");
            var results = new ArrayList<Object>();
            var monthly = new HashMap<String, Object>();
            var report = new LinkedHashMap<String, Object>();
            report.put("schemaVersion", 1);
            report.put("planSha256", VerifiedMarketDataStore.sha(bytes));
            report.put("startedAt", Instant.now().toString());
            report.put("formalWrite", false);
            Path folder = root.resolve("quality-source-reviews");
            Files.createDirectories(folder);
            Path target = folder.resolve(UUID.randomUUID() + ".json");
            for (var request : requests) {
                var dataset = BinanceArchiveDataset.parse(request.path("dataset").asText());
                var day = LocalDate.parse(request.path("day").asText());
                var item = new LinkedHashMap<String, Object>();
                item.put("dataset", dataset.id());
                item.put("day", day.toString());
                var month = YearMonth.from(day);
                String key = dataset.id() + "/" + month;
                if (!monthly.containsKey(key))
                    monthly.put(
                            key,
                            archive(
                                    source,
                                    dataset,
                                    month.atDay(1),
                                    month.atEndOfMonth(),
                                    dataset.monthly(month)));
                item.put("monthly", monthly.get(key));
                item.put("daily", archive(source, dataset, day, day, dataset.daily(day)));
                item.put("rest", rest(source, dataset, day));
                if (request.path("minuteCheck").asBoolean(false)) {
                    // 分钟原件单独冻结，不将其冒充已经验收的小时或日线产物。
                    URI uri =
                            URI.create(
                                    "https://data.binance.vision/data/spot/daily/klines/BTCUSDT/1m/BTCUSDT-1m-"
                                            + day
                                            + ".zip");
                    item.put("minutes", minuteArchive(source, uri));
                }
                if (request.path("aggregateTradesCheck").asBoolean(false)) {
                    // 先保留带官方校验和的成交原件；尚未核验成交连续性，不生成替代 K 线。
                    var trades = new LinkedHashMap<String, Object>();
                    try {
                        var raw = source.fetchAggregateTrades(day);
                        trades.put("url", raw.url());
                        evidence(trades, raw.raw(), raw.sha256(), raw.observedAt());
                        trades.put("checksum", raw.checksum().toString());
                        trades.put("checksumSha256", raw.checksumSha256());
                        trades.put("status", "FROZEN_NOT_YET_VALIDATED");
                    } catch (PersistentMarketApiBudget.Exhausted e) {
                        throw e;
                    } catch (Exception e) {
                        failure(trades, e);
                    }
                    item.put("aggregateTrades", trades);
                }
                results.add(item);
                report.put("results", results);
                report.put("finishedAt", Instant.now().toString());
                // 每个日期写独立检查点，外部中断也不丢已完成证据；不推进正式检查点。
                Path progress = target.resolveSibling(target.getFileName() + ".pending");
                JSON.writerWithDefaultPrettyPrinter().writeValue(progress.toFile(), report);
                Files.move(
                        progress,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        }
    }

    public static List<JsonNode> validatePlan(JsonNode plan) {
        if (plan.path("schemaVersion").asInt() != 1
                || !plan.path("batchId").asText().matches("[a-f0-9]{64}")
                || !plan.path("requests").isArray()
                || plan.path("requests").isEmpty()
                || plan.path("requests").size() > 64)
            throw new IllegalArgumentException("质量核对计划版本、批次或日期预算不符");
        var requests = new ArrayList<JsonNode>();
        var keys = new HashSet<String>();
        for (var request : plan.path("requests")) {
            var dataset = BinanceArchiveDataset.parse(request.path("dataset").asText());
            var day = LocalDate.parse(request.path("day").asText());
            // 2020 年首笔结算依赖前一小时；只给这一天的四侧小时价开放原件读取。
            boolean boundaryDependency = request.path("fundingBoundaryDependency").asBoolean(false);
            boolean validBoundaryDependency =
                    boundaryDependency
                            && day.equals(LocalDate.of(2019, 12, 31))
                            && List.of(
                                            BinanceArchiveDataset.SPOT_1H,
                                            BinanceArchiveDataset.PERP_1H,
                                            BinanceArchiveDataset.MARK_1H,
                                            BinanceArchiveDataset.INDEX_1H)
                                    .contains(dataset)
                            && !request.path("minuteCheck").asBoolean(false)
                            && !request.path("aggregateTradesCheck").asBoolean(false);
            if (dataset == BinanceArchiveDataset.FUNDING
                    || !day.isBefore(LocalDate.now(ZoneOffset.UTC))
                    || boundaryDependency && !validBoundaryDependency
                    || day.isBefore(LocalDate.of(2020, 1, 1)) && !validBoundaryDependency
                    || !keys.add(dataset.id() + "/" + day)
                    || (request.path("minuteCheck").asBoolean(false)
                                    || request.path("aggregateTradesCheck").asBoolean(false))
                            && dataset != BinanceArchiveDataset.SPOT_1H)
                throw new IllegalArgumentException("核对只接受已结束日期、唯一 K 线请求及现货小时的分钟或成交依赖");
            requests.add(request);
        }
        return requests;
    }

    private static Map<String, Object> archive(
            BinanceArchiveSource source,
            BinanceArchiveDataset dataset,
            LocalDate from,
            LocalDate through,
            URI uri)
            throws Exception {
        var item = new LinkedHashMap<String, Object>();
        item.put("url", uri.toString());
        try {
            var raw = source.fetch(uri, false);
            evidence(item, raw.raw(), raw.sha256(), raw.observedAt());
            item.put("checksum", raw.checksum().toString());
            item.put("checksumSha256", raw.checksumSha256());
            item.put(
                    "coverage",
                    BinanceArchiveMapper.inspect(
                            dataset,
                            Files.readAllBytes(raw.raw()),
                            from,
                            through,
                            raw.sha256(),
                            raw.observedAt()));
            item.put("status", "PARSED");
        } catch (PersistentMarketApiBudget.Exhausted e) {
            throw e;
        } catch (Exception e) {
            failure(item, e);
        }
        return item;
    }

    private static Map<String, Object> minuteArchive(BinanceArchiveSource source, URI uri)
            throws Exception {
        var item = new LinkedHashMap<String, Object>();
        item.put("url", uri.toString());
        try {
            var raw = source.fetch(uri, false);
            evidence(item, raw.raw(), raw.sha256(), raw.observedAt());
            item.put("checksum", raw.checksum().toString());
            item.put("checksumSha256", raw.checksumSha256());
            item.put("status", "FROZEN_NOT_YET_VALIDATED");
        } catch (PersistentMarketApiBudget.Exhausted e) {
            throw e;
        } catch (Exception e) {
            failure(item, e);
        }
        return item;
    }

    public static URI restUri(BinanceArchiveDataset dataset, LocalDate day) {
        String endpoint =
                switch (dataset) {
                    case SPOT_1D, SPOT_1H -> "https://data-api.binance.vision/api/v3/klines";
                    case PERP_1H -> "https://fapi.binance.com/fapi/v1/klines";
                    case MARK_1H -> "https://fapi.binance.com/fapi/v1/markPriceKlines";
                    case INDEX_1H -> "https://fapi.binance.com/fapi/v1/indexPriceKlines";
                    default -> throw new IllegalArgumentException("本轮只核对 K 线");
                };
        long start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return URI.create(
                endpoint
                        + (dataset == BinanceArchiveDataset.INDEX_1H ? "?pair=" : "?symbol=")
                        + "BTCUSDT&interval="
                        + dataset.interval()
                        + "&startTime="
                        + start
                        + "&endTime="
                        + (start + 86400000L - 1)
                        + "&limit=1000");
    }

    private static Map<String, Object> rest(
            BinanceArchiveSource source, BinanceArchiveDataset dataset, LocalDate day)
            throws Exception {
        var item = new LinkedHashMap<String, Object>();
        URI uri = restUri(dataset, day);
        item.put("url", uri.toString());
        try {
            var raw = source.fetchKlines(uri);
            evidence(item, raw.path(), raw.sha256(), raw.observedAt());
            item.put("sourceFormat", "BINANCE_REST_KLINES_JSON");
            var projection = restProjection(dataset, day, Files.readAllBytes(raw.path()));
            item.put(
                    "coverage",
                    BinanceArchiveMapper.inspect(
                            dataset, projection, day, day, raw.sha256(), raw.observedAt()));
            item.put("status", "PARSED");
        } catch (PersistentMarketApiBudget.Exhausted e) {
            throw e;
        } catch (Exception e) {
            failure(item, e);
        }
        return item;
    }

    public static byte[] restProjection(BinanceArchiveDataset dataset, LocalDate day, byte[] bytes)
            throws Exception {
        var rows = JSON.readTree(bytes);
        if (!rows.isArray()
                || rows.isEmpty()
                || rows.size() > (dataset == BinanceArchiveDataset.SPOT_1D ? 1 : 24))
            throw new IllegalArgumentException("REST 单日响应为空或超限");
        var csv = new StringBuilder();
        for (var row : rows) {
            if (!row.isArray()
                    || row.size() != 12
                    || !row.get(0).isIntegralNumber()
                    || !row.get(6).isIntegralNumber())
                throw new IllegalArgumentException("REST K 线字段数量或毫秒时间类型不符");
            for (int i = 0; i < 12; i++) {
                if (i > 0) csv.append(',');
                String value = row.get(i).asText();
                if (!value.matches("[0-9]+(?:\\.[0-9]+)?"))
                    throw new IllegalArgumentException("REST 数值格式不符");
                // REST 为毫秒，现货归档自 2025 年起为微秒；仅转换投影，不改原响应。
                if ((i == 0 || i == 6) && dataset.spot() && !day.isBefore(LocalDate.of(2025, 1, 1)))
                    value = Long.toString(Math.multiplyExact(row.get(i).longValue(), 1000L));
                csv.append(value);
            }
            csv.append('\n');
        }
        var result = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(result)) {
            zip.putNextEntry(new ZipEntry("rest-projection.csv"));
            zip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return result.toByteArray();
    }

    private static void evidence(Map<String, Object> item, Path raw, String sha, Instant observed) {
        item.put("raw", raw.toAbsolutePath().toString());
        item.put("sha256", sha);
        item.put("observedAt", observed.toString());
    }

    private static void failure(Map<String, Object> item, Exception error) {
        item.put("status", "SOURCE_OR_VALIDATION_FAILED");
        item.put("errorType", error.getClass().getSimpleName());
        item.put(
                "reason",
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
