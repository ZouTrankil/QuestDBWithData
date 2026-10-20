package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.marketdata.MarketPublisherLease;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.UsEquitySessionCalendar;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 日历视图只筛选原有行，保留供应商补齐记录；不替换未知的同名对象。 */
final class UsRegularSessionView {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    static Map<String, Object> install(
            Path root, VerifiedMarketDataStore store, Path calendarSource, Path hoursSource)
            throws Exception {
        if (Files.size(calendarSource) > 2 * 1024 * 1024
                || Files.size(hoursSource) > 2 * 1024 * 1024)
            throw new IllegalArgumentException("日历原文单文件不超过 2 MiB");
        byte[] calendar = Files.readAllBytes(calendarSource),
                hours = Files.readAllBytes(hoursSource);
        var approved = UsEquitySessionCalendar.load();
        approved.requireRetainedSources(calendar, hours);
        try (var lease =
                MarketPublisherLease.acquire(
                        root.resolve("ledger-writer.lock"), Duration.ofMinutes(15))) {
            String name = UsEquitySessionCalendar.VIEW, select = approved.selectSql();
            var base =
                    store.query(
                            "SELECT id,directoryName FROM tables() WHERE table_name='us_equity_intraday'");
            if (base.size() != 1) throw new IllegalStateException("分钟原表缺失或身份不唯一");
            Path folder = root.toAbsolutePath().resolve("session-calendars/" + approved.version());
            Files.createDirectories(folder);
            writeOnce(folder.resolve("nasdaq-calendar.html"), calendar);
            writeOnce(folder.resolve("nyse-hours.html"), hours);
            writeOnce(
                    folder.resolve("approved-calendar.json"),
                    JSON.writeValueAsBytes(approved.evidence()));
            writeOnce(folder.resolve("view.sql"), select.getBytes(StandardCharsets.UTF_8));
            var intent =
                    Map.of(
                            "calendarVersion",
                            approved.version(),
                            "baseTable",
                            base.getFirst(),
                            "view",
                            name,
                            "selectSha256",
                            VerifiedMarketDataStore.sha(select.getBytes(StandardCharsets.UTF_8)),
                            "calendarEvidence",
                            approved.evidence());
            writeOnce(folder.resolve("intent.json"), JSON.writeValueAsBytes(intent));
            var existing =
                    store.query(
                            "SELECT view_sql FROM views() WHERE view_name="
                                    + VerifiedMarketDataStore.literal(name));
            if (existing.isEmpty())
                store.query(
                        "CREATE VIEW " + VerifiedMarketDataStore.q(name) + " AS (" + select + ")");
            var actual =
                    store.query(
                            "SELECT view_sql FROM views() WHERE view_name="
                                    + VerifiedMarketDataStore.literal(name));
            if (actual.size() != 1
                    || !sameSql(select, actual.getFirst().get("view_sql").toString()))
                throw new IllegalStateException("同名视图不是当前已核对日历的完整定义，禁止覆盖");
            var columns =
                    store.query(
                            "SELECT \"column\",\"type\" FROM table_columns("
                                    + VerifiedMarketDataStore.literal(name)
                                    + ")");
            var expected = UsEquitySessionCalendar.REGULAR.columns();
            if (columns.size() != expected.size()) throw new IllegalStateException("交易时段视图字段数不符");
            for (int i = 0; i < columns.size(); i++)
                if (!expected.get(i).storageName().equals(columns.get(i).get("column"))
                        || !expected.get(i).storageType().name().equals(columns.get(i).get("type")))
                    throw new IllegalStateException("交易时段视图字段或类型不符");
            if (!base.equals(
                    store.query(
                            "SELECT id,directoryName FROM tables() WHERE table_name='us_equity_intraday'")))
                throw new IllegalStateException("视图创建期间原物理对象改变");
            var receipt =
                    Map.of(
                            "view",
                            name,
                            "calendarVersion",
                            approved.version(),
                            "baseTable",
                            base.getFirst(),
                            "fieldCount",
                            columns.size(),
                            "selectSha256",
                            intent.get("selectSha256"),
                            "approvedCalendar",
                            folder.resolve("approved-calendar.json").toString(),
                            "installedAt",
                            Instant.now().toString(),
                            "scope",
                            "只建立正常交易时段过滤视图；未删除或改写原表价格，全年覆盖仍须独立核对");
            Path path = folder.resolve("verified-" + UUID.randomUUID() + ".json");
            writeOnce(path, JSON.writeValueAsBytes(receipt));
            return Map.of(
                    "receipt",
                    path.toString(),
                    "view",
                    name,
                    "calendarVersion",
                    approved.version());
        }
    }

    private static void writeOnce(Path path, byte[] bytes) throws Exception {
        if (Files.exists(path)) {
            if (!Arrays.equals(Files.readAllBytes(path), bytes))
                throw new IllegalStateException("已有日历证据与当前输入不符：" + path.getFileName());
            return;
        }
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static boolean sameSql(String expected, String actual) {
        return tokens(expected).equals(tokens(actual));
    }

    private static List<String> tokens(String sql) {
        // 字符串字面量保持原样，避免忽略版本号或时区的大小写；只归一化编译器的空白及标识符引号。
        var parts = new ArrayList<String>();
        var literals = Pattern.compile("'(?:''|[^'])*'").matcher(sql);
        var outside = new StringBuilder();
        int cursor = 0, n = 0;
        while (literals.find()) {
            outside.append(sql, cursor, literals.start())
                    .append("@literal")
                    .append(n++)
                    .append('@');
            parts.add(literals.group());
            cursor = literals.end();
        }
        outside.append(sql.substring(cursor));
        String text =
                outside.toString()
                        .replace("\"", "")
                        .replaceAll("\\s+", "")
                        .toLowerCase(Locale.ROOT)
                        .replaceFirst(";$", "");
        while (text.startsWith("(") && text.endsWith(")")) {
            int depth = 0, closed = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                if (c == ')' && --depth == 0) {
                    closed = i;
                    break;
                }
            }
            if (closed != text.length() - 1) break;
            text = text.substring(1, text.length() - 1);
        }
        parts.addFirst(text);
        return parts;
    }
}
