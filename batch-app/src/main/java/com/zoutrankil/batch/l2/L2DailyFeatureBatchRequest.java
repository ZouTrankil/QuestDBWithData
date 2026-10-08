package com.zoutrankil.batch.l2;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record L2DailyFeatureBatchRequest(Path sourceRoot, List<LocalDate> dates, Path outputRoot, int workers, long maxBytesPerFile, boolean resume, String symbolsFile) {
    public L2DailyFeatureBatchRequest {
        sourceRoot = Objects.requireNonNull(sourceRoot).toAbsolutePath().normalize();
        outputRoot = Objects.requireNonNull(outputRoot).toAbsolutePath().normalize();
        dates = List.copyOf(dates);
        if (workers < 1 || workers > 32) throw new IllegalArgumentException("workers must be between 1 and 32");
        if (maxBytesPerFile < 1) throw new IllegalArgumentException("max-bytes-per-file must be positive");
        if (dates.isEmpty() || new HashSet<>(dates).size() != dates.size()) throw new IllegalArgumentException("Explicit unique dates required");
        dates = new TreeSet<>(dates).stream().toList();
    }

    public static L2DailyFeatureBatchRequest parse(String[] args) throws Exception {
        var options = options(args);
        Path sourceRoot = Path.of(required(options, "--source-root")).toAbsolutePath().normalize();
        Path outputRoot = Path.of(required(options, "--output-root")).toAbsolutePath().normalize();
        int workers = Integer.parseInt(options.getOrDefault("--workers", "1"));
        long maxBytes = Long.parseLong(options.getOrDefault("--max-bytes-per-file", "536870912"));
        String resumeText = options.getOrDefault("--resume", "false");
        if (workers < 1 || workers > 32) throw new IllegalArgumentException("workers must be between 1 and 32");
        if (maxBytes < 1) throw new IllegalArgumentException("max-bytes-per-file must be positive");
        if (!Set.of("true", "false").contains(resumeText)) throw new IllegalArgumentException("resume must be true or false");
        var dates = new TreeSet<LocalDate>();
        for (String text : required(options, "--dates").split(",", -1)) {
            LocalDate date = date(text.strip());
            if (!dates.add(date)) throw new IllegalArgumentException("Duplicate date: " + text);
        }
        String symbolsFile = options.get("--symbols-file");
        return new L2DailyFeatureBatchRequest(sourceRoot, List.copyOf(dates), outputRoot, workers, maxBytes, Boolean.parseBoolean(resumeText), symbolsFile);
    }

    private static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option value pairs");
            if (options.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option: " + args[i]);
        }
        if (!Set.of("--source-root", "--dates", "--output-root", "--workers", "--max-bytes-per-file", "--resume", "--symbols-file").containsAll(options.keySet()))
            throw new IllegalArgumentException("Unknown batch option");
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required option: " + name);
        return value;
    }

    private static LocalDate date(String text) {
        return LocalDate.parse(text.length() == 8 ? text.substring(0, 4) + "-" + text.substring(4, 6) + "-" + text.substring(6, 8) : text);
    }
}
