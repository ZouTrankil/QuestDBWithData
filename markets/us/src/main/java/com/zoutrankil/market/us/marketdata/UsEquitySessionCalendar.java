package com.zoutrankil.market.us.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetDefinition.Capability;
import com.zoutrankil.data.domain.DatasetDefinition.Column;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import com.zoutrankil.data.domain.DatasetDefinition.Partition;
import com.zoutrankil.data.domain.DatasetDefinition.StorageType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 只使用已核对的年度日历；供应商的休市日零量补齐记录保留在原表，研究视图只取真实开市时段。 */
public final class UsEquitySessionCalendar {
    public static final String RESOURCE = "market-us/calendars/us-equities-2026.json";
    public static final String VIEW = "us_equity_intraday_regular";
    public static final DatasetDefinition REGULAR = regularDefinition();
    private final JsonNode card;
    private final Set<LocalDate> closed;
    private final Map<LocalDate, LocalTime> early;
    private final int year;
    private final ZoneId zone;
    private final LocalTime open, close;

    private UsEquitySessionCalendar(JsonNode card) {
        this.card = card;
        year = card.path("year").asInt();
        zone = ZoneId.of(card.path("zone").asText());
        open = LocalTime.parse(card.path("regularOpen").asText());
        close = LocalTime.parse(card.path("regularClose").asText());
        if (year != 2026
                || !zone.equals(ZoneId.of("America/New_York"))
                || !version().matches("[a-z0-9-]+")
                || !close.isAfter(open)) throw new IllegalArgumentException("未核对的美国年度日历");
        var dates = new HashSet<LocalDate>();
        for (var d : card.path("closedDates")) {
            var date = LocalDate.parse(d.asText());
            if (date.getYear() != year || !dates.add(date))
                throw new IllegalArgumentException("日历休市日期越界或重复");
        }
        closed = Set.copyOf(dates);
        var half = new HashMap<LocalDate, LocalTime>();
        card.path("earlyCloseDates")
                .fields()
                .forEachRemaining(
                        e -> {
                            var date = LocalDate.parse(e.getKey());
                            var time = LocalTime.parse(e.getValue().asText());
                            if (date.getYear() != year
                                    || closed.contains(date)
                                    || !time.isAfter(open)
                                    || !time.isBefore(close))
                                throw new IllegalArgumentException("半日市日期或时段非法");
                            half.put(date, time);
                        });
        early = Map.copyOf(half);
        if (closed.size() != 10 || early.size() != 2)
            throw new IllegalArgumentException("年度日历完整范围尚未核对");
    }

    public static UsEquitySessionCalendar load() throws Exception {
        try (var input =
                UsEquitySessionCalendar.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("年度交易日历资源缺失");
            return new UsEquitySessionCalendar(new ObjectMapper().readTree(input));
        }
    }

    public String version() {
        return card.path("version").asText();
    }

    public JsonNode evidence() {
        return card.deepCopy();
    }

    public record Session(Instant open, Instant close) {}

