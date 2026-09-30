package com.zoutrankil.data.storage;

import com.zoutrankil.data.domain.StockBasic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class StockBasicCsvWriter {
    private static final String HEADER = "ts_code,symbol,name,area,industry,list_date";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private StockBasicCsvWriter() {}

    public static void write(Path output, List<StockBasic> stocks) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, "stock_basic-", ".csv.tmp");
        try {
            StringBuilder csv = new StringBuilder(HEADER).append('\n');
            for (StockBasic stock : stocks) {
                csv.append(row(stock)).append('\n');
            }
            Files.writeString(temporary, csv, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException | IOException atomicMoveFailure) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String row(StockBasic stock) {
        return String.join(",",
                escape(stock.tsCode()),
                escape(stock.symbol()),
                escape(stock.name()),
                escape(stock.area()),
                escape(stock.industry()),
                escape(stock.listDate() == null ? "" : DATE_FORMAT.format(stock.listDate())));
    }

    private static String escape(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.contains(",") || normalized.contains("\"")
                || normalized.contains("\n") || normalized.contains("\r")) {
            return "\"" + normalized.replace("\"", "\"\"") + "\"";
        }
        return normalized;
    }
}
