package com.zoutrankil.data.cli;

import java.util.Map;
import java.util.Set;

/** A finite set of command names and the application operations that handle them. */
public interface CliCommandFamily {
    Set<String> commands();
    void execute(String command, Map<String, String> options) throws Exception;
}
