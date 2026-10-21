package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 月包接替日包时先比业务值；同值只换来源版本，冲突留原值并转显式修订。 */
public final class BinanceArchiveOverlap {
    private BinanceArchiveOverlap() {}

    public static String inspect(
            VerifiedMarketDataStore store,
            DatasetDefinition definition,
            List<Map<String, Object>> expected,
            Path root)
            throws Exception {
        var target =
                store.query(
                        "SELECT id,directoryName FROM tables() WHERE table_name='"
                                + definition.objectName()
                                + "'");
        if (target.isEmpty()) return null;
        if (target.size() != 1 || !store.settled(definition.objectName()))
            throw new IllegalStateException("原目标不唯一或 WAL 未结清");
        String identity =
                "market-"
                        + VerifiedMarketDataStore.sha(
                                new ObjectMapper().writeValueAsBytes(target.getFirst()));
        var first =
                expected.stream()
                        .map(r -> Instant.parse(r.get(definition.designatedTimestamp()).toString()))
                        .min(Instant::compareTo)
                        .orElseThrow();
        var last =
                expected.stream()
                        .map(r -> Instant.parse(r.get(definition.designatedTimestamp()).toString()))
                        .max(Instant::compareTo)
                        .orElseThrow();
        String time = VerifiedMarketDataStore.q(definition.designatedTimestamp());
        var actual =
                store.query(
                        "SELECT * FROM "
                                + VerifiedMarketDataStore.q(definition.objectName())
                                + " WHERE "
                                + time
                                + ">='"
                                + first
                                + "' AND "
                                + time
                                + "<='"
                                + last
                                + "' AND exchange='BINANCE' AND symbol='BTCUSDT' LIMIT 10001");
        if (actual.size() > 10000) throw new IllegalStateException("重叠核对达到上限");
        var conflicts = conflicts(definition, expected, actual);
        if (!conflicts.isEmpty()) {
            Path folder = root.resolve("overlap-conflicts");
            Files.createDirectories(folder);
            Path report = folder.resolve(UUID.randomUUID() + ".json");
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            report.toFile(),
                            Map.of(
                                    "dataset",
                                    definition.datasetId(),
                                    "targetId",
                                    identity,
                                    "conflicts",
                                    conflicts));
            throw new IllegalStateException("重叠业务值不同，保留旧值并跳过；见 " + report);
        }
        return identity;
    }

    public static List<Map<String, Object>> conflicts(
            DatasetDefinition definition,
            List<Map<String, Object>> expected,
            List<Map<String, Object>> actual) {
        var codec = VerifiedMarketDataStore.codec(definition);
        var wanted = new LinkedHashMap<String, Map<String, Object>>();
        for (var row : expected) {
            var normalized = VerifiedMarketDataStore.normalize(definition, row);
            wanted.put(codec.key(normalized), normalized);
        }
        var result = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (var raw : actual) {
            var row = VerifiedMarketDataStore.normalize(definition, raw);
            String key = codec.key(row);
            var next = wanted.get(key);
            if (next == null) continue;
            if (!seen.add(key)) throw new IllegalStateException("原重叠范围有重复键");
            for (String field : definition.storageColumns()) {
                if (field.equals("source_sha256") || field.equals("observed_at")) continue;
                if (field.equals("funding_interval_hours")
                        && row.get(field) == null
                        && next.get(field) != null) continue;
                if (!Objects.equals(row.get(field), next.get(field))) {
                    var diff = new LinkedHashMap<String, Object>();
                    diff.put("key", key);
                    diff.put("field", field);
                    diff.put("existing", row.get(field));
                    diff.put("incoming", next.get(field));
                    result.add(diff);
                }
            }
        }
        return List.copyOf(result);
    }
}
