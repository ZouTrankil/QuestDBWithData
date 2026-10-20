package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.UsMarketDatasets;
import com.zoutrankil.market.us.marketdata.UsYahooAnnualBundle;
import com.zoutrankil.market.us.marketdata.UsYahooDailyBundle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 年度意图先落盘，成功段保留原运行；只继续未发送段，不自动重发 PUBLISHING。 */
final class UsAnnualDailyPublication {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Published(String run, long rows, Map<String, Object> receipt) {}

    record State(String phase, String run, String proof) {}

    interface Port {
        Published publish(
                UsYahooAnnualBundle.Parsed bundle,
                UsYahooAnnualBundle.Window window,
                List<Map<String, Object>> rows,
                Path raw,
                String sha)
                throws Exception;

        Map<String, Object> verify(
                String run,
                UsYahooAnnualBundle.Parsed bundle,
                UsYahooAnnualBundle.Window window,
                List<Map<String, Object>> rows,
                Path raw,
                String sha)
                throws Exception;
    }

    @FunctionalInterface
    interface Progress {
        void accept(String phase, String detail) throws Exception;
    }

    private final Port port;

    UsAnnualDailyPublication(Port port) {
        this.port = port;
    }

    UsAnnualDailyPublication(Path root, VerifiedMarketDataStore store) {
        this(
                new Port() {
                    public Published publish(
                            UsYahooAnnualBundle.Parsed b,
                            UsYahooAnnualBundle.Window w,
                            List<Map<String, Object>> rows,
                            Path raw,
                            String sha)
                            throws Exception {
                        var result =
                                store.publish(
                                        UsMarketDatasets.DAILY,
                                        rows,
                                        raw,
                                        sha,
                                        Map.of(
                                                "bundle",
                                                b.bundle(),
                                                "window",
                                                w.number(),
                                                "requestedFrom",
                                                w.from().toString(),
                                                "requestedThrough",
                                                w.through().toString(),
                                                "layout",
                                                "CALENDAR_YEAR"),
                                        new VerifiedMarketDataStore.PublicationPage(
                                                b.page().rows(), b.page().bytes()));
                        if (!result.state().toString().equals("VERIFIED")
                                || result.errorCode() != null
                                || result.sourceRows() != rows.size()
                                || result.verifiedRows() != rows.size())
                            throw new IllegalStateException("原年度正式运行未完整验收");
                        return new Published(
                                result.runId(), result.verifiedRows(), Map.of("result", result));
                    }

                    public Map<String, Object> verify(
                            String run,
                            UsYahooAnnualBundle.Parsed b,
                            UsYahooAnnualBundle.Window w,
                            List<Map<String, Object>> rows,
                            Path raw,
                            String sha)
                            throws Exception {
                        var prepared =
                                JSON.readTree(
                                        Files.readAllBytes(
                                                root.resolve("prepared/" + run + ".json")));
                        var evidence = prepared.path("evidence");
                        if (!evidence.path("bundle").asText().equals(b.bundle())
                                || !evidence.path("window").isIntegralNumber()
                                || evidence.path("window").asInt() != w.number()
                                || !evidence.path("requestedFrom")
                                        .asText()
                                        .equals(w.from().toString())
                                || !evidence.path("requestedThrough")
                                        .asText()
                                        .equals(w.through().toString())
                                || !prepared.path("definition")
                                        .path("datasetId")
                                        .asText()
                                        .equals(UsMarketDatasets.DAILY.datasetId())
                                || !prepared.path("sourceSha256").asText().equals(sha)
                                || !Path.of(prepared.path("raw").asText())
                                        .toAbsolutePath()
                                        .normalize()
                                        .equals(raw.toAbsolutePath().normalize())
                                || !prepared.path("rows")
                                        .equals(
                                                JSON.valueToTree(
                                                        rows.stream()
                                                                .map(
                                                                        r ->
                                                                                VerifiedMarketDataStore
                                                                                        .normalize(
                                                                                                UsMarketDatasets
                                                                                                        .DAILY,
                                                                                                r))
                                                                .toList())))
                            throw new IllegalStateException("原运行没有覆盖本次完整年度窗口");
                        return store.verifyExistingBundle(
                                run, UsMarketDatasets.DAILY, rows, raw, sha, b.bundle());
                    }
                });
    }

