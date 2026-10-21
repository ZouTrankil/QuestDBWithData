package com.zoutrankil.market.btc.archive.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 复用原 HTTP 预算和不可变响应；清单只表示文件校验成功，不代表行情已入库。 */
public final class BinanceArchiveSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FrozenHttpSource http;
    private final Path root;

    public record Archive(
            String url,
            Path raw,
            String sha256,
            Instant observedAt,
            Path checksum,
            String checksumSha256) {}

    public BinanceArchiveSource(FrozenHttpSource http, Path root) {
        this.http = http;
        this.root = root.toAbsolutePath().normalize();
    }

    public Archive fetch(URI uri, boolean refresh) throws Exception {
        if (!uri.getHost().equals("data.binance.vision")
                || !uri.getPath().endsWith(".zip")
                || uri.getQuery() != null)
            throw new IllegalArgumentException("只接受 Binance 官方 ZIP 地址");
        String key = VerifiedMarketDataStore.sha(uri.toString().getBytes(StandardCharsets.UTF_8));
        Path manifest = root.resolve("archives").resolve(key + ".json");
        Files.createDirectories(manifest.getParent());
        if (!refresh && Files.isRegularFile(manifest)) {
            var doc = JSON.readTree(Files.readAllBytes(manifest));
            var old =
                    new Archive(
                            doc.path("url").asText(),
                            Path.of(doc.path("raw").asText()),
                            doc.path("sha256").asText(),
                            Instant.parse(doc.path("observedAt").asText()),
                            Path.of(doc.path("checksum").asText()),
                            doc.path("checksumSha256").asText());
            if (!old.url().equals(uri.toString())) throw new IllegalStateException("缓存 URL 与原请求不符");
            verify(old);
            return old;
        }
        var checksum = get(URI.create(uri + ".CHECKSUM"), 2048);
        String[] fields = Files.readString(checksum.path()).trim().split("\\s+");
        String filename = Path.of(uri.getPath()).getFileName().toString();
        if (fields.length != 2
                || !fields[0].matches("[a-fA-F0-9]{64}")
                || !fields[1].replaceFirst("^\\*", "").equals(filename))
            throw new IllegalArgumentException("官方 CHECKSUM 内容或文件名不符");
        var raw = get(uri, 4 * 1024 * 1024);
        if (!fields[0].equalsIgnoreCase(raw.sha256()))
            throw new IllegalStateException("ZIP 与官方 CHECKSUM 不符");
        var result =
                new Archive(
                        uri.toString(),
                        raw.path(),
                        raw.sha256(),
                        raw.observedAt(),
                        checksum.path(),
                        checksum.sha256());
        // 每个版本清单独立保留；替换的只是可重建的当前指针。
        byte[] bytes =
                JSON.writeValueAsBytes(
                        Map.of(
                                "url",
                                result.url(),
                                "raw",
                                result.raw().toString(),
                                "sha256",
                                result.sha256(),
                                "observedAt",
                                result.observedAt().toString(),
                                "checksum",
                                result.checksum().toString(),
                                "checksumSha256",
                                result.checksumSha256()));
        Path version = manifest.resolveSibling(key + "-" + UUID.randomUUID() + ".json");
        Files.write(version, bytes, StandardOpenOption.CREATE_NEW);
        Path pending = manifest.resolveSibling(key + "-" + UUID.randomUUID() + ".pending");
        Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
        Files.move(
                pending,
                manifest,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        return result;
    }

    private FrozenHttpSource.Response get(URI uri, int max) throws Exception {
        while (true) {
            try {
                return http.get("binance", "public", "BTCUSDT", uri, Map.of(), max);
            } catch (PersistentMarketApiBudget.Exhausted wait) {
                long delay = Duration.between(Instant.now(), wait.nextAllowed).toMillis();
                if (delay > 60_000) throw wait;
                Thread.sleep(Math.max(1, delay));
            }
        }
    }

    private static void verify(Archive archive) throws Exception {
        if (!VerifiedMarketDataStore.sha(Files.readAllBytes(archive.raw())).equals(archive.sha256())
                || !VerifiedMarketDataStore.sha(Files.readAllBytes(archive.checksum()))
                        .equals(archive.checksumSha256()))
            throw new IllegalStateException("缓存原文已变化，保留文件并停止该项");
        String[] fields = Files.readString(archive.checksum()).trim().split("\\s+");
        if (fields.length != 2
                || !fields[0].equalsIgnoreCase(archive.sha256())
                || !fields[1]
                        .replaceFirst("^\\*", "")
                        .equals(
                                Path.of(URI.create(archive.url()).getPath())
                                        .getFileName()
                                        .toString()))
            throw new IllegalStateException("缓存 CHECKSUM 与原 ZIP 不符");
    }
}
