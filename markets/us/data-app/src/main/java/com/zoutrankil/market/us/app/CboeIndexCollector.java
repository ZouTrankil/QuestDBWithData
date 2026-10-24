package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.CboeIndexDaily;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient.Version;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 独立 CBOE 收盘参照，只补确认缺失的键；旧值修订与未决运行均不自动覆盖或重发。 */
final class CboeIndexCollector {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CboeIndexCollector() {}

    static PersistentMarketApiBudget.Policy policy(Environment env) {
        return new PersistentMarketApiBudget.Policy(
                env.getRequiredProperty("app.market-data.cboe-requests-per-hour", Integer.class),
                env.getRequiredProperty("app.market-data.cboe-requests-per-day", Integer.class),
                1,
                env.getRequiredProperty("app.market-data.cboe-monthly-bytes", Long.class),
                env.getRequiredProperty("app.market-data.cboe-request-spacing-ms", Long.class));
    }

    static URI sourceUri(Environment env) {
        URI uri = URI.create(env.getRequiredProperty("app.market-data.cboe-vix-url"));
        if (!"https".equals(uri.getScheme())
                || !List.of("cdn-api.cboe.com", "cdn.cboe.com").contains(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !"/api/global/us_indices/daily_prices/VIX_History.csv".equals(uri.getPath()))
            throw new IllegalArgumentException("须指定不含凭据的官方 CBOE VIX 历史地址");
        return uri;
    }

    static void requireClosed(LocalDate through, Clock clock) {
        if (!through.isBefore(LocalDate.now(clock.withZone(ZoneId.of("America/New_York")))))
            throw new IllegalArgumentException("指数只回补已结束的纽约日期");
    }

    static Object collect(
            Environment env,
            Path root,
            VerifiedMarketDataStore store,
            LocalDate through,
            Path frozenReceipt)
            throws Exception {
        requireClosed(through, Clock.systemUTC());
        LocalDate from = LocalDate.parse(env.getRequiredProperty("app.market-data.history-start"));
        if (through.isBefore(from)) throw new IllegalArgumentException("指数窗口倒置");
        root = root.toAbsolutePath().normalize();
        URI uri = sourceUri(env);
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("cboe-vix-collector.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有 CBOE 指数采集器运行");
            // 换新原件也不能绕过旧意图；先固定恢复责任，再消耗来源预算。
            store.requireNoUnresolved(CboeIndexDaily.DEFINITION.datasetId());
            requireNoPendingIntent(root);
            Path raw;
            String sha;
            Instant observed;
            if (frozenReceipt == null) {
                try (var budget =
                                new PersistentMarketApiBudget(
                                        root.resolve("api-budget.sqlite3"),
                                        Map.of("cboe", policy(env)));
                        var source =
                                new FrozenHttpSource(
                                        root,
                                        budget,
                                        env.getProperty("app.market-data.proxy-url", ""))) {
                    var response =
                            source.get(
                                    "cboe",
                                    "public",
                                    CboeIndexDaily.SYMBOL,
                                    uri,
                                    Map.of(
                                            "Accept",
                                            "text/csv",
                                            "Accept-Encoding",
                                            "identity",
                                            "User-Agent",
                                            "us-market-data/0.1.0"),
                                    4 * 1024 * 1024,
                                    Version.HTTP_1_1);
                    raw = response.path();
                    sha = response.sha256();
                    observed = response.observedAt();
                }
            } else {
                // 复用取得时间和原摘要，不因导入而伪造一次新的来源观察。
                var receipt = JSON.readTree(Files.readAllBytes(frozenReceipt));
                if (!receipt.path("retrieved").asBoolean()
                        || !CboeIndexDaily.SYMBOL.equals(receipt.path("symbol").asText())
                        || !CboeIndexDaily.PROVIDER.equals(receipt.path("provider").asText())
                        || !uri.toString().equals(receipt.path("url").asText()))
                    throw new IllegalArgumentException("原来源回执、代码或公开地址不符");
                raw = Path.of(receipt.required("raw").asText()).toAbsolutePath().normalize();
                sha = receipt.required("sha256").asText();
                observed = Instant.parse(receipt.required("observedAt").asText());
            }
            if (Files.size(raw) > 4L * 1024 * 1024
                    || observed.isAfter(Instant.now().plusSeconds(5)))
                throw new IllegalArgumentException("原件大小或取得时间不符");
            byte[] bytes = Files.readAllBytes(raw);
            if (!VerifiedMarketDataStore.sha(bytes).equals(sha))
                throw new IllegalStateException("CBOE 冻结原件摘要变化");
            var parsed = CboeIndexDaily.parse(bytes, sha, observed, from, through);
            var d = CboeIndexDaily.DEFINITION;
            store.requireNoUnresolved(d.datasetId());
            store.ensure(d);
            var target =
                    store.query(
                            "SELECT id,directoryName FROM tables() WHERE table_name='us_index_daily'");
            if (!store.settled(d.objectName()))
                throw new IllegalStateException("原指数 WAL 未结清，先核对原运行");
            String sql =
                    "SELECT * FROM us_index_daily WHERE provider='CBOE' AND symbol='VIX'"
                            + " AND trade_date>='"
                            + from
                            + "' AND trade_date<='"
                            + through
                            + "' ORDER BY trade_date,symbol,provider LIMIT 100001";
            var before =
                    store.query(sql).stream()
                            .map(row -> VerifiedMarketDataStore.normalize(d, row))
                            .toList();
            var sourceRows =
                    parsed.rows().stream()
                            .map(row -> VerifiedMarketDataStore.normalize(d, row))
                            .toList();
            var missing = missingRows(sourceRows, before);
            if (missing.isEmpty()) {
                var again =
                        store.query(sql).stream()
                                .map(row -> VerifiedMarketDataStore.normalize(d, row))
                                .toList();
                if (!before.equals(again)
                        || !store.settled(d.objectName())
                        || !target.equals(
                                store.query(
                                        "SELECT id,directoryName FROM tables() WHERE table_name='us_index_daily'"))
                        || !VerifiedMarketDataStore.sha(Files.readAllBytes(raw)).equals(sha))
                    throw new IllegalStateException("幂等回读期间来源、目标、旧值或 WAL 变化");
                return Map.of(
                        "state",
                        "NO_MISSING_KEYS",
                        "preservedRows",
                        before.size(),
                        "sourceSha256",
                        sha,
                        "sourceLastDate",
                        parsed.sourceLastDate().toString(),
                        "oldRowsUnchanged",
                        true,
                        "walSettled",
                        true);
            }
            String identity =
                    VerifiedMarketDataStore.sha(
                            (sha + "|" + from + "|" + through).getBytes(StandardCharsets.UTF_8));
            Path folder = root.resolve("cboe-index-publications/" + identity);
            Files.createDirectories(folder);
            if (Files.exists(folder.resolve("intent.json")))
                throw new IllegalStateException("原来源发布意图已存在，先核对原运行，不能重发");
            Files.write(
                    folder.resolve("before.json"),
                    JSON.writeValueAsBytes(before),
                    StandardOpenOption.CREATE_NEW);
            Files.write(
                    folder.resolve("intent.json"),
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "createdAt",
                                    Instant.now().toString(),
                                    "sourceSha256",
                                    sha,
                                    "sourceUrl",
                                    uri.toString(),
                                    "missingRows",
                                    missing.size(),
                                    "from",
                                    from.toString(),
                                    "through",
                                    through.toString(),
                                    "beforeSha256",
                                    VerifiedMarketDataStore.sha(
                                            Files.readAllBytes(folder.resolve("before.json"))))),
                    StandardOpenOption.CREATE_NEW);
            var result =
                    store.publishMissing(
                            d,
                            missing,
                            raw,
                            sha,
                            Map.of(
                                    "sourceUrl",
                                    uri.toString(),
                                    "sourceRows",
                                    parsed.sourceRows(),
                                    "sourceFirstDate",
                                    parsed.sourceFirstDate().toString(),
                                    "sourceLastDate",
                                    parsed.sourceLastDate().toString(),
                                    "from",
                                    from.toString(),
                                    "through",
                                    through.toString(),
                                    "before",
                                    folder.resolve("before.json").toString(),
                                    "scope",
                                    "官方指数收盘点位；不声明原 CSV OHLC 一致，不覆盖 FRED"));
            var after =
                    store.query(sql).stream()
                            .map(row -> VerifiedMarketDataStore.normalize(d, row))
                            .toList();
            if (!missingRows(sourceRows, after).isEmpty()
                    || !store.settled(d.objectName())
                    || !target.equals(
                            store.query(
                                    "SELECT id,directoryName FROM tables() WHERE table_name='us_index_daily'")))
                throw new IllegalStateException("指数完整键值或 WAL 尚未验收");
            var codec = VerifiedMarketDataStore.codec(d);
            var indexed = new LinkedHashMap<String, Map<String, Object>>();
            for (var row : after) indexed.put(codec.key(row), row);
            for (var row : before)
                if (!Arrays.equals(
                        codec.canonicalBytes(row),
                        codec.canonicalBytes(indexed.get(codec.key(row)))))
                    throw new IllegalStateException("指数补缺改变了旧行，停止验收");
            var receipt =
                    Map.of(
                            "result",
                            result,
                            "newRows",
                            missing.size(),
                            "preservedRows",
                            before.size(),
                            "oldRowsUnchanged",
                            true,
                            "sourceSha256",
                            sha,
                            "walSettled",
                            true,
                            "frozenSourceReused",
                            frozenReceipt != null);
            Files.write(
                    folder.resolve("receipt.json"),
                    JSON.writeValueAsBytes(receipt),
                    StandardOpenOption.CREATE_NEW);
            return receipt;
        }
    }

    /** 原意图必须有完整成功回执；缺回执或半写入文件都先停下核对。 */
    static void requireNoPendingIntent(Path root) throws Exception {
        Path publications = root.resolve("cboe-index-publications");
        if (!Files.exists(publications)) return;
        try (var folders = Files.list(publications)) {
            for (Path folder : folders.filter(Files::isDirectory).toList()) {
                Path intent = folder.resolve("intent.json");
                if (!Files.exists(intent)) continue;
                Path receipt = folder.resolve("receipt.json");
                if (!Files.isRegularFile(receipt))
                    throw new IllegalStateException("原指数发布意图未完成，先核对原运行");
                var request = JSON.readTree(Files.readAllBytes(intent));
                var completed = JSON.readTree(Files.readAllBytes(receipt));
                if (!"VERIFIED".equals(completed.path("result").path("state").asText())
                        || !completed.path("oldRowsUnchanged").asBoolean()
                        || !completed.path("walSettled").asBoolean()
                        || !request.required("sourceSha256")
                                .asText()
                                .equals(completed.path("sourceSha256").asText())
                        || request.required("missingRows").asInt()
                                != completed.path("newRows").asInt(-1)
                        || request.required("missingRows").asInt()
                                != completed.path("result").path("verifiedRows").asInt(-1))
                    throw new IllegalStateException("原指数发布回执不完整，先核对原运行");
            }
        }
    }

    /** 旧价格修订或原日期在新源消失时停下；仅原文摘要及取得时刻变化不覆盖历史血缘。 */
    static List<Map<String, Object>> missingRows(
            List<Map<String, Object>> source, List<Map<String, Object>> before) {
        var d = CboeIndexDaily.DEFINITION;
        var codec = VerifiedMarketDataStore.codec(d);
        var expected = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : source)
            if (expected.put(codec.key(row), row) != null)
                throw new IllegalStateException("指数来源键重复");
        var old = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : before) {
            String key = codec.key(row);
            if (old.put(key, row) != null) throw new IllegalStateException("原指数键重复");
            var incoming = expected.get(key);
            if (incoming == null) throw new IllegalStateException("原指数日期在本次来源中消失，须复核来源范围");
            for (String field : List.of("trade_date", "symbol", "provider", "close", "value_unit"))
                if (!row.get(field).equals(incoming.get(field)))
                    throw new IllegalStateException("原指数业务值有修订，须单独核对，不自动覆盖");
        }
        return source.stream().filter(row -> !old.containsKey(codec.key(row))).toList();
    }
}