    static void createTables(Connection db) throws SQLException {
        try (var s = db.createStatement()) {
            s.execute(
                    "CREATE TABLE IF NOT EXISTS broad_bundle_windows(bundle_id TEXT NOT NULL,window_no INT NOT NULL,from_date TEXT NOT NULL,through_date TEXT NOT NULL,expected_rows INT NOT NULL,phase TEXT NOT NULL,run_id TEXT,proof TEXT,updated_at TEXT NOT NULL,PRIMARY KEY(bundle_id,window_no))");
        }
    }

    static void begin(Connection db, UsYahooAnnualBundle.Parsed b, Path raw, String sha)
            throws Exception {
        db.setAutoCommit(false);
        try {
            try (var s =
                    db.prepareStatement(
                            "INSERT INTO broad_bundles VALUES(?,?,?,'PUBLISHING',NULL,?)")) {
                s.setString(1, b.bundle());
                s.setString(2, raw.toString());
                s.setString(3, sha);
                s.setString(4, Instant.now().toString());
                s.executeUpdate();
            }
            try (var s =
                    db.prepareStatement(
                            "UPDATE broad_tasks SET phase='PUBLISHING',bundle_id=?,updated_at=? WHERE id=? AND kind='DAILY' AND phase='FETCHED' AND sha=? AND observed=? AND symbol=? AND yahoo=? AND bundle_id IS NULL")) {
                for (var source : b.sources()) {
                    s.setString(1, b.bundle());
                    s.setString(2, Instant.now().toString());
                    s.setString(3, source.task());
                    s.setString(4, source.sha256());
                    s.setString(5, source.observedAt().toString());
                    s.setString(6, source.symbol());
                    s.setString(7, source.yahooSymbol());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("年度组原来源状态或证据变化");
                }
            }
            try (var s =
                    db.prepareStatement(
                            "INSERT INTO broad_bundle_windows VALUES(?,?,?,?,?,'PLANNED',NULL,NULL,?)")) {
                for (var w : b.windows()) {
                    s.setString(1, b.bundle());
                    s.setInt(2, w.number());
                    s.setString(3, w.from().toString());
                    s.setString(4, w.through().toString());
                    s.setInt(5, w.expectedRows());
                    s.setString(6, Instant.now().toString());
                    s.executeUpdate();
                }
            }
            db.commit();
        } catch (Exception error) {
            db.rollback();
            throw error;
        } finally {
            db.setAutoCommit(true);
        }
    }

