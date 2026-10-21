package com.zoutrankil.market.btc.archive.application;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveDefinitions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 状态只读原队列和远端统计；计数和最大时间不充当来源完整证据。 */
public final class BinanceArchiveStatus {
    private BinanceArchiveStatus() {}

    public static Map<String, Object> inspect(Path root, VerifiedMarketDataStore store)
            throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("observedAt", Instant.now().toString());
        result.put("dataVerified", false);
        Path queue = root.resolve("archive-queue.sqlite3");
        var counts = new ArrayList<Object>();
        var issues = new ArrayList<Object>();
        var tails = new ArrayList<Object>();
        if (Files.isRegularFile(queue)) {
            try (var db =
                            DriverManager.getConnection(
                                    "jdbc:sqlite:file:" + queue.toAbsolutePath() + "?mode=ro");
                    var s = db.createStatement()) {
                try (var r =
                        s.executeQuery(
                                "SELECT dataset,phase,count(*) FROM archive_tasks GROUP BY dataset,phase ORDER BY dataset,phase")) {
                    while (r.next())
                        counts.add(
                                Map.of(
                                        "dataset",
                                        r.getString(1),
                                        "phase",
                                        r.getString(2),
                                        "tasks",
                                        r.getLong(3)));
                }
                try (var r =
                        s.executeQuery(
                                "SELECT id,dataset,url,phase,run_id,reason FROM archive_tasks WHERE phase IN ('SKIPPED_SOURCE','PUBLISHING','PARTIAL_VERIFIED') ORDER BY dataset,url LIMIT 1000")) {
                    while (r.next()) {
                        var item = new LinkedHashMap<String, Object>();
                        item.put("task", r.getString(1));
                        item.put("dataset", r.getString(2));
                        item.put("url", r.getString(3));
                        item.put("phase", r.getString(4));
                        item.put("runId", r.getString(5));
                        item.put("reason", r.getString(6));
                        issues.add(item);
                    }
                }
                boolean hasTails;
                try (var r =
                        s.executeQuery(
                                "SELECT name FROM sqlite_master WHERE name='funding_api_tails'")) {
                    hasTails = r.next();
                }
                if (hasTails)
                    try (var r =
                            s.executeQuery(
                                    "SELECT from_date,through_date,phase,run_id,reason FROM funding_api_tails ORDER BY from_date,through_date LIMIT 1000")) {
                        while (r.next()) {
                            var item = new LinkedHashMap<String, Object>();
                            item.put("from", r.getString(1));
                            item.put("through", r.getString(2));
                            item.put("phase", r.getString(3));
                            item.put("runId", r.getString(4));
                            item.put("reason", r.getString(5));
                            tails.add(item);
                        }
                    }
            }
        }
        result.put("fundingApiTails", tails);
        result.put("queueCounts", counts);
        result.put("issues", issues);
        var tables = new ArrayList<Object>();
        for (var dataset : BinanceArchiveDataset.values()) {
            var definition = BinanceArchiveDefinitions.definition(dataset);
            String name = definition.objectName(), time = definition.designatedTimestamp();
            var metadata =
                    store.query(
                            "SELECT id,directoryName,walEnabled,table_type FROM tables() WHERE table_name='"
                                    + name
                                    + "'");
            var item = new LinkedHashMap<String, Object>();
            item.put("dataset", dataset.id());
            item.put("table", name);
            item.put("metadata", metadata);
            if (metadata.size() == 1) {
                item.put(
                        "range",
                        store.query(
                                "SELECT min(\""
                                        + time
                                        + "\") first_time,max(\""
                                        + time
                                        + "\") last_time,count() rows FROM \""
                                        + name
                                        + "\""));
                item.put("walSettled", store.settled(name));
            }
            tables.add(item);
        }
        result.put("tables", tables);
        return result;
    }
}
