package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.MacroCoreMonthlySourceData.Snapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Bounded physical input reads; each returned row keeps its ordered nullable fields. */
public interface MacroCoreMonthlySourceReadPort {
    Snapshot snapshot(long deadlineNanos);

    List<Map<String, Object>> readWindow(
            String table, LocalDate from, LocalDate to, long deadlineNanos);

    List<Map<String, Object>> readPrecedingSocialFinancingDescending(
            LocalDate before, long deadlineNanos);
}
