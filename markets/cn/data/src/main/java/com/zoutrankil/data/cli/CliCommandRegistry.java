package com.zoutrankil.data.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Immutable exact-name routing; ambiguous registrations fail before a command can execute. */
@Component
@ConditionalOnNotWebApplication
public final class CliCommandRegistry {
    private final Map<String, CliCommandFamily> commands;

    public CliCommandRegistry(List<CliCommandFamily> families) {
        var registered = new LinkedHashMap<String, CliCommandFamily>();
        for (var family : Objects.requireNonNull(families)) {
            Objects.requireNonNull(family);
            Set<String> names = family.commands();
            if (names == null || names.isEmpty())
                throw new IllegalArgumentException("CLI command family must declare command names");
            for (String name : names) {
                if (name == null || name.isBlank())
                    throw new IllegalArgumentException("CLI command name must not be empty");
                if (registered.putIfAbsent(name, family) != null)
                    throw new IllegalArgumentException("Duplicate CLI command: " + name);
            }
        }
        commands = Collections.unmodifiableMap(registered);
    }

    public Set<String> commands() { return commands.keySet(); }

    public void execute(String command, Map<String, String> options) throws Exception {
        var family = commands.get(command);
        if (family == null) throw new IllegalArgumentException(CliUsage.text());
        family.execute(command, options);
    }
}
