package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.us.marketdata.UsEquitySessionCalendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class UsCoreIncrementalPlanTest {
    private static LocalDate day(String value) {
        return LocalDate.parse(value);
    }

    @Test
    void groupsOnlyNewSessionsAndDoesNothingOnWeekendOrAlreadyReservedRange() throws Exception {
        var from = day("2026-10-22");
        var through = day("2026-10-25");
        var expected = List.of(new UsCoreIncrementalPlan.Range(from, day("2026-10-23")));
        assertEquals(expected, UsCoreIncrementalPlan.missing(from, through, List.of()));
        assertTrue(UsCoreIncrementalPlan.missing(day("2026-10-24"), through, List.of()).isEmpty());
        assertTrue(UsCoreIncrementalPlan.missing(from, through, expected).isEmpty());
    }

    @Test
    void findsInteriorHoleInsteadOfUsingLargestDateAndKeepsReservedFailures() throws Exception {
        var reserved =
                List.of(
                        new UsCoreIncrementalPlan.Range(day("2026-10-19"), day("2026-10-20")),
                        new UsCoreIncrementalPlan.Range(day("2026-10-22"), day("2026-10-23")));
        assertEquals(
                List.of(new UsCoreIncrementalPlan.Range(day("2026-10-21"), day("2026-10-21"))),
                UsCoreIncrementalPlan.missing(day("2026-10-19"), day("2026-10-23"), reserved));
        // 已登记范围不因状态改变而重新发请求，失败和未决仍归原任务。
        assertTrue(
                UsCoreIncrementalPlan.missing(day("2026-10-22"), day("2026-10-23"), reserved)
                        .isEmpty());
    }

    @Test
    void boundsEachWindowAndNeverGuessesUnsupportedYear() throws Exception {
        var ranges = UsCoreIncrementalPlan.missing(day("2026-10-22"), day("2026-11-30"), List.of());
        assertTrue(ranges.size() > 1);
        for (var range : ranges) assertTrue(!range.through().isAfter(range.from().plusDays(6)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsCoreIncrementalPlan.missing(
                                day("2026-12-31"), day("2027-01-04"), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsCoreIncrementalPlan.missing(
                                day("2026-10-23"), day("2026-10-22"), List.of()));
    }

    @Test
    void validatesDailyDateMarkersWithoutNewYorkDateConversion() throws Exception {
        var first = day("2026-10-22");
        var last = day("2026-10-23");
        List<Map<String, Object>> rows =
                List.of(
                        Map.of("trade_date", "2026-10-22T00:00:00Z"),
                        Map.of("trade_date", "2026-10-23T00:00:00Z"));
        UsCoreIncrementalPlan.requireSource("DAILY", first, last, rows);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsCoreIncrementalPlan.requireSource(
                                "DAILY", first, last, rows.subList(0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        UsCoreIncrementalPlan.requireSource(
                                "DAILY", first, last, List.of(rows.getFirst(), rows.getFirst())));
        assertThrows(
                IllegalArgumentException.class,
                () -> UsCoreIncrementalPlan.requireSource("DAILY", last, last, rows));
    }

    @Test
    void validatesFullMinuteGridAcrossDstAndEarlyCloseAndRejectsMissingOrDuplicate()
            throws Exception {
        var calendar = UsEquitySessionCalendar.load();
        for (String value : List.of("2026-03-09", "2026-11-02", "2026-11-27")) {
            var d = day(value);
            var session = calendar.session(d).orElseThrow();
            var rows = new ArrayList<Map<String, Object>>();
            for (var ts = session.open(); ts.isBefore(session.close()); ts = ts.plusSeconds(60))
                rows.add(Map.of("ts", ts.toString(), "trade_date", d + "T00:00:00Z"));
            assertEquals(value.endsWith("11-27") ? 210 : 390, rows.size());
            UsCoreIncrementalPlan.requireSource("MINUTE", d, d, rows);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            UsCoreIncrementalPlan.requireSource(
                                    "MINUTE", d, d, rows.subList(1, rows.size())));
            rows.add(rows.getFirst());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> UsCoreIncrementalPlan.requireSource("MINUTE", d, d, rows));
        }
    }

    @Test
    void onlyFullVerifiedNativeResultCanAdvanceCoreQueue() {
        UsCoreCollector.requireVerified(
                new SyncJobRunner.Result("run", SyncRunState.VERIFIED, 2, 2, null), 2);
        for (var result :
                List.of(
                        new SyncJobRunner.Result("run", SyncRunState.IN_DOUBT, 2, 0, "UNCERTAIN"),
                        new SyncJobRunner.Result("run", SyncRunState.VERIFIED, 2, 1, null),
                        new SyncJobRunner.Result("run", SyncRunState.VERIFIED, 2, 2, "ERROR"),
                        new SyncJobRunner.Result("run", SyncRunState.VERIFIED_EMPTY, 0, 0, null)))
            assertThrows(
                    IllegalStateException.class, () -> UsCoreCollector.requireVerified(result, 2));
    }
}
