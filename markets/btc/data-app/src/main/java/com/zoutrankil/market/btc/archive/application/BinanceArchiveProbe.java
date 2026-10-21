package com.zoutrankil.market.btc.archive.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.market.btc.archive.BinanceArchiveDataset;
import com.zoutrankil.market.btc.archive.BinanceArchiveMapper;
import com.zoutrankil.market.btc.archive.client.BinanceArchiveSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只探测真实文件，不建表、不发布；个别来源失败后继续核对其他组。 */
public final class BinanceArchiveProbe {
    private BinanceArchiveProbe() {}

    public static void run(BinanceArchiveSource source, Path root) throws Exception {
        var samples = new ArrayList<Object>();
        for (var dataset : BinanceArchiveDataset.values()) {
            for (String month : List.of("2020-01", "2024-12", "2025-01", "2026-09")) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("dataset", dataset.id());
                entry.put("month", month);
                entry.put("url", dataset.monthly(YearMonth.parse(month)).toString());
                try {
                    var archive = source.fetch(dataset.monthly(YearMonth.parse(month)), false);
                    var lines = BinanceArchiveMapper.csv(Files.readAllBytes(archive.raw()));
                    entry.put("sha256", archive.sha256());
                    entry.put("raw", archive.raw().toString());
                    entry.put("checksum", archive.checksum().toString());
                    entry.put("first", lines.getFirst());
                    entry.put("last", lines.getLast());
                    entry.put("lineCount", lines.size());
                    var period = YearMonth.parse(month);
                    var rows =
                            BinanceArchiveMapper.parse(
                                    dataset,
                                    Files.readAllBytes(archive.raw()),
                                    period.atDay(1),
                                    period.atEndOfMonth(),
                                    archive.sha256(),
                                    archive.observedAt());
                    entry.put("parsedRows", rows.size());
                    entry.put("firstNormalized", rows.getFirst());
                    entry.put("status", "SOURCE_PARSED");
                } catch (Exception e) {
                    entry.put("status", "SKIPPED_SOURCE");
                    entry.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                samples.add(entry);
                System.out.println(new ObjectMapper().writeValueAsString(entry));
            }
        }
        Files.createDirectories(root);
        new ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(
                        root.resolve("probe.json").toFile(),
                        Map.of(
                                "observedAt",
                                Instant.now().toString(),
                                "dataVerified",
                                false,
                                "samples",
                                samples));
    }
}
