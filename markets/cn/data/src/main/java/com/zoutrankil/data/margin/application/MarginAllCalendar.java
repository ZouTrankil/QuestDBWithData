package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.stock.margin.MarginAll;
import com.zoutrankil.data.repository.FileEvidenceStore;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 正式汇总冻结沪深一致日历及北交所沿用规则，空响应只有在当天全部休市时才合法。 */
public final class MarginAllCalendar {
    public static final String POLICY = "TUSHARE_CN_A_SHARED_V1";
    public static final Set<String> EXCHANGES = Set.of("BSE", "SSE", "SZSE");

    public record Window(
            List<String> encoded, String fingerprint, Map<LocalDate, Set<String>> openExchanges) {
        public Window {
            encoded = List.copyOf(encoded);
            var copy = new TreeMap<LocalDate, Set<String>>();
            openExchanges.forEach((d, e) -> copy.put(d, Set.copyOf(e)));
            openExchanges = Collections.unmodifiableMap(copy);
        }
    }

    private final ExchangeCalendarReadPort calendars;

    public MarginAllCalendar(ExchangeCalendarReadPort calendars) {
        this.calendars = Objects.requireNonNull(calendars);
    }

    public Window read(LocalDate from, LocalDate to) {
        int days = days(from, to);
        var encoded = new ArrayList<String>();
        var masks = new TreeMap<LocalDate, Integer>();
        for (String exchange : List.of("SSE", "SZSE")) {
            var query =
                    new DatasetReadQuery(
                            List.of("exchange", "calendar_date", "is_open", "previous_trade_date"),
                            Map.of("exchange", exchange),
                            "calendar_date",
                            from,
                            to.plusDays(1),
                            100,
                            null);
            int count = 0;
            var dates = new TreeMap<LocalDate, Boolean>();
            while (true) {
                var page = calendars.findPage(query);
                for (var row : page.rows()) {
                    if (++count > days
                            || !exchange.equals(row.exchange())
                            || row.calendarDate().isBefore(from)
                            || row.calendarDate().isAfter(to)
                            || dates.putIfAbsent(row.calendarDate(), row.open()) != null)
                        throw new IllegalStateException("汇总日历重复、越界或交易所不符");
                }
                if (!page.hasMore()) break;
                if (page.nextCursor() == null) throw new IllegalStateException("汇总日历缺分页游标");
                query = query.after(page.nextCursor());
            }
            if (count != days) throw new IllegalStateException("汇总日历缺日期：" + exchange);
            // 官方trade_cal说明北交所参考沪深；两份原日历必须齐备且一致。
            dates.forEach(
                    (date, open) -> {
                        Integer before = masks.putIfAbsent(date, open ? 7 : 0);
                        if (before != null && before != (open ? 7 : 0))
                            throw new IllegalStateException("沪深日历不一致，不能套用北交所日历规则");
                    });
        }
        // 三个位分别编码BSE/SSE/SZSE，366天也能保持在原4096字符预算内。
        masks.forEach(
                (date, mask) ->
                        encoded.add(date.format(DateTimeFormatter.BASIC_ISO_DATE) + ":" + mask));
        return validate(from, to, encoded, fingerprint(encoded));
    }

    public static Window validate(
            LocalDate from, LocalDate to, List<String> encoded, String fingerprint) {
        int count = days(from, to);
        if (encoded.size() != count || !fingerprint(encoded).equals(fingerprint))
            throw new IllegalArgumentException("汇总冻结日历数量或摘要不符");
        var all = new TreeMap<LocalDate, Set<String>>();
        var seen = new HashSet<String>();
        String previous = null;
        for (String item : encoded) {
            String[] parts = item.split(":", -1);
            if (parts.length != 2
                    || !Set.of("0", "7").contains(parts[1])
                    || previous != null && previous.compareTo(item) >= 0)
                throw new IllegalArgumentException("汇总冻结日历格式或顺序不符");
            LocalDate date = LocalDate.parse(parts[0], DateTimeFormatter.BASIC_ISO_DATE);
            if (date.isBefore(from) || date.isAfter(to) || !seen.add(parts[0]))
                throw new IllegalArgumentException("汇总冻结日历日期越界或重复");
            int mask = Integer.parseInt(parts[1]);
            var open = new TreeSet<String>();
            var exchanges = new ArrayList<>(new TreeSet<>(EXCHANGES));
            for (int i = 0; i < exchanges.size(); i++)
                if ((mask & (1 << i)) != 0) open.add(exchanges.get(i));
            all.put(date, open);
            previous = item;
        }
        if (all.size() != count) throw new IllegalArgumentException("汇总冻结日历缺日期");
        return new Window(encoded, fingerprint, all);
    }

    /** 每个开市日恰好三交易所一行；休市日只能是空来源。 */
    public static void requireCoverage(Window calendar, LocalDate date, List<MarginAll> rows) {
        var actual = new HashSet<String>();
        for (var row : rows)
            if (!date.equals(row.tradeDate()) || !actual.add(row.exchangeId()))
                throw new IllegalStateException("汇总来源日期越界或交易所重复");
        if (!actual.equals(calendar.openExchanges().get(date)))
            throw new IllegalStateException("汇总来源与冻结开休市交易所范围不符：" + date);
    }

    private static int days(LocalDate from, LocalDate to) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) + 1 > MarginAllSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("汇总日历需要1到366天范围");
        return Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
    }

    private static String fingerprint(List<String> encoded) {
        return FileEvidenceStore.sha256(
                (String.join("\n", encoded) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
