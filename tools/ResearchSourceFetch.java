import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 只下载显式清单的小型研究原件；不写正式库、不使用生产来源预算或令牌。 */
public class ResearchSourceFetch {
    /** 先检查下一块是否越界再写入，未知Content-Length也受同一容量限制。 */
    static long copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] chunk = new byte[65536];
        long count = 0;
        for (int n; (n = input.read(chunk)) != -1;) {
            if (n > limit - count) throw new IOException("读取中超过容量预算");
            output.write(chunk, 0, n);
            count += n;
        }
        if (count == 0) throw new IOException("来源为空");
        return count;
    }

    static String sha(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] chunk = new byte[65536];
            for (int n; (n = in.read(chunk)) != -1;) digest.update(chunk, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static long bytes(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            long size = 0;
            for (var file : files.filter(Files::isRegularFile).toList()) size += Files.size(file);
            return size;
        }
    }

    static Properties read(Path path) throws IOException {
        var result = new Properties();
        try (var in = Files.newInputStream(path)) { result.load(in); }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("参数：根配置路径 [单个来源ID]");
        var config = read(Path.of(args[0]));
        Path root = Path.of(config.getProperty("data.root")).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("raw"));
        long maxTotal = Long.parseLong(config.getProperty("max.total.bytes"));
        long maxFile = Long.parseLong(config.getProperty("max.file.bytes"));
        long seconds = Long.parseLong(config.getProperty("timeout.seconds"));
        if (maxTotal <= 0 || maxTotal > 300000000 || maxFile <= 0 || maxFile > maxTotal || seconds <= 0 || seconds > 60)
            throw new IllegalArgumentException("配置超出本任务的300MB或60秒边界");
        var clientBuilder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER);
        String proxy = System.getenv("RESEARCH_HTTPS_PROXY");
        if (proxy != null && !proxy.isBlank()) {
            URI p = URI.create(proxy);
            if (!"http".equals(p.getScheme()) || p.getHost() == null || p.getPort() <= 0 || p.getUserInfo() != null)
                throw new IllegalArgumentException("代理须是无账号的HTTP地址");
            clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort())));
        }
        Set<String> ids = Set.of(config.getProperty("sources").split(","));
        if (args.length == 2 && !ids.contains(args[1])) throw new IllegalArgumentException("来源不在固定清单");
        int failures = 0;
        // 与Python准备器共用同一文件锁，避免并发下载和派生文件一起超过总容量。
        try (var lockFile = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockFile.tryLock(); var client = clientBuilder.build()) {
            if (lock == null) throw new IllegalStateException("研究目录正由其他任务使用");
            if (bytes(root) > maxTotal) throw new IllegalStateException("现有研究目录已经超出容量预算");
            for (String id : config.getProperty("sources").split(",")) {
                if (args.length == 2 && !id.equals(args[1])) continue;
                if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("来源ID格式不合法");
                Path target = root.resolve("raw/" + id + ".source");
                Path receipt = root.resolve("raw/" + id + ".properties");
                Path part = root.resolve("raw/" + id + ".part");
                URI url = URI.create(config.getProperty(id + ".url"));
                if (!"https".equals(url.getScheme()) || url.getUserInfo() != null
                        || !Set.of("data.sec.gov", "fred.stlouisfed.org", "stablecoins.llama.fi", "raw.githubusercontent.com", "api.github.com", "query1.finance.yahoo.com").contains(url.getHost()))
                    throw new IllegalArgumentException("来源不在已核对的公开域名中");
                boolean createdPart = false;
                try {
                    if (Files.exists(target) || Files.exists(receipt) || Files.exists(part)) {
                        if (Files.exists(target) && Files.exists(receipt) && !Files.exists(part)) {
                            var old = read(receipt);
                            if (sha(target).equals(old.getProperty("sha256")) && url.toString().equals(old.getProperty("url"))
                                    && Long.toString(Files.size(target)).equals(old.getProperty("bytes"))) {
                                System.out.println(id + " REUSED " + Files.size(target));
                                continue;
                            }
                        }
                        throw new IOException("已有原件不完整或摘要改变，保留现场，拒绝覆盖");
                    }
                    // 预留回执空间；无Content-Length时仍逐块限流，超限不留下半成品。
                    long room = Math.min(maxFile, maxTotal - bytes(root) - 16384);
                    if (room <= 0) throw new IOException("研究容量预算耗尽");
                    if (url.getHost().equals("data.sec.gov") && (System.getenv("SEC_USER_AGENT") == null
                            || !System.getenv("SEC_USER_AGENT").contains("@")))
                        throw new IOException("SEC需要真实姓名和联系邮箱的SEC_USER_AGENT；未配置则不发请求");
                    String agent = url.getHost().equals("data.sec.gov") ? System.getenv("SEC_USER_AGENT")
                            : "QuestDBWithData research";
                    var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(seconds))
                            .header("User-Agent", agent).header("Accept", "application/json,text/csv").GET().build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream input = response.body()) {
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        long expectedBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                        if (expectedBytes > room)
                            throw new IOException("来源文件超过容量预算");
                        // ofInputStream只约束响应头等待；另外限住读取正文，慢连接也不能无限占用。
                        var timer = CompletableFuture.runAsync(() -> {
                            try { input.close(); } catch (IOException ignored) { /* 主读取线程报告失败。 */ }
                        }, CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS));
                        try (var out = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                            createdPart = true;
                            long count = copyLimited(input, out, room);
                            if (expectedBytes >= 0 && count != expectedBytes)
                                throw new IOException("正文长度与响应头不一致");
                            if (timer.isDone() && !timer.isCancelled()) throw new IOException("正文读取超时");
                        } finally { timer.cancel(false); }
                    }
                    var proof = new Properties();
                    proof.setProperty("url", url.toString());
                    proof.setProperty("sha256", sha(part));
                    proof.setProperty("bytes", Long.toString(Files.size(part)));
                    proof.setProperty("retrievedAt", Instant.now().toString());
                    proof.setProperty("scope", "research-only; not historical PIT; not formal ingestion");
                    Files.move(part, target);
                    try (var out = Files.newOutputStream(receipt, StandardOpenOption.CREATE_NEW)) {
                        proof.store(out, "Research source receipt");
                    }
                    System.out.println(id + " DOWNLOADED " + Files.size(target));
                } catch (Exception e) {
                    // 只删除本次创建的半成品，历史遗留现场必须保留供核对。
                    if (createdPart) Files.deleteIfExists(part);
                    // 不输出请求头、代理或完整异常，失败不写成成功回执。
                    System.err.println(id + " FAILED " + e.getClass().getSimpleName() + ": "
                            + (e instanceof IOException ? e.getMessage() : "来源请求失败"));
                    failures++;
                }
                Thread.sleep(Long.parseLong(config.getProperty("request.spacing.ms")));
            }
        }
        if (failures > 0) System.exit(2);
    }
}
