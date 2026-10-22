package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.CboeIndexDaily;
import com.zoutrankil.market.us.marketdata.FredMarketDaily;
import com.zoutrankil.market.us.marketdata.NasdaqItchDataset;
import com.zoutrankil.market.us.marketdata.NasdaqItchMapper;
import com.zoutrankil.market.us.marketdata.TiingoPriceMapper;
import com.zoutrankil.market.us.marketdata.UsEquitySessionCalendar;
import com.zoutrankil.market.us.marketdata.UsFundHoldings;
import com.zoutrankil.market.us.marketdata.UsHistoricalSecurityCatalog;
import com.zoutrankil.market.us.marketdata.UsMarketDatasets;
import com.zoutrankil.market.us.marketdata.UsSecurityIdentifiers;
import com.zoutrankil.market.us.marketdata.UsUniverseSnapshot;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立有限采集入口；不加载 A 股 API、Quartz、策略或实时 L2 组件。 */
public final class UsMarketDataMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 0)
            throw new IllegalArgumentException(
                    "命令：definitions|status|collect-core|import-tiingo-daily|import-tiingo-minute|collect-cboe-vix|import-cboe-vix");
        var options = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i += 2)
            if (!args[i].startsWith("--")
                    || i + 1 >= args.length
                    || options.putIfAbsent(args[i].substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException("参数必须成对且不能重复");
        var env = ProjectConfiguration.load("us-data");
        var root = Path.of(env.getRequiredProperty("app.market-data.root"));
        var store =
                new VerifiedMarketDataStore(
                        URI.create(env.getRequiredProperty("app.questdb.http-url")),
                        env.getProperty("app.questdb.http-authorization", ""),
                        env.getProperty("app.questdb.username", "admin"),
                        env.getProperty("app.questdb.password", ""),
                        root);
        switch (args[0]) {
            case "collect-cboe-vix", "import-cboe-vix" -> {
                boolean imported = args[0].equals("import-cboe-vix");
                if (!(imported ? Set.of("through", "source") : Set.of("through"))
                        .containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知 CBOE 参数");
                print(
                        CboeIndexCollector.collect(
                                env,
                                root,
                                store,
                                LocalDate.parse(required(options, "through")),
                                imported ? Path.of(required(options, "source")) : null));
            }
            case "create-regular-view" -> {
                if (!Set.of("calendar-source", "hours-source").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知交易日历参数");
                print(
                        UsRegularSessionView.install(
                                root,
                                store,
                                Path.of(required(options, "calendar-source")),
                                Path.of(required(options, "hours-source"))));
            }
            case "collect-fred" -> {
                if (!Set.of("through").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知 FRED 参数");
                LocalDate through = LocalDate.parse(required(options, "through"));
                if (!through.isBefore(LocalDate.now(ZoneId.of("America/New_York"))))
                    throw new IllegalArgumentException("FRED 有限回补只使用已结束的纽约日期");
                FredMacroCollector.collect(env, root, store, through);
            }
            case "reconcile-complete-frozen" -> {
                if (!Set.of("run", "prepared-sha256", "stopped-evidence")
                        .containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知只读核对参数");
                print(
                        store.reconcileCompleteFrozen(
                                required(options, "run"),
                                required(options, "prepared-sha256"),
                                Path.of(required(options, "stopped-evidence"))));
            }
            case "reconcile-frozen-tail" -> {
                if (!Set.of("run", "prepared-sha256", "stopped-evidence", "append-unsubmitted")
                                .containsAll(options.keySet())
                        || !"true".equals(options.get("append-unsubmitted")))
                    throw new IllegalArgumentException("续接尾部必须显式指定 append-unsubmitted=true");
                print(
                        store.reconcileFrozenTail(
                                required(options, "run"),
                                required(options, "prepared-sha256"),
                                Path.of(required(options, "stopped-evidence")),
                                true));
            }
            case "reconcile-frozen" -> {
                if (!Set.of("run", "prepared-sha256", "stopped-evidence", "repair-missing")
                                .containsAll(options.keySet())
                        || !Set.of("true", "false")
                                .contains(options.getOrDefault("repair-missing", "false")))
                    throw new IllegalArgumentException("未知核对参数或补缺标志");
                print(
                        store.reconcileFrozen(
                                required(options, "run"),
                                required(options, "prepared-sha256"),
                                Path.of(required(options, "stopped-evidence")),
                                Boolean.parseBoolean(
                                        options.getOrDefault("repair-missing", "false"))));
            }
            case "reconcile-core-task" -> {
                if (!Set.of("task", "run").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知队列核对参数");
                new UsCoreCollector(env, root, store)
                        .reconcileTask(required(options, "task"), required(options, "run"));
            }
            case "reconcile-broad-task" -> {
                if (!Set.of("task", "run", "through").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知队列核对参数");
                new UsBroadDailyCollector(env, root, store)
                        .reconcileTask(
                                required(options, "task"),
                                required(options, "run"),
                                LocalDate.parse(required(options, "through")));
            }
            case "reconcile-broad-bundle" -> {
                if (!Set.of("bundle", "run").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知来源组核对参数");
                new UsBroadDailyCollector(env, root, store)
                        .reconcileBundle(required(options, "bundle"), required(options, "run"));
            }
            case "reconcile-broad-window" -> {
                if (!Set.of("bundle", "window", "run").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知年度窗口核对参数");
                new UsBroadDailyCollector(env, root, store)
                        .reconcileWindow(
                                required(options, "bundle"),
                                Integer.parseInt(required(options, "window")),
                                required(options, "run"));
            }
            case "import-fund-holdings" -> {
                if (!Set.of("source", "fund").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知基金持仓参数");
                Path raw = Path.of(required(options, "source"));
                byte[] bytes = Files.readAllBytes(raw);
                String sha = VerifiedMarketDataStore.sha(bytes), fund = required(options, "fund");
                Instant observed =
                        Files.getLastModifiedTime(raw).toInstant().truncatedTo(ChronoUnit.MICROS);
                var rows =
                        switch (fund) {
                            case "SPY" -> UsFundHoldings.spy(bytes, sha, observed);
                            case "QQQ" -> UsFundHoldings.qqq(bytes, sha, observed);
                            default -> throw new IllegalArgumentException("只支持 SPY/QQQ 发行人持仓");
                        };
                print(
                        store.publish(
                                UsFundHoldings.EQUITIES,
                                rows,
                                raw,
                                sha,
                                Map.of(
                                        "fund",
                                        fund,
                                        "membershipBasis",
                                        "ETF_HOLDINGS_PROXY",
                                        "scope",
                                        "发行人当前股票持仓；不是官方历史指数成分")));
            }
            case "import-itch" -> {
                if (!Set.of("source", "metadata", "first-message", "message-count")
                        .containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知 ITCH 参数");
                Path raw = Path.of(required(options, "source")),
                        metadata = Path.of(required(options, "metadata"));
                byte[] bytes = Files.readAllBytes(raw);
                var card = JSON.readTree(Files.readAllBytes(metadata));
                String sha = VerifiedMarketDataStore.sha(bytes);
                if (!sha.equals(card.path("compressedSha256").asText())
                        || card.path("sourceComplete").asBoolean(true)
                        || card.path("compressedPrefixBytes").asLong() != bytes.length)
                    throw new IllegalArgumentException("ITCH 前缀元数据或摘要不符");
                long first = Long.parseLong(required(options, "first-message"));
                int count = Integer.parseInt(required(options, "message-count"));
                Instant observed =
                        Instant.parse(card.path("observedAt").asText())
                                .truncatedTo(ChronoUnit.MICROS);
                var rows =
                        NasdaqItchMapper.messages(
                                new ByteArrayInputStream(bytes),
                                LocalDate.parse(card.path("fileDate").asText()),
                                first,
                                count,
                                sha,
                                observed);
                print(
                        store.publish(
                                NasdaqItchDataset.MESSAGES,
                                rows,
                                raw,
                                sha,
                                Map.of(
                                        "sourceMetadata",
                                        card,
                                        "metadataSha256",
                                        VerifiedMarketDataStore.sha(Files.readAllBytes(metadata)),
                                        "firstMessage",
                                        first,
                                        "messageCount",
                                        count,
                                        "sourceComplete",
                                        false)));
            }
            case "reconcile-undelivered" -> {
                if (!Set.of("run", "prepared-sha256", "stopped-evidence")
                        .containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知核对参数");
                print(
                        store.reconcileUndelivered(
                                required(options, "run"),
                                required(options, "prepared-sha256"),
                                Path.of(required(options, "stopped-evidence"))));
            }
            case "collect-core", "collect-broad" -> {
                if (!Set.of("through", "maximum-jobs").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知采集参数");
                if (args[0].equals("collect-broad") && options.containsKey("maximum-jobs"))
                    throw new IllegalArgumentException("maximum-jobs 仅适用于核心队列，不能在挂牌队列中静默忽略");
                LocalDate through = LocalDate.parse(required(options, "through"));
                var ny = ZonedDateTime.now(ZoneId.of("America/New_York"));
                var ceiling =
                        ny.toLocalTime().isBefore(LocalTime.of(20, 30))
                                ? ny.toLocalDate().minusDays(1)
                                : ny.toLocalDate();
                if (through.isAfter(ceiling)) throw new IllegalArgumentException("来源日期尚未完成");
                int maximum = Integer.parseInt(options.getOrDefault("maximum-jobs", "100000"));
                if (maximum < 1 || maximum > 100000)
                    throw new IllegalArgumentException("明确的任务上限必须在 1 到 100000 之间");
                if (args[0].equals("collect-core"))
                    new UsCoreCollector(env, root, store).run(through, maximum);
                else new UsBroadDailyCollector(env, root, store).run(through);
            }
            case "import-security-identifiers" -> {
                if (!Set.of("source").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知永久代码映射参数");
                print(
                        UsSecurityIdentifierImporter.publish(
                                root,
                                store,
                                Path.of(required(options, "source")).toAbsolutePath().normalize()));
            }
            case "definitions" ->
                    print(
                            List.of(
                                    UsMarketDatasets.DAILY,
                                    UsMarketDatasets.MINUTE,
                                    UsEquitySessionCalendar.REGULAR,
                                    UsUniverseSnapshot.SECURITIES,
                                    UsHistoricalSecurityCatalog.SECURITIES,
                                    UsSecurityIdentifiers.IDENTIFIERS,
                                    UsFundHoldings.EQUITIES,
                                    NasdaqItchDataset.MESSAGES,
                                    CboeIndexDaily.DEFINITION,
                                    FredMarketDaily.DEFINITION));
            case "status" -> {
                var result = new ArrayList<Object>();
                for (var d :
                        List.of(
                                UsMarketDatasets.DAILY,
                                UsMarketDatasets.MINUTE,
                                CboeIndexDaily.DEFINITION)) {
                    var exists =
                            store.query(
                                    "SELECT table_name FROM tables() WHERE table_name="
                                            + VerifiedMarketDataStore.literal(d.objectName()));
                    result.add(
                            exists.isEmpty()
                                    ? Map.of("dataset", d.datasetId(), "exists", false)
                                    : Map.of(
                                            "dataset",
                                            d.datasetId(),
                                            "walSettled",
                                            store.settled(d.objectName()),
                                            "coverage",
                                            store.query(
                                                    "SELECT provider,symbol,count() rows,min("
                                                            + VerifiedMarketDataStore.q(
                                                                    d.designatedTimestamp())
                                                            + ") first_ts,max("
                                                            + VerifiedMarketDataStore.q(
                                                                    d.designatedTimestamp())
                                                            + ") last_ts FROM "
                                                            + VerifiedMarketDataStore.q(
                                                                    d.objectName())
                                                            + " GROUP BY provider,symbol ORDER BY provider,symbol")));
                }
                print(result);
            }
            case "import-tiingo-daily", "import-tiingo-minute" -> {
                if (!Set.of("source", "symbol", "through", "from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("未知导入参数");
                var raw = Path.of(required(options, "source")).toAbsolutePath().normalize();
                byte[] bytes = Files.readAllBytes(raw);
                String sha = VerifiedMarketDataStore.sha(bytes),
                        symbol = required(options, "symbol");
                // 本地冻结响应文件的写完时刻是本次可用时间，不伪造历史当时可见的证据。
                Instant observed =
                        Files.getLastModifiedTime(raw).toInstant().truncatedTo(ChronoUnit.MICROS);
                var ny = ZonedDateTime.now(ZoneId.of("America/New_York"));
                LocalDate ceiling =
                        ny.toLocalTime().isBefore(LocalTime.of(20, 30))
                                ? ny.toLocalDate().minusDays(1)
                                : ny.toLocalDate();
                LocalDate through =
                        LocalDate.parse(options.getOrDefault("through", ceiling.toString()));
                if (through.isAfter(ceiling)) throw new IllegalArgumentException("不能导入尚未完成的纽约交易日");
                var evidence =
                        Map.<String, Object>of(
                                "provider",
                                "TIINGO",
                                "symbol",
                                symbol,
                                "rawSha256",
                                sha,
                                "rawPath",
                                raw.toString(),
                                "observedAt",
                                observed.toString(),
                                "through",
                                through.toString(),
                                "coverageScope",
                                "仅核验冻结来源的完整业务键和值，不证明全市场覆盖");
                if (args[0].equals("import-tiingo-daily")) {
                    var mapped =
                            TiingoPriceMapper.dailyMapping(bytes, symbol, sha, observed, through);
                    var dailyEvidence = new LinkedHashMap<>(evidence);
                    dailyEvidence.put("cashDividendPolicy", TiingoPriceMapper.CASH_DIVIDEND_POLICY);
                    dailyEvidence.put("nonCashDistributions", mapped.nonCashDistributions());
                    print(
                            store.publish(
                                    UsMarketDatasets.DAILY,
                                    mapped.rows(),
                                    raw,
                                    sha,
                                    dailyEvidence));
                } else {
                    LocalDate from = LocalDate.parse(required(options, "from"));
                    var minute =
                            TiingoPriceMapper.minute(bytes, symbol, sha, observed, from, through);
                    print(store.publish(UsMarketDatasets.MINUTE, minute, raw, sha, evidence));
                    print(
                            store.publish(
                                    UsMarketDatasets.MINUTE,
                                    TiingoPriceMapper.fiveMinute(minute),
                                    raw,
                                    sha,
                                    evidence));
                }
            }
            default -> throw new IllegalArgumentException("未知美股采集命令");
        }
    }

    private static String required(Map<String, String> options, String name) {
        var value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --" + name);
        return value;
    }

    private static void print(Object value) throws Exception {
        System.out.println(JSON.writeValueAsString(value));
    }
}
