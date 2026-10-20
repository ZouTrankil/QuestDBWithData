package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.DailyMetricCalendar;
import com.zoutrankil.data.repository.QuestDbWriteChecks;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Same latest-mature (30 calendar days) or explicit backfill scope as Python factor_daily_metrics. */
public final class QuestDbDailyMetricPlan {
    private final JdbcTemplate jdbc;

    public QuestDbDailyMetricPlan(JdbcTemplate template) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));
        jdbc.setQueryTimeout(60);
    }

    public record Plan(
            LocalDate asOf,
            String scopePolicy,
            List<DailyMetricCalendar.Pair> pairs,
            List<Map<String, Object>> calendarState) {}

    public Plan plan(LocalDate from, LocalDate asOf) {
        Objects.requireNonNull(asOf);
        boolean latest = from == null;
        LocalDate start = latest ? asOf.minusDays(30) : from;
        if (start.isAfter(asOf) || ChronoUnit.DAYS.between(start, asOf) > 3660)
            throw new IllegalArgumentException("Bounded metric calendar range required");
        var before = state();
        var days =
                jdbc.query(
                        "SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=? AND cal_date<? ORDER BY cal_date LIMIT 3662",
                        (rs, n) -> {
                            Object flag = rs.getObject(2);
                            if (!(flag instanceof Number number)
                                    || (number.doubleValue() != 0 && number.doubleValue() != 1))
                                throw new IllegalStateException("Invalid official calendar flag");
                            return new DailyMetricCalendar.Day(
                                    "SSE", rs.getDate(1).toLocalDate(), number.doubleValue() == 1);
                        },
                        start + "T00:00:00.000000Z",
                        asOf.plusDays(1) + "T00:00:00.000000Z");
        if (days.stream().map(DailyMetricCalendar.Day::date).distinct().count() != days.size())
            throw new IllegalStateException("Duplicate official calendar dates");
        var pairs = DailyMetricCalendar.maturePairs(days, start, asOf);
        if (pairs.isEmpty())
            throw new IllegalStateException("No mature trading sessions available");
        if (!before.equals(state()))
            throw new IllegalStateException("Calendar changed during metric planning");
        return new Plan(
                asOf,
                latest ? "latest_mature_session" : "explicit_backfill",
                latest ? List.of(pairs.getLast()) : pairs,
                before);
    }

    public void requireUnchanged(Plan plan) {
        if (!plan.calendarState().equals(state()))
            throw new IllegalStateException("Metric plan calendar changed");
    }

    private List<Map<String, Object>> state() {
        if (!QuestDbWriteChecks.walSettled(jdbc, "exchange_calendar"))
            throw new IllegalStateException("Calendar WAL unsettled");
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name='exchange_calendar'");
        if (rows.size() != 1) throw new IllegalStateException("Official calendar missing");
        return rows;
    }
}
