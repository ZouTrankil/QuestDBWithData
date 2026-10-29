package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 同一截止日和 Yahoo 代码只保留原归属；隔离未绑定别名，不合并证券身份或改旧成功记录。 */
final class UsYahooAliasOwnership {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Review(
            String task,
            String symbol,
            String yahoo,
            String ownerTask,
            String ownerSymbol,
            String sourcePhaseBeforeReview,
            String through) {}

    private record Task(
            long row,
            String id,
            String symbol,
            String yahoo,
            String phase,
            String proof,
            int priority,
            String bundle) {}

    static List<Review> hold(Connection db, LocalDate through) throws Exception {
        var groups = new LinkedHashMap<String, List<Task>>();
        try (var s = db.createStatement();
                var rows =
                        s.executeQuery(
                                "SELECT rowid,id,symbol,yahoo,phase,proof,priority,bundle_id FROM broad_tasks WHERE kind='DAILY' ORDER BY rowid")) {
            while (rows.next()) {
                String symbol = rows.getString(3);
                String expected =
                        VerifiedMarketDataStore.sha(
                                ("v1|DAILY|" + symbol + "|" + through + "|")
                                        .getBytes(StandardCharsets.UTF_8));
                // 原任务没有日期列，必须重算原 v1 指纹；另一截止日的任务不能参与归属判断。
                if (!rows.getString(2).equals(expected)) continue;
                String yahoo = rows.getString(4);
                if (yahoo == null || yahoo.isBlank()) continue;
                groups.computeIfAbsent(yahoo, key -> new ArrayList<>())
                        .add(
                                new Task(
                                        rows.getLong(1),
                                        rows.getString(2),
                                        symbol,
                                        yahoo,
                                        rows.getString(5),
                                        rows.getString(6),
                                        rows.getInt(7),
                                        rows.getString(8)));
            }
        }
        var order =
                Comparator.comparingInt(
                                (Task t) ->
                                        t.phase().equals("VERIFIED")
                                                ? 0
                                                : t.bundle() != null
                                                                || t.phase().equals("PUBLISHING")
                                                        ? 1
                                                        : 2)
                        .thenComparingInt(Task::priority)
                        .thenComparingLong(Task::row);
        var reviews = new ArrayList<Review>();
        boolean auto = db.getAutoCommit();
        if (auto) db.setAutoCommit(false);
        var savepoint = db.setSavepoint();
        try {
            for (var group : groups.values()) {
                if (group.size() < 2) continue;
                Task owner = group.stream().min(order).orElseThrow();
                for (var task : group) {
                    if (task.id().equals(owner.id())
                            || task.bundle() != null
                            || !List.of("PLANNED", "FETCHED", "FAILED_SOURCE")
                                    .contains(task.phase())) continue;
                    var review =
                            new Review(
                                    task.id(),
                                    task.symbol(),
                                    task.yahoo(),
                                    owner.id(),
                                    owner.symbol(),
                                    task.phase(),
                                    through.toString());
                    String proof =
                            JSON.writeValueAsString(
                                    Map.of(
                                            "reason",
                                            "YAHOO_ALIAS_ALREADY_OWNED",
                                            "ownership",
                                            review,
                                            "ownerPhase",
                                            owner.phase(),
                                            "priorProof",
                                            Objects.toString(task.proof(), ""),
                                            "scope",
                                            "保留原文、失败和成功归属；不把同一 Yahoo 代码再写成另一价格键，历史身份仍须核对"));
                    try (var update =
                            db.prepareStatement(
                                    "UPDATE broad_tasks SET phase='SCOPE_REVIEW_REQUIRED',proof=?,updated_at=? WHERE id=? AND phase=? AND bundle_id IS NULL")) {
                        update.setString(1, proof);
                        update.setString(2, Instant.now().toString());
                        update.setString(3, task.id());
                        update.setString(4, task.phase());
                        if (update.executeUpdate() != 1)
                            throw new IllegalStateException("别名复核期间原任务状态变化，保留原归属");
                    }
                    reviews.add(review);
                }
            }
            if (auto) db.commit();
            else db.releaseSavepoint(savepoint);
            return List.copyOf(reviews);
        } catch (Exception e) {
            if (auto) db.rollback();
            else db.rollback(savepoint);
            throw e;
        } finally {
            if (auto) db.setAutoCommit(true);
        }
    }
}
