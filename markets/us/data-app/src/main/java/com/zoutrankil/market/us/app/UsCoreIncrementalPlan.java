package com.zoutrankil.market.us.app;

import com.zoutrankil.market.us.marketdata.UsEquitySessionCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 核心增量只补原队列未覆盖的交易日；失败和未决范围继续归原任务，不重新登记。 */
final class UsCoreIncrementalPlan {
    record Range(LocalDate from, LocalDate through) {}

    static List<Range> missing(LocalDate from, LocalDate through, List<Range> reserved)
            throws Exception {
        if (through.isBefore(from)) throw new IllegalArgumentException("增量日期范围倒置");
        var calendar = UsEquitySessionCalendar.load();
        calendar.session(from);
        calendar.session(through);
        var result = new ArrayList<Range>();
        LocalDate first = null, last = null;
        for (var day = from; !day.isAfter(through); day = day.plusDays(1)) {
            boolean occupied = false;
            for (var range : reserved)
                if (!day.isBefore(range.from()) && !day.isAfter(range.through())) {
                    occupied = true;
                    break;
                }
            if (occupied) {
                if (first != null) {
                    result.add(new Range(first, last));
                    first = last = null;
                }
                continue;
            }
            if (calendar.session(day).isEmpty()) continue;
            // 最多一周，来源仍按原预算限流；休市日不单独请求，也不补造报价。
            if (first != null && day.isAfter(first.plusDays(6))) {
                result.add(new Range(first, last));
                first = null;
            }
            if (first == null) first = day;
            last = day;
        }
        if (first != null) result.add(new Range(first, last));
        return List.copyOf(result);
    }

    static void requireSource(
            String kind, LocalDate from, LocalDate through, List<Map<String, Object>> rows)
            throws Exception {
        var calendar = UsEquitySessionCalendar.load();
        var wantedDays = new HashSet<LocalDate>();
        var wantedMinutes = new HashSet<Instant>();
        for (var day = from; !day.isAfter(through); day = day.plusDays(1)) {
            var session = calendar.session(day);
            if (session.isEmpty()) continue;
            wantedDays.add(day);
            for (var ts = session.get().open();
                    ts.isBefore(session.get().close());
                    ts = ts.plusSeconds(60)) wantedMinutes.add(ts);
        }
        var days = new HashSet<LocalDate>();
        var actualMinutes = new HashSet<Instant>();
        for (var row : rows) {
            // trade_date 是 UTC 零点日期标记，直接读日期，不能转纽约时区后再取日。
            var day = LocalDate.parse(row.get("trade_date").toString().substring(0, 10));
            if (day.isBefore(from) || day.isAfter(through))
                throw new IllegalArgumentException("增量来源日期越界");
            if (kind.equals("DAILY")) {
                if (!days.add(day)) throw new IllegalArgumentException("增量日线日期重复");
            } else {
                var ts = Instant.parse(row.get("ts").toString());
                if (wantedMinutes.contains(ts) && !actualMinutes.add(ts))
                    throw new IllegalArgumentException("增量分钟重复");
            }
        }
        if (kind.equals("DAILY") ? !days.equals(wantedDays) : !actualMinutes.equals(wantedMinutes))
            throw new IllegalArgumentException("增量来源缺少完整交易日或正常时段分钟，保留原件，不补零");
    }
}
