package com.zoutrankil.questdbwithdata;

import java.io.IOException;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || !"sync-stock-basic".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: sync-stock-basic [--env-file PATH] [--output PATH]");
        }

        Path envFile = Path.of(".env");
        Path output = Path.of("var/stock_basic.csv");
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--env-file" -> envFile = Path.of(requireValue(args, ++i, "--env-file"));
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        String token = TushareConfig.loadToken(envFile);
        var stocks = new TushareClient().fetchCurrentListedStocks(token);
        StockBasicCsv.write(output, stocks);
        System.out.printf("Fetched %d stocks; wrote %s%n", stocks.size(), output.toAbsolutePath());
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
