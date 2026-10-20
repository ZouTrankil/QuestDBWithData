package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.EquityStyleMonthlySourceData.Snapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Bounded physical input reads; each returned row keeps its ordered nullable fields. */
public interface EquityStyleMonthlySourceReadPort {
    String table();

    Snapshot snapshot();

    List<Map<String, Object>> readWindow(LocalDate from, LocalDate to);
}
