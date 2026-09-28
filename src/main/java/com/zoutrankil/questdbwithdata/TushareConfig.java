package com.zoutrankil.questdbwithdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TushareConfig {
    private TushareConfig() {}

    static String loadToken(Path envFile) throws IOException {
        String token = System.getenv("TUSHARE_TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }

        if (Files.isRegularFile(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0 || !"TUSHARE_TOKEN".equals(trimmed.substring(0, separator).trim())) {
                    continue;
                }
                token = trimmed.substring(separator + 1).trim();
                if (token.length() >= 2
                        && ((token.startsWith("\"") && token.endsWith("\""))
                        || (token.startsWith("'") && token.endsWith("'")))) {
                    token = token.substring(1, token.length() - 1);
                }
                if (!token.isBlank()) {
                    return token;
                }
            }
        }

        throw new IllegalStateException(
                "TUSHARE_TOKEN is not configured in the environment or " + envFile);
    }
}
