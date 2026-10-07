package com.zoutrankil.data.cli;

import java.util.HashMap;
import java.util.Map;

/** Lexical options for the command arguments after Spring configuration filtering. */
public final class CliOptions {
    private CliOptions() {}

    public static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            int equals = argument.indexOf('=');
            if (equals > 2) {
                putOption(options, argument.substring(0, equals), argument.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            putOption(options, argument, args[++i]);
        }
        return options;
    }

    private static void putOption(Map<String,String> options, String key, String value) {
        if (!key.matches("--[a-z][a-z0-9-]*") || value.isBlank())
            throw new IllegalArgumentException("Invalid or empty option: " + key);
        if (options.putIfAbsent(key, value) != null)
            throw new IllegalArgumentException("Duplicate option: " + key);
    }
}
