package com.zoutrankil.data.derived.port;
/** Configured target; newWriter always starts an independent mutable session. */
public interface MacroCoreMonthlyTarget {
    String table();
    MacroCoreMonthlyWriteSession newWriter();
}
