package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class L2DailyFeatureSingleService {
    private final L2DailyFeatureSource sourceReader;
    public L2DailyFeatureSingleService() {
        this(new L2DailyFeatureFileSource());
    }

    L2DailyFeatureSingleService(L2DailyFeatureSource source) {
        sourceReader = Objects.requireNonNull(source);
    }

    public void execute(L2DailyFeatureSingleRequest request) throws Exception {
        Path root = request.sourceRoot(), output = request.output();
        LocalDate date = request.date();
        List<String> symbols = request.symbols();
        long maxBytes = request.maxBytesPerFile();
        String day = date.toString().replace("-", "");
        Path dateDirectory = day.equals(String.valueOf(root.getFileName()))?root:root.resolve(day);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Path temp = output.resolveSibling(output.getFileName() + ".tmp-" + UUID.randomUUID());
        var json = new ObjectMapper();
        int complete = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                for (String symbol:symbols) {
                    Path source = dateDirectory.resolve(symbol.toUpperCase(Locale.ROOT));
                    if (!Files.isDirectory(source)) throw new IllegalArgumentException("Missing extracted source directory: " + source);
                    long started = System.nanoTime();
                    var parsed = sourceReader.parse(source, symbol, date, maxBytes);
                    if (parsed.rawDealRows() == 0 && parsed.rawOrderRows() == 0 && parsed.rawQuoteRows() == 0) throw new IllegalArgumentException("Empty source directory: " + source);
                    var row = L2DailyFeaturePipeline.compute(parsed);
                    writer.write(json.writeValueAsString(L2DailyFeaturePipeline.output(row)));
                    writer.newLine();
                    complete++;
                    System.out.printf(Locale.ROOT, "%s date=%s normalized=%s deals=%d orders=%d quotes=%d fields=%d elapsedSeconds=%.3f%n", symbol, day, row.symbol(), parsed.deals().size(), parsed.orders().size(), parsed.quotes().size(), row.features().size() + 2, (System.nanoTime()-started)/1e9);
                }
            }
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally {
            Files.deleteIfExists(temp);
        }
        System.out.println("generated_rows=" + complete + " output=" + output);
    }
}
