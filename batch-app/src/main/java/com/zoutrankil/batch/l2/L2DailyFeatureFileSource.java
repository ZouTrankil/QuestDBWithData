package com.zoutrankil.batch.l2;

import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.batch.l2.L2BatchState.Job;
import com.zoutrankil.batch.l2.L2BatchState.SourceFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static com.zoutrankil.batch.l2.L2FeatureFiles.digest;
import static com.zoutrankil.batch.l2.L2FeatureFiles.hex;
import static com.zoutrankil.batch.l2.L2FeatureFiles.sha256;
import static com.zoutrankil.batch.l2.L2FeatureFiles.update;

final class L2DailyFeatureFileSource implements L2DailyFeatureSource {
    private static final List<String> SOURCE_FILES = List.of("逐笔成交.csv", "逐笔委托.csv", "行情.csv");
    @Override public List<Job> discover(Path source, List<String> ignored) throws IOException {
        if (!Files.isDirectory(source)) throw new IOException("Missing extracted date directory: " + source);
        var symbols = new TreeMap<String, List<Path>>();
        try (var entries = Files.list(source)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || !name.matches("(?i)\\d{6}[._](SH|SZ|BJ)")) {
                    ignored.add(name);
                    continue;
                }
                String canonical = DfcfCsvParser.normalizeSymbol(name.replace('_', '.'));
                symbols.computeIfAbsent(canonical, unused -> new ArrayList<>()).add(entry);
            }
        }
        return symbols.entrySet().stream().map(entry -> new Job(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    @Override public SourceFingerprint fingerprint(Path source, long maxBytes) throws IOException {
        var files = new LinkedHashMap<String, Object>();
        MessageDigest digest = digest();
        for (String name : SOURCE_FILES) {
            Path file = source.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                files.put(name, Map.of("present", false));
                update(digest, name + "\nMISSING\n");
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Source CSV is not a regular file: " + file);
            long size = Files.size(file);
            if (size > maxBytes) throw new IOException("Source CSV exceeds byte bound: " + file + " bytes=" + size);
            String hash = sha256(file);
            if (Files.size(file) != size) throw new IOException("Source CSV changed while hashing: " + file);
            files.put(name, Map.of("present", true, "bytes", size, "sha256", hash));
            update(digest, name + "\n" + size + "\n" + hash + "\n");
        }
        return new SourceFingerprint(hex(digest), Collections.unmodifiableMap(files));
    }

    @Override public DfcfCsvParser.ProductionDay parse(Path source, String symbol, LocalDate date, long maxBytes) throws IOException {
        return DfcfCsvParser.readProductionDay(source, symbol, date, maxBytes);
    }
}
