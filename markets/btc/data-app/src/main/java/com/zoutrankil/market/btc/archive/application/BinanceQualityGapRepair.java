package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveMapper;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 有限缺日补齐，只复用原同步、原物理目标和恢复协议，不迁移已有键。 */
public final class BinanceQualityGapRepair {
    private BinanceQualityGapRepair() {}

    public static void run(
            Path root, BinanceArchiveSource source, VerifiedMarketDataStore store, Path planPath)
            throws Exception {
        if (Files.exists(root.resolve("stop.request")))
            throw new IllegalStateException("存在 stop.request，不启动缺日补齐");
        // 只接已冻结日包的显式缺日补齐；原同步负责物理目标、重叠值和未决保护。
        var plan = new ObjectMapper().readTree(Files.readAllBytes(planPath));
        var requests = BinanceQualitySourceReview.validatePlan(plan);
        var windows = new ArrayList<BinanceArchiveSync.Window>();
        var hashes = new LinkedHashMap<String, String>();
        for (var request : requests) {
            var dataset = BinanceArchiveDataset.parse(request.path("dataset").asText());
            var day = LocalDate.parse(request.path("day").asText());
            if (!List.of(BinanceArchiveDataset.MARK_1H, BinanceArchiveDataset.INDEX_1H)
                            .contains(dataset)
                    || !request.path("dailySha256").asText().matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("本轮只允许标记价或指数价的已核对完整缺日");
            var raw = source.fetch(dataset.daily(day), false);
            if (!raw.sha256().equals(request.path("dailySha256").asText()))
                throw new IllegalStateException("核对后的日包版本变化，不自动补入");
            var coverage =
                    BinanceArchiveMapper.inspect(
                            dataset,
                            Files.readAllBytes(raw.raw()),
                            day,
                            day,
                            raw.sha256(),
                            raw.observedAt());
            if (!coverage.complete() || coverage.rows().size() != 24)
                throw new IllegalStateException("补齐日包必须有完整的 24 根小时线");
            var window = new BinanceArchiveSync.Window(dataset, dataset.daily(day), day, day);
            windows.add(window);
            hashes.put(window.id(), raw.sha256());
        }
        // 先探测数据库，断连时不改原队列，也不重复刷新来源。
        store.query("SELECT 1");
        var sync = new BinanceArchiveSync(root, source, store);
        sync.retrySource(windows);
        sync.run(windows, false, hashes);
    }
}
