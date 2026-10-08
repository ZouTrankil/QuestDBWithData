package com.zoutrankil.batch.l2;

import com.zoutrankil.batch.DfcfCsvParser;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record L2DailyFeatureSingleRequest(Path sourceRoot, LocalDate date, List<String> symbols, Path output, long maxBytesPerFile) {
    public L2DailyFeatureSingleRequest {
        sourceRoot = Objects.requireNonNull(sourceRoot).toAbsolutePath().normalize();
        Objects.requireNonNull(date);
        symbols = List.copyOf(symbols);
        output = Objects.requireNonNull(output).toAbsolutePath().normalize();
        if (symbols.isEmpty() || symbols.size()>1000 || symbols.stream().anyMatch(String::isBlank) || new HashSet<>(symbols).size() != symbols.size()) throw new IllegalArgumentException("Explicit unique bounded symbol list required");
        var canonicalSymbols = new HashSet<String>();
        for (String symbol:symbols) {
            if (!symbol.matches("(?i)\\d{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("Invalid source symbol: " + symbol);
            if (!canonicalSymbols.add(DfcfCsvParser.normalizeSymbol(symbol))) throw new IllegalArgumentException("Duplicate canonical source symbol: " + symbol);
        }
        if (maxBytesPerFile < 1) throw new IllegalArgumentException("Positive source byte bound required");
    }

    public static L2DailyFeatureSingleRequest parse(String[] args) throws Exception {
        var options = new HashMap<String, String>();
        for (int i = 0; i<args.length; i += 2) {
            if (i + 1 >= args.length || !args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option value pairs");
            if (options.put(args[i], args[i + 1]) != null) throw new IllegalArgumentException("Duplicate option: " + args[i]);
        }
        if (!Set.of("--source-root", "--date", "--symbols", "--output", "--max-bytes-per-file").containsAll(options.keySet())) throw new IllegalArgumentException("Unknown compute option");
        Path root = Path.of(required(options, "--source-root")).toAbsolutePath().normalize();
        String dateText = required(options, "--date");
        LocalDate date = LocalDate.parse(dateText.length() == 8?dateText.substring(0, 4) + "-" + dateText.substring(4, 6) + "-" + dateText.substring(6, 8):dateText);
        List<String> symbols = Arrays.stream(required(options, "--symbols").split(",", -1)).map(String::strip).toList();
        if (symbols.isEmpty() || symbols.size()>1000 || symbols.stream().anyMatch(String::isBlank) || new HashSet<>(symbols).size() != symbols.size()) throw new IllegalArgumentException("Explicit unique bounded symbol list required");
        var canonicalSymbols = new HashSet<String>();
        for (String symbol:symbols) {
            if (!symbol.matches("(?i)\\d{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("Invalid source symbol: " + symbol);
            if (!canonicalSymbols.add(DfcfCsvParser.normalizeSymbol(symbol))) throw new IllegalArgumentException("Duplicate canonical source symbol: " + symbol);
        }
        long maxBytes = Long.parseLong(options.getOrDefault("--max-bytes-per-file", "536870912"));
        if (maxBytes < 1) throw new IllegalArgumentException("Positive source byte bound required");
        Path output = Path.of(required(options, "--output")).toAbsolutePath().normalize();
        return new L2DailyFeatureSingleRequest(root, date, symbols, output, maxBytes);
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required option: " + key);
        return value;
    }
}
