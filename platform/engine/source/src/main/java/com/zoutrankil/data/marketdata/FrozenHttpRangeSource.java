package com.zoutrankil.data.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 大文件按明确字节范围冻结；每次请求仍先记原预算，不接受整文件回退或静默缩短。 */
public final class FrozenHttpRangeSource implements AutoCloseable {
    public record Response(
            Path path,
            String sha256,
            Instant observedAt,
            String sourceUrl,
            long first,
            long last,
            long total,
            String etag,
            String lastModified,
            String requestId) {
        public String validator() {
            if (!etag.isBlank() && !etag.startsWith("W/")) return etag;
            if (!lastModified.isBlank()) {
                ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME);
                return lastModified;
            }
            throw new IllegalStateException("分段来源缺少可核对的实体版本");
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private final Path root;
    private final MarketRequestBudget budget;
    private final HttpClient client;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("market-range-deadline").factory());

    public FrozenHttpRangeSource(Path root, MarketRequestBudget budget, String proxyUrl) {
        this.root = root.toAbsolutePath().normalize();
        this.budget = Objects.requireNonNull(budget);
        var builder =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            var uri = URI.create(proxyUrl);
            if (uri.getUserInfo() != null) throw new IllegalArgumentException("代理凭据须单独配置");
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        }
        client = builder.build();
    }

    public Response get(
            String provider,
            String account,
            String resource,
            URI uri,
            long first,
            int length,
            String ifRange)
            throws Exception {
        if (!Set.of("https", "http").contains(uri.getScheme())
                || uri.getUserInfo() != null
                || !provider.matches("[a-z][a-z0-9_-]{0,63}")
                || first < 0
                || length < 1
                || length > 64 * 1024 * 1024)
            throw new IllegalArgumentException("分段来源必须使用明确且不超过 64 MiB 的 HTTP 范围");
        long last = Math.addExact(first, length - 1L);
        if (ifRange == null
                || ifRange.startsWith("W/")
                || ifRange.length() > 512
                || ifRange.contains("\r")
                || ifRange.contains("\n")) throw new IllegalArgumentException("分段实体版本非法");
        String id = budget.reserve(provider, account, resource, length + 1);
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("非法预算请求标识");
        boolean accounted = false;
        var started = Instant.now();
        try {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("User-Agent", "QuestDBWithData/market-data")
                            .header("Accept-Encoding", "identity")
                            .header("Range", "bytes=" + first + "-" + last)
                            .GET();
            if (!ifRange.isEmpty()) request.header("If-Range", ifRange);
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var input = response.body()) {
                var deadline =
                        timer.schedule(
                                () -> {
                                    try {
                                        input.close();
                                    } catch (IOException ignored) {
                                    }
                                },
                                60,
                                TimeUnit.SECONDS);
                try {
                    bytes =
                            input.readNBytes(
                                    response.statusCode() == 206
                                            ? length + 1
                                            : Math.min(length + 1, 65536));
                } finally {
                    deadline.cancel(false);
                }
            }
            Instant observed = Instant.now();
            String retryText = response.headers().firstValue("Retry-After").orElse("");
            Instant retry = null;
            try {
                retry = observed.plusSeconds(Math.max(0, Long.parseLong(retryText)));
            } catch (Exception ignored) {
                try {
                    retry =
                            ZonedDateTime.parse(retryText, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant();
                } catch (Exception absent) {
                }
            }
            // 服务器忽略范围时仅保留响应前缀，不能把未知的整文件带宽记作已完整接收。
            budget.complete(
                    id,
                    response.statusCode() == 200 ? 0 : response.statusCode(),
                    bytes.length,
                    retry);
            accounted = true;
            String range = response.headers().firstValue("Content-Range").orElse("");
            String etag = response.headers().firstValue("ETag").orElse("");
            String modified = response.headers().firstValue("Last-Modified").orElse("");
            String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
            var matcher = RANGE.matcher(range);
            boolean valid =
                    response.statusCode() == 206
                            && matcher.matches()
                            && bytes.length == length
                            && (encoding.isBlank() || encoding.equalsIgnoreCase("identity"));
            long total = -1;
            if (valid) {
                try {
                    total = Long.parseLong(matcher.group(3));
                    valid =
                            Long.parseLong(matcher.group(1)) == first
                                    && Long.parseLong(matcher.group(2)) == last
                                    && total > last;
                } catch (NumberFormatException invalidRange) {
                    valid = false;
                }
            }
            if (valid && !ifRange.isEmpty())
                valid = ifRange.equals(etag) || ifRange.equals(modified);
            String sha =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path folder = root.resolve("raw").resolve(provider);
            Files.createDirectories(folder);
            Path target = folder.resolve(id + (valid ? ".response" : ".rejected-prefix"));
            Path pending = folder.resolve(id + ".pending");
            Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
            try (var file = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
            var card = new LinkedHashMap<String, Object>();
            card.put("requestId", id);
            card.put("provider", provider);
            card.put("resource", resource);
            card.put("sourceUrl", FrozenHttpSource.redacted(uri));
            card.put("startedAt", started.toString());
            card.put("observedAt", observed.toString());
            card.put("httpStatus", response.statusCode());
            card.put("requestedFirst", first);
            card.put("requestedLast", last);
            card.put("contentRange", range);
            card.put("etag", etag);
            card.put("lastModified", modified);
            card.put("ifRange", ifRange);
            card.put("bytes", bytes.length);
            card.put("sha256", sha);
            card.put("raw", target.toString());
            card.put("accepted", valid);
            card.put("bodyScope", valid ? "EXACT_RANGE" : "REJECTED_PREFIX");
            Files.write(
                    folder.resolve(id + ".json"),
                    JSON.writeValueAsBytes(card),
                    StandardOpenOption.CREATE_NEW);
            if (!valid) {
                if (response.statusCode() != 206)
                    throw new FrozenHttpSource.SourceStatusException(
                            response.statusCode(), FrozenHttpSource.redacted(uri), retry);
                throw new IllegalStateException("分段响应的范围、长度、编码或实体版本不一致");
            }
            return new Response(
                    target,
                    sha,
                    observed,
                    FrozenHttpSource.redacted(uri),
                    first,
                    last,
                    total,
                    etag,
                    modified,
                    id);
        } catch (Exception e) {
            if (!accounted) budget.complete(id, 0, 0, null);
            if (e instanceof FrozenHttpSource.SourceStatusException
                    || e instanceof IllegalStateException) throw e;
            throw new IOException(
                    "分段来源请求未完成：" + provider + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public void close() throws Exception {
        timer.shutdownNow();
        client.close();
    }
}
