package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** 原始响应和清单一起冻结；按日期组织发布，减少逐证券回填造成的历史分区重写。 */
public final class UsYahooDailyBundle {
    private UsYahooDailyBundle() {}

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Source(
            String task,
            String symbol,
            String yahooSymbol,
            String sourceUrl,
            String sha256,
            Instant observedAt,
            byte[] body) {
        public Source {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    public record Member(
            String task,
            String symbol,
            String yahooSymbol,
            String sourceUrl,
            String sha256,
            Instant observedAt,
            String entry) {}

    public record Parsed(
            String bundle,
            LocalDate from,
            LocalDate through,
            List<Member> members,
            List<Map<String, Object>> rows) {}

    public static byte[] freeze(
            String bundle, LocalDate from, LocalDate through, List<Source> sources)
            throws Exception {
        if (sources.isEmpty() || sources.size() > 16)
            throw new IllegalArgumentException("股票日线来源组必须为 1 至 16 个响应");
        var members = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < sources.size(); i++) {
            var s = sources.get(i);
            members.add(
                    Map.of(
                            "task",
                            s.task(),
                            "symbol",
                            s.symbol(),
                            "yahooSymbol",
                            s.yahooSymbol(),
                            "sourceUrl",
                            s.sourceUrl(),
                            "sha256",
                            s.sha256(),
                            "observedAt",
                            s.observedAt().toString(),
                            "entry",
                            entry(i)));
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(
                    zip,
                    "manifest.json",
                    JSON.writeValueAsBytes(
                            Map.of(
                                    "version",
                                    1,
                                    "bundle",
                                    bundle,
                                    "from",
                                    from.toString(),
                                    "through",
                                    through.toString(),
                                    "members",
                                    members)));
            for (int i = 0; i < sources.size(); i++) put(zip, entry(i), sources.get(i).body());
        }
        if (bytes.size() > 64 * 1024 * 1024)
            throw new IllegalArgumentException("冻结的日线来源组超过 64 MiB");
        return bytes.toByteArray();
    }

    public static Parsed parse(byte[] bytes) throws Exception {
        if (bytes.length > 64 * 1024 * 1024) throw new IllegalArgumentException("日线来源组超限");
        var entries = new HashMap<String, byte[]>();
        long total = 0;
        try (var zip =
                new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.isDirectory()
                        || !(e.getName().equals("manifest.json")
                                || e.getName().matches("[0-9]{4}\\.response"))
                        || entries.size() >= 17) throw new IllegalArgumentException("日线来源组成员非法或超限");
                int maximum = e.getName().equals("manifest.json") ? 128 * 1024 : 4 * 1024 * 1024;
                byte[] body = zip.readNBytes(maximum + 1);
                total += body.length;
                if (body.length > maximum
                        || total > 64 * 1024 * 1024
                        || entries.put(e.getName(), body) != null)
                    throw new IllegalArgumentException("日线来源组内容重复或超限");
            }
        }
        byte[] manifest = entries.remove("manifest.json");
        if (manifest == null) throw new IllegalArgumentException("日线来源组缺少清单");
        var card = JSON.readTree(manifest);
        if (card.path("version").asInt() != 1
                || !card.path("bundle").asText().matches("broad-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("日线来源组身份或版本非法");
        var from = LocalDate.parse(card.path("from").asText());
        var through = LocalDate.parse(card.path("through").asText());
        if (through.isBefore(from)) throw new IllegalArgumentException("日线来源组日期倒置");
        var refs = card.path("members");
        if (!refs.isArray() || refs.isEmpty() || refs.size() > 16 || refs.size() != entries.size())
            throw new IllegalArgumentException("清单未覆盖全部原始响应");
        var members = new ArrayList<Member>();
        var tasks = new HashSet<String>();
        var symbols = new HashSet<String>();
        var keys = new HashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < refs.size(); i++) {
            var r = refs.get(i);
            var member =
                    new Member(
                            r.path("task").asText(),
                            r.path("symbol").asText(),
                            r.path("yahooSymbol").asText(),
                            r.path("sourceUrl").asText(),
                            r.path("sha256").asText(),
                            Instant.parse(r.path("observedAt").asText()),
                            r.path("entry").asText());
            var uri = URI.create(member.sourceUrl());
            String expectedTask =
                    sha(
                            ("v1|DAILY|" + member.symbol() + "|" + through + "|")
                                    .getBytes(StandardCharsets.UTF_8));
            byte[] body = entries.remove(member.entry());
            if (!member.entry().equals(entry(i))
                    || body == null
                    || !sha(body).equals(member.sha256())
                    || !expectedTask.equals(member.task())
                    || !tasks.add(member.task())
                    || !symbols.add(member.symbol())
                    || !member.symbol().matches("[A-Z0-9.-]{1,32}")
                    || !member.yahooSymbol().matches("[A-Z0-9-]{1,32}")
                    || !"https".equals(uri.getScheme())
                    || !"query1.finance.yahoo.com".equals(uri.getHost())
                    || uri.getUserInfo() != null
                    || !uri.getPath().equals("/v8/finance/chart/" + member.yahooSymbol()))
                throw new IllegalArgumentException("日线组原文摘要、队列归属或来源地址不符");
            for (var row :
                    YahooPriceMapper.daily(
                            body,
                            member.symbol(),
                            member.yahooSymbol(),
                            member.sha256(),
                            member.observedAt(),
                            from,
                            through)) {
                if (!keys.add(
                        row.get("trade_date")
                                + "|"
                                + row.get("symbol")
                                + "|"
                                + row.get("provider")))
                    throw new IllegalArgumentException("日线组有重复完整业务键");
                rows.add(row);
                if (rows.size() > 100000) throw new IllegalArgumentException("日线组超过正式发布的十万行边界");
            }
            members.add(member);
        }
        if (!entries.isEmpty()) throw new IllegalArgumentException("日线组有未登记响应");
        // 时间只解析一次，排序仍按真实时间比较，避免大量比较重复构建日期解析器。
        var ordered =
                rows.stream()
                        .map(r -> new DatedRow(Instant.parse(r.get("trade_date").toString()), r))
                        .sorted(
                                Comparator.comparing(DatedRow::date)
                                        .thenComparing(r -> r.row().get("symbol").toString()))
                        .map(DatedRow::row)
                        .toList();
        return new Parsed(
                card.path("bundle").asText(), from, through, List.copyOf(members), ordered);
    }

    private record DatedRow(Instant date, Map<String, Object> row) {}

    private static String entry(int i) {
        return String.format(Locale.ROOT, "%04d.response", i);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var e = new ZipEntry(name);
        e.setTime(0);
        zip.putNextEntry(e);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
