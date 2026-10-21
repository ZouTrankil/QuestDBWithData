package com.zoutrankil.market.btc.archive.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveProbe;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRecovery;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRevisionApply;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveRevisionCheck;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveStatus;
import com.zoutrankil.market.btc.archive.application.BinanceArchiveSync;
import com.zoutrankil.market.btc.archive.application.BinanceFundingTailSync;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有限命令，不启动调度；原 collect 命令及其旧队列保持原行为。 */
public final class BinanceArchiveCommands {
    private BinanceArchiveCommands() {}

    public static boolean execute(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("apply-binance-revisions")) {
            if (args.length != 3 || !args[1].equals("--report"))
                throw new IllegalArgumentException(
                        "命令：apply-binance-revisions --report REPORT.json");
            var env = ProjectConfiguration.load("btc-data");
            var base = Path.of(env.getRequiredProperty("app.crypto-data.root"));
            var root = base.resolve("binance-vision");
            var policy =
                    new PersistentMarketApiBudget.Policy(
                            env.getProperty(
                                    "app.crypto-data.requests-per-hour", Integer.class, 2000),
                            env.getProperty(
                                    "app.crypto-data.requests-per-day", Integer.class, 20000),
                            10000,
                            env.getProperty(
                                    "app.crypto-data.bytes-per-31-days", Long.class, 5368709120L),
                            env.getProperty(
                                    "app.crypto-data.request-spacing-ms", Long.class, 400L));
            try (var budget =
                            new PersistentMarketApiBudget(
                                    base.resolve("api-budget.sqlite3"),
                                    Map.of("binance", policy, "bybit", policy));
                    var http =
                            new FrozenHttpSource(
                                    root,
                                    budget,
                                    env.getProperty("app.crypto-data.proxy-url", ""))) {
                var store =
                        new VerifiedMarketDataStore(
                                URI.create(env.getRequiredProperty("app.questdb.http-url")),
                                env.getProperty("app.questdb.http-authorization", ""),
                                env.getProperty("app.questdb.username", "admin"),
                                env.getProperty("app.questdb.password", ""),
                                base);
                System.out.println(
                        new ObjectMapper()
                                .writeValueAsString(
                                        BinanceArchiveRevisionApply.run(
                                                root,
                                                new BinanceArchiveSource(http, root),
                                                store,
                                                Path.of(args[2]))));
            }
            return true;
        }
        if (args.length > 0 && args[0].equals("reconcile-binance")) {
            if ((args.length != 3 && args.length != 4)
                    || !args[1].equals("--run")
                    || args.length == 4 && !args[3].equals("--repair-missing"))
                throw new IllegalArgumentException(
                        "命令：reconcile-binance --run RUN_ID [--repair-missing]");
            var env = ProjectConfiguration.load("btc-data");
            var base = Path.of(env.getRequiredProperty("app.crypto-data.root"));
            var store =
                    new VerifiedMarketDataStore(
                            URI.create(env.getRequiredProperty("app.questdb.http-url")),
                            env.getProperty("app.questdb.http-authorization", ""),
                            env.getProperty("app.questdb.username", "admin"),
                            env.getProperty("app.questdb.password", ""),
                            base);
            System.out.println(
                    new ObjectMapper()
                            .writeValueAsString(
                                    BinanceArchiveRecovery.reconcile(
                                            base.resolve("binance-vision"),
                                            store,
                                            args[2],
                                            args.length == 4)));
            return true;
        }
        if (args.length > 0 && args[0].equals("sync-binance")) {
            if (args.length != 1 && (args.length != 3 || !args[1].equals("--through")))
                throw new IllegalArgumentException("命令：sync-binance [--through YYYY-MM-DD]");
            var env = ProjectConfiguration.load("btc-data");
            String through =
                    args.length == 3
                            ? args[2]
                            : LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
            execute(
                    new String[] {
                        "retry-binance-source",
                        "--dataset",
                        "all",
                        "--from",
                        env.getRequiredProperty("app.crypto-data.archive-history-start"),
                        "--through",
                        through
                    });
            execute(
                    new String[] {
                        "funding-binance-tail",
                        "--dataset",
                        "funding",
                        "--from",
                        LocalDate.parse(through).withDayOfMonth(1).toString(),
                        "--through",
                        through
                    });
            return true;
        }
        if (args.length == 0
                || !List.of(
                                "probe-binance",
                                "collect-binance",
                                "status-binance",
                                "check-binance-revisions",
                                "retry-binance-source",
                                "funding-binance-tail")
                        .contains(args[0])) return false;
        if (args[0].equals("status-binance")) {
            if (args.length != 1) throw new IllegalArgumentException("status-binance 不带参数");
            var env = ProjectConfiguration.load("btc-data");
            var base = Path.of(env.getRequiredProperty("app.crypto-data.root"));
            var store =
                    new VerifiedMarketDataStore(
                            URI.create(env.getRequiredProperty("app.questdb.http-url")),
                            env.getProperty("app.questdb.http-authorization", ""),
                            env.getProperty("app.questdb.username", "admin"),
                            env.getProperty("app.questdb.password", ""),
                            base);
            System.out.println(
                    new ObjectMapper()
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    BinanceArchiveStatus.inspect(
                                            base.resolve("binance-vision"), store)));
            return true;
        }
        boolean probe = args[0].equals("probe-binance");
        var options = new LinkedHashMap<String, String>();
        boolean download = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--download-only")) {
                download = true;
                continue;
            }
            if (!List.of("--dataset", "--from", "--through").contains(args[i])
                    || i + 1 == args.length
                    || options.put(args[i], args[++i]) != null)
                throw new IllegalArgumentException(
                        "命令：collect-binance --dataset all|spot_1d|spot_1h|perp_1h|funding|mark_1h|index_1h --from YYYY-MM-DD --through YYYY-MM-DD [--download-only]");
        }
        if (probe && (download || !options.isEmpty()) || !probe && options.size() != 3)
            throw new IllegalArgumentException("probe-binance 不带参数；collect-binance 必须指定数据集和起止日期");
        var env = ProjectConfiguration.load("btc-data");
        var base = Path.of(env.getRequiredProperty("app.crypto-data.root"));
        var root = base.resolve("binance-vision");
        Files.createDirectories(root);
        var policy =
                new PersistentMarketApiBudget.Policy(
                        env.getProperty("app.crypto-data.requests-per-hour", Integer.class, 2000),
                        env.getProperty("app.crypto-data.requests-per-day", Integer.class, 20000),
                        10000,
                        env.getProperty(
                                "app.crypto-data.bytes-per-31-days", Long.class, 5368709120L),
                        env.getProperty("app.crypto-data.request-spacing-ms", Long.class, 400L));
        // 与旧 BTC 下载共享同一个持久预算，分开归档不增加来源配额。
        try (var budget =
                        new PersistentMarketApiBudget(
                                base.resolve("api-budget.sqlite3"),
                                Map.of("binance", policy, "bybit", policy));
                var http =
                        new FrozenHttpSource(
                                root, budget, env.getProperty("app.crypto-data.proxy-url", ""))) {
            var source = new BinanceArchiveSource(http, root);
            if (probe) BinanceArchiveProbe.run(source, root);
            else {
                List<BinanceArchiveDataset> datasets =
                        options.get("--dataset").equals("all")
                                ? Arrays.asList(BinanceArchiveDataset.values())
                                : List.of(BinanceArchiveDataset.parse(options.get("--dataset")));
                var windows =
                        BinanceArchiveSync.plan(
                                datasets,
                                LocalDate.parse(options.get("--from")),
                                LocalDate.parse(options.get("--through")));
                var store =
                        new VerifiedMarketDataStore(
                                URI.create(env.getRequiredProperty("app.questdb.http-url")),
                                env.getProperty("app.questdb.http-authorization", ""),
                                env.getProperty("app.questdb.username", "admin"),
                                env.getProperty("app.questdb.password", ""),
                                base);
                if (args[0].equals("funding-binance-tail")) {
                    if (!options.get("--dataset").equals("funding") || download)
                        throw new IllegalArgumentException(
                                "API 尾部只支持 --dataset funding，正式写入不接受 --download-only");
                    System.out.println(
                            new ObjectMapper()
                                    .writeValueAsString(
                                            BinanceFundingTailSync.run(
                                                    root,
                                                    http,
                                                    store,
                                                    LocalDate.parse(options.get("--from")),
                                                    LocalDate.parse(options.get("--through")))));
                } else if (args[0].equals("check-binance-revisions")) {
                    if (download) throw new IllegalArgumentException("修订探测不接受 --download-only");
                    System.out.println(BinanceArchiveRevisionCheck.run(root, source, windows));
                } else {
                    var sync = new BinanceArchiveSync(root, source, store);
                    if (args[0].equals("retry-binance-source")) sync.retrySource(windows);
                    sync.run(windows, download);
                }
            }
        }
        return true;
    }
}