    public Optional<Session> session(LocalDate day) {
        if (day.getYear() != year) throw new IllegalArgumentException("该年份没有已核对日历，不能按工作日猜测");
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY
                || day.getDayOfWeek() == DayOfWeek.SUNDAY
                || closed.contains(day)) return Optional.empty();
        return Optional.of(
                new Session(
                        day.atTime(open).atZone(zone).toInstant(),
                        day.atTime(early.getOrDefault(day, close)).atZone(zone).toInstant()));
    }

    public List<LocalDate> openDates() {
        return LocalDate.of(year, 1, 1)
                .datesUntil(LocalDate.of(year + 1, 1, 1))
                .filter(d -> session(d).isPresent())
                .toList();
    }

    public void requireRetainedSources(byte[] calendar, byte[] hours) throws Exception {
        if (!sha(calendar).equals(card.path("calendarSourceSha256").asText())
                || !sha(hours).equals(card.path("hoursSourceSha256").asText()))
            throw new IllegalArgumentException("当前日历版本与保留的官方原文摘要不符");
        String heading = "U.S. Equity and Options Markets Holiday Schedule " + year,
                text = new String(calendar, StandardCharsets.UTF_8);
        int at = text.indexOf(heading);
        if (at < 0) throw new IllegalArgumentException("官方日历年份不符");
        var table =
                Pattern.compile("(?is)<table\\b[^>]*>(.*?)</table>")
                        .matcher(text.substring(at + heading.length()));
        if (!table.find()) throw new IllegalArgumentException("官方年度日历表格缺失");
        var holidays = new HashSet<LocalDate>();
        var half = new HashMap<LocalDate, LocalTime>();
        var rows = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(table.group(1));
        while (rows.find()) {
            var cells = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>").matcher(rows.group(1));
            var values = new ArrayList<String>();
            while (cells.find()) values.add(clean(cells.group(1)));
            if (values.isEmpty()) continue;
            if (values.size() != 3) throw new IllegalArgumentException("官方年度日历字段数变化");
            var date =
                    LocalDate.parse(
                            values.getFirst(),
                            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US));
            if (date.getYear() != year || holidays.contains(date) || half.containsKey(date))
                throw new IllegalArgumentException("官方日历日期越界或重复");
            if (values.get(2).equals("Closed")) holidays.add(date);
            else if (values.get(2).equals("1:00 p.m.")) half.put(date, LocalTime.of(13, 0));
            else throw new IllegalArgumentException("官方日历出现未核对时段");
        }
        if (!closed.equals(holidays)
                || !early.equals(half)
                || !open.equals(LocalTime.of(9, 30))
                || !close.equals(LocalTime.of(16, 0))
                || !clean(new String(hours, StandardCharsets.UTF_8))
                        .contains("Core Trading Session: 9:30 a.m. to 4:00 p.m. ET"))
            throw new IllegalArgumentException("版本化日历事实与官方原文不一致");
    }

    private static String clean(String text) {
        return text.replaceAll("(?is)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public String selectSql() {
        String wall = "(cast(to_timezone(\"ts\",'America/New_York') as long)%86400000000)";
        var halfCases =
                early.keySet().stream()
                        .sorted()
                        .map(d -> "WHEN \"trade_date\"='" + d + "' THEN " + micros(early.get(d)))
                        .toList();
        // 只投影原值和日历版本，不生成、删除或覆盖任何来源价格；本版本仅覆盖 2026 年。
        return "SELECT "
                + String.join(
                        ",",
                        UsMarketDatasets.MINUTE.storageColumns().stream()
                                .map(c -> "\"" + c + "\"")
                                .toList())
                + ",cast('"
                + version()
                + "' as varchar) AS \"calendar_version\" FROM \"us_equity_intraday\" WHERE \"trade_date\" IN ("
                + String.join(",", openDates().stream().map(d -> "'" + d + "'").toList())
                + ") AND date_trunc('day',to_timezone(\"ts\",'America/New_York'))=\"trade_date\" AND \"interval_minutes\" IN (1,5) AND "
                + wall
                + ">="
                + micros(open)
                + " AND "
                + wall
                + "<CASE "
                + String.join(" ", halfCases)
                + " ELSE "
                + micros(close)
                + " END";
    }

    private static long micros(LocalTime time) {
        return time.toNanoOfDay() / 1000;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static DatasetDefinition regularDefinition() {
        var columns = new ArrayList<>(UsMarketDatasets.MINUTE.columns());
        columns.add(
                new Column(
                        "derived:approved_session_calendar",
                        "calendar_version",
                        "calendar_version",
                        StorageType.VARCHAR,
                        false,
                        "已核对的年度交易日历版本；不把休市日补齐记录当作交易行情",
                        null));
        return new DatasetDefinition(
                VIEW,
                1,
                "market.us.regular_session",
                "java.market.us",
                VIEW,
                ObjectKind.VIEW,
                columns,
                UsMarketDatasets.MINUTE.businessKey(),
                List.of(),
                "ts",
                Partition.NONE,
                false,
                Set.of(Capability.READ),
                List.of(UsMarketDatasets.MINUTE.datasetId()),
                "当前日历版本仅覆盖 2026 年；按纽约正常交易时段读取原有价格，保留原表和全部来源值，视图只读。");
    }
}
