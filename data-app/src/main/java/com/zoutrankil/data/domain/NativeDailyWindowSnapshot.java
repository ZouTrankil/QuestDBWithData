package com.zoutrankil.data.domain;

import java.util.List;

/** Immutable physical target evidence shared by typed daily-window writers. */
public record NativeDailyWindowSnapshot<R extends Record>(String targetId, long tableId, String directory,
        boolean wal, List<R> rows, String fingerprint) {
    public NativeDailyWindowSnapshot { rows = List.copyOf(rows); }
}
