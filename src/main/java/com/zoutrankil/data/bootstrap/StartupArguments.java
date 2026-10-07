package com.zoutrankil.data.bootstrap;

import java.util.ArrayList;
import java.util.List;

/** Separates Spring configuration from the existing command and its business options. */
public record StartupArguments(boolean web, List<String> commandArguments) {
    public StartupArguments { commandArguments = List.copyOf(commandArguments); }

    public static StartupArguments parse(String... arguments) {
        boolean web = false;
        var command = new ArrayList<String>();
        for (String argument : arguments) {
            if (argument.equals("--web")) {
                if (web) throw new IllegalArgumentException("Duplicate --web option");
                web = true;
            } else if (argument.startsWith("--web=")) {
                throw new IllegalArgumentException("Use --web without a value");
            } else if (configurationOption(argument)) {
                if (argument.indexOf('=') < 0 && !argument.equals("--debug") && !argument.equals("--trace"))
                    throw new IllegalArgumentException("Spring configuration requires --key=value: " + argument);
            } else {
                command.add(argument);
            }
        }
        if (!command.isEmpty() && command.getFirst().startsWith("--"))
            throw new IllegalArgumentException("A CLI command must precede its business options");
        if (web && !command.isEmpty())
            throw new IllegalArgumentException("--web cannot be combined with a CLI command");
        return new StartupArguments(web, command);
    }

    public boolean hasCommand() { return !commandArguments.isEmpty(); }

    public String[] commandArray() { return commandArguments.toArray(String[]::new); }

    private static boolean configurationOption(String argument) {
        if (!argument.startsWith("--")) return false;
        int equals = argument.indexOf('=');
        String key = argument.substring(2, equals < 0 ? argument.length() : equals);
        return key.equals("debug") || key.equals("trace")
                || key.matches("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");
    }
}
