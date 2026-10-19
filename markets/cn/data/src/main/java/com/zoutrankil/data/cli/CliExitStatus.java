package com.zoutrankil.data.cli;

import java.util.Collections;
import java.util.IdentityHashMap;

/** Process contract: 0 completed command, 1 runtime failure, 2 invalid input, 3 incomplete outcome. */
public final class CliExitStatus {
    private CliExitStatus() {}
    public static int failureCode(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        boolean invalid = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IncompleteCommandException) return 3;
            if (cause instanceof IllegalArgumentException
                    || cause instanceof java.time.format.DateTimeParseException
                    || cause instanceof com.fasterxml.jackson.core.JsonProcessingException) invalid = true;
        }
        return invalid ? 2 : 1;
    }
}