    static List<State> states(Connection db, UsYahooAnnualBundle.Parsed b) throws Exception {
        var states = new ArrayList<State>();
        try (var s =
                db.prepareStatement(
                        "SELECT window_no,from_date,through_date,expected_rows,phase,run_id,proof FROM broad_bundle_windows WHERE bundle_id=? ORDER BY window_no")) {
            s.setString(1, b.bundle());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    int i = states.size();
                    if (i >= b.windows().size()) throw new IllegalStateException("年度队列出现额外窗口");
                    var w = b.windows().get(i);
                    var state = new State(r.getString(5), r.getString(6), r.getString(7));
                    if (r.getInt(1) != i
                            || !r.getString(2).equals(w.from().toString())
                            || !r.getString(3).equals(w.through().toString())
                            || r.getInt(4) != w.expectedRows()
                            || !List.of("PLANNED", "PUBLISHING", "VERIFIED").contains(state.phase())
                            || !state.phase().equals("VERIFIED")
                                    && (state.run() != null || state.proof() != null)
                            || state.phase().equals("VERIFIED")
                                    && (state.proof() == null
                                            || (w.expectedRows() == 0
                                                    ? state.run() != null
                                                    : state.run() == null
                                                            || !state.run()
                                                                    .matches(
                                                                            "market-[a-f0-9-]{36}"))))
                        throw new IllegalStateException("年度队列与冻结清单或原状态不符");
                    states.add(state);
                }
            }
        }
        if (states.size() != b.windows().size()) throw new IllegalStateException("年度队列缺少冻结窗口");
        var members = new HashMap<String, UsYahooDailyBundle.Source>();
        for (var source : b.sources()) members.put(source.task(), source);
        try (var s =
                db.prepareStatement(
                        "SELECT id,phase,sha,observed,symbol,yahoo FROM broad_tasks WHERE bundle_id=?")) {
            s.setString(1, b.bundle());
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var source = members.remove(r.getString(1));
                    if (source == null
                            || !r.getString(2).equals("PUBLISHING")
                            || !r.getString(3).equals(source.sha256())
                            || !r.getString(4).equals(source.observedAt().toString())
                            || !r.getString(5).equals(source.symbol())
                            || !r.getString(6).equals(source.yahooSymbol()))
                        throw new IllegalStateException("年度原成员或来源证据变化");
                }
            }
        }
        if (!members.isEmpty()) throw new IllegalStateException("年度组缺少原成员");
        return states;
    }

    boolean publishRemaining(
            Connection db,
            UsYahooAnnualBundle.Parsed b,
            Path raw,
            String sha,
            BooleanSupplier stopped,
            Progress status)
            throws Exception {
        requireHeader(db, b, raw, sha);
        var states = states(db, b);
        if (states.stream().anyMatch(s -> s.phase().equals("PUBLISHING")))
            throw new IllegalStateException("原年度窗口未核对，禁止自动重发；使用 reconcile-broad-window");
        // 重启时先逐字段重验已经成功的原运行，不能仅凭窗口状态跳过旧数据。
        for (var w : b.windows())
            if (states.get(w.number()).phase().equals("VERIFIED"))
                verify(states.get(w.number()), b, w, raw, sha);
        for (var w : b.windows()) {
            if (states.get(w.number()).phase().equals("VERIFIED")) continue;
            if (stopped.getAsBoolean()) return false;
            requireRaw(raw, sha);
            status.accept("PUBLISHING_WINDOW", b.bundle() + "/" + w.from().getYear());
            if (w.expectedRows() == 0) {
                complete(db, b, w, "PLANNED", null, Map.of("emptySourceWindow", true));
                continue;
            }
            // 事务提交后才交给正式写入方；崩溃后这个窗口永远不会被当作未发送。
            try (var s =
                    db.prepareStatement(
                            "UPDATE broad_bundle_windows SET phase='PUBLISHING',updated_at=? WHERE bundle_id=? AND window_no=? AND phase='PLANNED' AND run_id IS NULL AND proof IS NULL")) {
                s.setString(1, Instant.now().toString());
                s.setString(2, b.bundle());
                s.setInt(3, w.number());
                if (s.executeUpdate() != 1) throw new IllegalStateException("年度原发送意图变化");
            }
            var rows = b.rows(w);
            var result = port.publish(b, w, rows, raw, sha);
            if (result.rows() != w.expectedRows() || !result.run().matches("market-[a-f0-9-]{36}"))
                throw new IllegalStateException("年度正式回执范围不符");
            complete(db, b, w, "PUBLISHING", result.run(), result.receipt());
            status.accept("VERIFIED_WINDOW", b.bundle() + "/" + w.from().getYear());
        }
        // 全部写完再按原运行重读每段；只有当前完整数据、原对象和 WAL 都通过才推进成员。
        status.accept("VERIFYING_ANNUAL_GROUP", b.bundle());
        states = states(db, b);
        var proofs = new ArrayList<Map<String, Object>>();
        for (var w : b.windows()) {
            var state = states.get(w.number());
            if (!state.phase().equals("VERIFIED")) throw new IllegalStateException("年度来源尚未全部验收");
            proofs.add(
                    Map.of(
                            "window",
                            w.number(),
                            "from",
                            w.from().toString(),
                            "through",
                            w.through().toString(),
                            "rows",
                            w.expectedRows(),
                            "verification",
                            verify(state, b, w, raw, sha)));
        }
        finish(db, b, proofs);
        status.accept("VERIFIED_GROUP", b.bundle());
        return true;
    }

    void reconcile(
            Connection db,
            UsYahooAnnualBundle.Parsed b,
            int number,
            String run,
            Path raw,
            String sha)
            throws Exception {
        if (number < 0 || number >= b.windows().size() || !run.matches("market-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("原年度窗口或运行非法");
        requireHeader(db, b, raw, sha);
        var states = states(db, b);
        var w = b.windows().get(number);
        if (!states.get(number).phase().equals("PUBLISHING") || w.expectedRows() == 0)
            throw new IllegalArgumentException("只核对原未决非空窗口");
        var proof = port.verify(run, b, w, b.rows(w), raw, sha);
        complete(
                db,
                b,
                w,
                "PUBLISHING",
                run,
                Map.of("fullReadbackProof", proof, "reconciledRun", run));
    }

    private Map<String, Object> verify(
            State state,
            UsYahooAnnualBundle.Parsed b,
            UsYahooAnnualBundle.Window w,
            Path raw,
            String sha)
            throws Exception {
        requireRaw(raw, sha);
        var rows = b.rows(w);
        if (w.expectedRows() == 0) {
            if (!rows.isEmpty()
                    || state.run() != null
                    || !JSON.readTree(state.proof()).path("emptySourceWindow").asBoolean())
                throw new IllegalStateException("原空窗口证据不符");
            return Map.of("emptySourceWindow", true);
        }
        return port.verify(state.run(), b, w, rows, raw, sha);
    }

    private static void complete(
            Connection db,
            UsYahooAnnualBundle.Parsed b,
            UsYahooAnnualBundle.Window w,
            String phase,
            String run,
            Map<String, Object> proof)
            throws Exception {
        try (var s =
                db.prepareStatement(
                        "UPDATE broad_bundle_windows SET phase='VERIFIED',run_id=?,proof=?,updated_at=? WHERE bundle_id=? AND window_no=? AND phase=?")) {
            s.setString(1, run);
            s.setString(2, JSON.writeValueAsString(proof));
            s.setString(3, Instant.now().toString());
            s.setString(4, b.bundle());
            s.setInt(5, w.number());
            s.setString(6, phase);
            if (s.executeUpdate() != 1) throw new IllegalStateException("年度原检查点变化");
        }
    }

    private static void finish(
            Connection db, UsYahooAnnualBundle.Parsed b, List<Map<String, Object>> windows)
            throws Exception {
        String proof =
                JSON.writeValueAsString(
                        Map.of(
                                "version",
                                2,
                                "bundle",
                                b.bundle(),
                                "members",
                                b.sources().size(),
                                "rows",
                                b.totalRows(),
                                "verification",
                                Map.of("allWindowsVerified", true, "windows", windows)));
        db.setAutoCommit(false);
        try {
            try (var s =
                    db.prepareStatement(
                            "UPDATE broad_bundles SET phase='VERIFIED',proof=?,updated_at=? WHERE id=? AND phase='PUBLISHING'")) {
                s.setString(1, proof);
                s.setString(2, Instant.now().toString());
                s.setString(3, b.bundle());
                if (s.executeUpdate() != 1) throw new IllegalStateException("年度原组状态变化");
            }
            try (var s =
                    db.prepareStatement(
                            "UPDATE broad_tasks SET phase='VERIFIED',proof=?,updated_at=? WHERE id=? AND phase='PUBLISHING' AND bundle_id=? AND sha=?")) {
                for (var source : b.sources()) {
                    s.setString(1, proof);
                    s.setString(2, Instant.now().toString());
                    s.setString(3, source.task());
                    s.setString(4, b.bundle());
                    s.setString(5, source.sha256());
                    if (s.executeUpdate() != 1) throw new IllegalStateException("年度原成员状态变化");
                }
            }
            db.commit();
        } catch (Exception error) {
            db.rollback();
            throw error;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static void requireRaw(Path raw, String sha) throws Exception {
        if (!VerifiedMarketDataStore.sha(Files.readAllBytes(raw)).equals(sha))
            throw new IllegalStateException("年度冻结 ZIP 变化");
    }

    private static void requireHeader(
            Connection db, UsYahooAnnualBundle.Parsed b, Path raw, String sha) throws Exception {
        requireRaw(raw, sha);
        try (var s = db.prepareStatement("SELECT raw,sha,phase FROM broad_bundles WHERE id=?")) {
            s.setString(1, b.bundle());
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || !Path.of(r.getString(1))
                                .toAbsolutePath()
                                .normalize()
                                .equals(raw.toAbsolutePath().normalize())
                        || !r.getString(2).equals(sha)
                        || !r.getString(3).equals("PUBLISHING"))
                    throw new IllegalStateException("原年度组头与冻结来源不符");
            }
        }
    }
}
