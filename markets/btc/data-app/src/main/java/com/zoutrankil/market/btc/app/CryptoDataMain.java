package com.zoutrankil.market.btc.app;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** BTC 的有限历史入口，与美股只共享通用请求预算和验收端口。 */
public final class CryptoDataMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("collect") || !args[1].equals("--through"))
            throw new IllegalArgumentException("命令：collect --through YYYY-MM-DD");
        var env = ProjectConfiguration.load("btc-data");
        var through = LocalDate.parse(args[2]);
        if (!through.isBefore(LocalDate.now(ZoneOffset.UTC)))
            throw new IllegalArgumentException("只采集已完成的 UTC 日期");
        var root = Path.of(env.getRequiredProperty("app.crypto-data.root"));
        var store =
                new VerifiedMarketDataStore(
                        URI.create(env.getRequiredProperty("app.questdb.http-url")),
                        env.getProperty("app.questdb.http-authorization", ""),
                        env.getProperty("app.questdb.username", "admin"),
                        env.getProperty("app.questdb.password", ""),
                        root);
        new CryptoHistoryCollector(env, root, store).run(through);
    }
}
