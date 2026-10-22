package com.zoutrankil.market.btc.archive.application;

import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 月包缺失时补日包；资金费率按原 API 区间并集补缺，不重建未决任务。 */
public final class BinanceArchiveIncremental {
    private BinanceArchiveIncremental() {}

    public record FundingWindow(LocalDate from, LocalDate through) {}

    public static List<BinanceArchiveSync.Window> fallbackDays(
            Path root, List<BinanceArchiveSync.Window> windows) throws Exception {
        var result = new ArrayList<BinanceArchiveSync.Window>();
        Path queue = root.resolve("archive-queue.sqlite3");
        if (!Files.isRegularFile(queue)) return List.of();
        try (var db =
                DriverManager.getConnection(
                        "jdbc:sqlite:file:" + queue.toAbsolutePath() + "?mode=ro")) {
            for (var window : windows) {
                if (window.dataset() == BinanceArchiveDataset.FUNDING
                        || !window.uri().getPath().contains("/monthly/")) continue;
                try (var q =
                        db.prepareStatement(
                                "SELECT phase,run_id,reason FROM archive_tasks WHERE id=?")) {
                    q.setString(1, window.id());
                    try (var r = q.executeQuery()) {
                        // 仅未发送的官方 404 可转日包；校验错误和正式未决不能绕过。
                        if (!r.next()
                                || !r.getString(1).equals("SKIPPED_SOURCE")
                                || r.getString(2) != null
                                || r.getString(3) == null
                                || !r.getString(3).contains("来源 HTTP 404")) continue;
                    }
                }
                for (var day = window.from(); !day.isAfter(window.through()); day = day.plusDays(1))
                    result.add(
                            new BinanceArchiveSync.Window(
                                    window.dataset(), window.dataset().daily(day), day, day));
            }
        }
        return List.copyOf(result);
    }

    public static List<FundingWindow> fundingWindows(Path root, LocalDate from, LocalDate through)
            throws Exception {
        if (through.isBefore(from)) throw new IllegalArgumentException("资金费率起止范围无效");
        var result = new ArrayList<FundingWindow>();
        Path queue = root.resolve("archive-queue.sqlite3");
        if (!Files.isRegularFile(queue)) throw new IllegalStateException("先登记原归档队列");
        try (var db =
                DriverManager.getConnection(
                        "jdbc:sqlite:file:" + queue.toAbsolutePath() + "?mode=ro")) {
            boolean hasTails;
            try (var s = db.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT name FROM sqlite_master WHERE name='funding_api_tails'")) {
                hasTails = r.next();
            }
            for (var month = YearMonth.from(from);
                    !month.isAfter(YearMonth.from(through));
                    month = month.plusMonths(1)) {
                var first = from.isAfter(month.atDay(1)) ? from : month.atDay(1);
                var last = through.isBefore(month.atEndOfMonth()) ? through : month.atEndOfMonth();
                var archive =
                        new BinanceArchiveSync.Window(
                                BinanceArchiveDataset.FUNDING,
                                BinanceArchiveDataset.FUNDING.monthly(month),
                                month.atDay(1),
                                month.atEndOfMonth());
                try (var q = db.prepareStatement("SELECT phase FROM archive_tasks WHERE id=?")) {
                    q.setString(1, archive.id());
                    try (var r = q.executeQuery()) {
                        if (r.next()
                                && List.of("VERIFIED", "PARTIAL_VERIFIED", "PUBLISHING", "FETCHED")
                                        .contains(r.getString(1))) continue;
                    }
                }
                var reserved = new HashSet<LocalDate>();
                if (hasTails) {
                    try (var q =
                            db.prepareStatement(
                                    "SELECT from_date,through_date,phase FROM funding_api_tails WHERE from_date<=? AND through_date>=? ORDER BY from_date,through_date")) {
                        q.setString(1, last.toString());
                        q.setString(2, first.toString());
                        try (var r = q.executeQuery()) {
                            while (r.next()) {
                                var oldFrom = LocalDate.parse(r.getString(1));
                                var oldThrough = LocalDate.parse(r.getString(2));
                                for (var day = oldFrom;
                                        !day.isAfter(oldThrough);
                                        day = day.plusDays(1)) reserved.add(day);
                                // 原任务保留范围；未决只交原运行核对，不改小区间躲锁。
                                if (r.getString(3).equals("PUBLISHING"))
                                    throw new IllegalStateException("资金费率 API 原发布未决，先核对原运行");
                                if (!r.getString(3).equals("VERIFIED")) {
                                    if (oldFrom.isBefore(from) || oldThrough.isAfter(through))
                                        throw new IllegalStateException("原 API 任务越出本轮范围，先处理原任务");
                                    result.add(new FundingWindow(oldFrom, oldThrough));
                                }
                            }
                        }
                    }
                }
                LocalDate begin = null;
                for (var day = first; !day.isAfter(last.plusDays(1)); day = day.plusDays(1)) {
                    boolean missing = !day.isAfter(last) && !reserved.contains(day);
                    if (missing && begin == null) begin = day;
                    if (!missing && begin != null) {
                        result.add(new FundingWindow(begin, day.minusDays(1)));
                        begin = null;
                    }
                }
            }
        }
        return List.copyOf(result);
    }
}
