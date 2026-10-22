package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.AtomicMarketStatus;
import com.zoutrankil.data.marketdata.FrozenHttpRangeSource;
import com.zoutrankil.data.marketdata.FrozenHttpSource;
import com.zoutrankil.data.marketdata.PersistentMarketApiBudget;
import com.zoutrankil.market.us.marketdata.UsEquitySessionCalendar;

import org.springframework.core.env.Environment;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** 顺序续取官方压缩前缀，定位正常时段后冻结原件；本入口不写正式行情或推进 Native。 */
final class UsItchPrefixCollector {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Settings(
            URI uri,
            LocalDate date,
            LocalTime time,
            int count,
            int rangeBytes,
            long maximumBytes,
            long maximumMessages,
            PersistentMarketApiBudget.Policy policy) {}

    static Settings settings(Environment env) throws Exception {
        URI uri = URI.create(env.getRequiredProperty("app.market-data.itch-source-url"));
        LocalDate date = LocalDate.parse(env.getRequiredProperty("app.market-data.itch-file-date"));
        LocalTime time =
                LocalTime.parse(env.getRequiredProperty("app.market-data.itch-sample-local-time"));
        int count =
                env.getRequiredProperty("app.market-data.itch-sample-message-count", Integer.class);
        int range = env.getRequiredProperty("app.market-data.itch-range-bytes", Integer.class);
        long maximum =
                env.getRequiredProperty("app.market-data.itch-prefix-limit-bytes", Long.class);
        long messages =
                env.getRequiredProperty("app.market-data.itch-maximum-scan-messages", Long.class);
        var session =
                UsEquitySessionCalendar.load()
                        .session(date)
                        .orElseThrow(() -> new IllegalArgumentException("ITCH 日期不是已核对开市日"));
        Instant target = date.atTime(time).atZone(ZoneId.of("America/New_York")).toInstant();
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !uri.getPath()
                        .endsWith(
                                "/S"
                                        + date.format(DateTimeFormatter.ofPattern("MMddyy"))
                                        + "-v50.txt.gz")
                || target.isBefore(session.open())
                || !target.isBefore(session.close())
                || count < 1
                || count > 100000
                || range < 1
                || range > 64 * 1024 * 1024
                || maximum < range
                || maximum > 1024L * 1024 * 1024
                || messages < 1
                || messages > 100_000_000L)
            throw new IllegalArgumentException("ITCH 来源日期、正常时段或有限扫描预算不匹配");
        var policy =
                new PersistentMarketApiBudget.Policy(
                        env.getRequiredProperty(
                                "app.market-data.itch-requests-per-hour", Integer.class),
                        env.getRequiredProperty(
                                "app.market-data.itch-requests-per-day", Integer.class),
                        1,
                        env.getRequiredProperty("app.market-data.itch-monthly-bytes", Long.class),
                        env.getRequiredProperty(
                                "app.market-data.itch-request-spacing-ms", Long.class));
        return new Settings(uri, date, time, count, range, maximum, messages, policy);
    }

    private final Environment env;
    private final Path root;

    UsItchPrefixCollector(Environment env, Path root) {
        this.env = env;
        this.root = root.toAbsolutePath().normalize();
    }

    private void progress(Path folder, String phase, Object detail) throws Exception {
        byte[] bytes =
                JSON.writeValueAsBytes(
                        Map.of(
                                "phase",
                                phase,
                                "detail",
                                detail,
                                "pid",
                                ProcessHandle.current().pid(),
                                "updatedAt",
                                Instant.now().toString(),
                                "formalWrites",
                                0));
        AtomicMarketStatus.write(folder.resolve("progress.json"), bytes);
        AtomicMarketStatus.write(root.resolve("us-itch-source-status.json"), bytes);
        System.out.println(new String(bytes, StandardCharsets.UTF_8));
        System.out.flush();
    }

    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = in.read(bytes)) >= 0) if (count > 0) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    record Scan(
            long first,
            long firstOffset,
            long endOffset,
            long minimum,
            long maximum,
            int selected,
            Map<String, Integer> types) {}

    static Scan scan(InputStream compressed, LocalTime start, int count, long limit)
            throws Exception {
        if (count < 1 || count > 100000 || limit < 1 || limit > 100_000_000L)
            throw new IllegalArgumentException("ITCH 扫描和产物上限须明确");
        long first = 0,
                offset = 0,
                firstOffset = -1,
                minimum = Long.MAX_VALUE,
                maximum = 0,
                index = 0;
        int selected = 0;
        var types = new TreeMap<String, Integer>();
        // 解压状态只沿同一实体连续前进；不猜 gzip 重启点，也不把截断消息补成完整消息。
        try (var input = new DataInputStream(new GZIPInputStream(compressed))) {
            while (selected < count) {
                if (++index > limit) throw new IOException("ITCH 达到明确消息扫描上限");
                int size = input.readUnsignedShort();
                if (size < 11 || size > 256) throw new IOException("ITCH 消息长度非法");
                byte[] p = input.readNBytes(size);
                if (p.length != size) throw new EOFException("ITCH 消息尚未完整");
                long nanos = 0;
                for (int i = 5; i < 11; i++) nanos = (nanos << 8) | (p[i] & 255L);
                if (nanos >= 86400_000_000_000L) throw new IOException("ITCH 时间超出源日期");
                if (first == 0 && nanos >= start.toNanoOfDay()) {
                    first = index;
                    firstOffset = offset;
                }
                if (first != 0) {
                    selected++;
                    minimum = Math.min(minimum, nanos);
                    maximum = Math.max(maximum, nanos);
                    types.merge(Character.toString((char) p[0]), 1, Integer::sum);
                }
                offset += size + 2L;
            }
        }
        return new Scan(first, firstOffset, offset, minimum, maximum, selected, Map.copyOf(types));
    }

    private final class Growing extends InputStream {
        final Settings s;
        final Path folder;
        final FrozenHttpRangeSource source;
        final FileChannel output;
        final List<Map<String, Object>> chunks = new ArrayList<>();
        byte[] bytes = new byte[0];
        int at;
        long length, total = -1;
        String validator = "";
        Instant observed;

        Growing(Settings s, Path folder, FrozenHttpRangeSource source, Path pending)
                throws Exception {
            this.s = s;
            this.folder = folder;
            this.source = source;
            output =
                    FileChannel.open(
                            pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        void fetch() throws Exception {
            if (Files.exists(root.resolve("us-itch-stop.request")))
                throw new InterruptedException("ITCH 来源已收到停止请求");
            long remaining =
                    Math.min(
                            s.maximumBytes() - length, total < 0 ? Long.MAX_VALUE : total - length);
            if (remaining < 1) throw new IOException("ITCH 前缀到达明确上限，所选完整消息区间仍未齐备");
            int count = (int) Math.min(s.rangeBytes(), remaining);
            FrozenHttpRangeSource.Response r;
            while (true) {
                try {
                    r =
                            source.get(
                                    "nasdaq-itch",
                                    "public",
                                    s.date().toString(),
                                    s.uri(),
                                    length,
                                    count,
                                    validator);
                    break;
                } catch (PersistentMarketApiBudget.Exhausted wait) {
                    progress(folder, "WAITING_QUOTA", wait.nextAllowed.toString());
                    if (Files.exists(root.resolve("us-itch-stop.request")))
                        throw new InterruptedException("ITCH 来源已收到停止请求");
                    Thread.sleep(
                            Math.max(
                                    1,
                                    Math.min(
                                            10000,
                                            Duration.between(Instant.now(), wait.nextAllowed)
                                                    .toMillis())));
                }
            }
            if (total >= 0 && total != r.total()) throw new IOException("ITCH 分段的整文件长度变化");
            if (total < 0) {
                total = r.total();
                validator = r.validator();
                Path old = root.resolve("itch/S061226-v50.prefix8MiB.json");
                if (Files.isRegularFile(old)) {
                    var card = JSON.readTree(Files.readAllBytes(old));
                    if (card.path("sourceUrl").asText().equals(s.uri().toString())
                            && r.last() + 1 == card.path("compressedPrefixBytes").asLong()
                            && !r.sha256().equals(card.path("compressedSha256").asText()))
                        throw new IOException("当前官方首段与保留原前缀不一致");
                }
            }
            if (!sha(r.path()).equals(r.sha256())) throw new IOException("ITCH 已冻结分段摘要变化");
            bytes = Files.readAllBytes(r.path());
            at = 0;
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
            length += bytes.length;
            observed = r.observedAt();
            chunks.add(
                    Map.of(
                            "requestId",
                            r.requestId(),
                            "raw",
                            r.path().toString(),
                            "sha256",
                            r.sha256(),
                            "first",
                            r.first(),
                            "last",
                            r.last(),
                            "total",
                            r.total(),
                            "etag",
                            r.etag(),
                            "lastModified",
                            r.lastModified()));
            Files.write(
                    folder.resolve("chunks.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(chunks));
            progress(
                    folder,
                    "FETCHING_PREFIX",
                    Map.of(
                            "bytes",
                            length,
                            "chunks",
                            chunks.size(),
                            "maximumBytes",
                            s.maximumBytes()));
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] target, int offset, int count) throws IOException {
            Objects.checkFromIndexSize(offset, count, target.length);
            if (count == 0) return 0;
            if (at == bytes.length)
                try {
                    fetch();
                } catch (Exception e) {
                    throw new IOException("ITCH 顺序分段读取未完成", e);
                }
            int copied = Math.min(count, bytes.length - at);
            System.arraycopy(bytes, at, target, offset, copied);
            at += copied;
            return copied;
        }

        @Override
        public void close() throws IOException {
            output.close();
        }
    }

    Map<String, Object> run() throws Exception {
        Settings s = settings(env);
        Files.createDirectories(root);
        try (var channel =
                        FileChannel.open(
                                root.resolve("us-itch-source.lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("已有 ITCH 来源同步进程");
            if (Files.exists(root.resolve("us-itch-stop.request")))
                throw new IllegalStateException("ITCH 来源停止请求尚未解除");
            Path folder = root.resolve("itch/rth-" + UUID.randomUUID());
            Files.createDirectories(folder);
            Path pending = folder.resolve("prefix.pending"),
                    raw =
                            folder.resolve(
                                    "S"
                                            + s.date().format(DateTimeFormatter.ofPattern("MMddyy"))
                                            + "-v50.rth-prefix.gz");
            progress(
                    folder,
                    "STARTING_PREFIX",
                    Map.of(
                            "source",
                            FrozenHttpSource.redacted(s.uri()),
                            "maximumBytes",
                            s.maximumBytes()));
            try (var budget =
                            new PersistentMarketApiBudget(
                                    root.resolve("api-budget.sqlite3"),
                                    Map.of("nasdaq-itch", s.policy()));
                    var source =
                            new FrozenHttpRangeSource(
                                    root,
                                    budget,
                                    env.getProperty("app.market-data.proxy-url", ""));
                    var growing = new Growing(s, folder, source, pending)) {
                var scan = scan(growing, s.time(), s.count(), s.maximumMessages());
                var session = UsEquitySessionCalendar.load().session(s.date()).orElseThrow();
                Instant midnight = s.date().atStartOfDay(ZoneId.of("America/New_York")).toInstant();
                if (midnight.plusNanos(scan.minimum()).isBefore(session.open())
                        || !midnight.plusNanos(scan.maximum()).isBefore(session.close())
                        || scan.types().getOrDefault("E", 0)
                                        + scan.types().getOrDefault("C", 0)
                                        + scan.types().getOrDefault("P", 0)
                                == 0) throw new IllegalStateException("所取片段没有完整落在正常时段或没有成交类消息");
                Files.move(pending, raw, StandardCopyOption.ATOMIC_MOVE);
                var card = new LinkedHashMap<String, Object>();
                card.put("sourceUrl", FrozenHttpSource.redacted(s.uri()));
                card.put("fileDate", s.date().toString());
                card.put("compression", "gzip");
                card.put("compressedPrefixBytes", growing.length);
                card.put("compressedSha256", sha(raw));
                card.put("sourceComplete", false);
                card.put("observedAt", growing.observed.toString());
                card.put("totalCompressedBytes", growing.total);
                card.put("entityValidator", growing.validator);
                card.put("chunks", growing.chunks);
                card.put("firstMessage", scan.first());
                card.put("messageCount", scan.selected());
                card.put("firstDecompressedOffset", scan.firstOffset());
                card.put("selectedEndDecompressedOffsetExclusive", scan.endOffset());
                card.put("firstTimestamp", midnight.plusNanos(scan.minimum()).toString());
                card.put("lastTimestamp", midnight.plusNanos(scan.maximum()).toString());
                card.put("types", scan.types());
                card.put("maximumFirstMessage", s.maximumMessages());
                card.put("session", "REGULAR");
                card.put("scope", "只保留完整压缩前缀中的明确消息区间，不是全日或完整订单簿");
                Path metadata = folder.resolve("metadata.json");
                Files.write(
                        metadata,
                        JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(card),
                        StandardOpenOption.CREATE_NEW);
                var result =
                        Map.<String, Object>of(
                                "source",
                                raw.toString(),
                                "metadata",
                                metadata.toString(),
                                "firstMessage",
                                scan.first(),
                                "messageCount",
                                scan.selected(),
                                "sourceSha256",
                                card.get("compressedSha256"),
                                "formalWrites",
                                0);
                progress(folder, "PREFIX_READY_NOT_PUBLISHED", result);
                return result;
            } catch (Exception e) {
                progress(
                        folder,
                        "PREFIX_FAILED_NOT_PUBLISHED",
                        Map.of(
                                "error",
                                e.getClass().getSimpleName(),
                                "message",
                                Objects.toString(e.getMessage(), "")));
                throw e;
            }
        }
    }
}
