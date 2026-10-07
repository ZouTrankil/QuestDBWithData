package com.zoutrankil.data.flow.port;

import java.time.LocalDate;
import java.util.function.BooleanSupplier;

/** Bounded physical operations used by application-owned staging and recovery. */
public interface MoneyflowHsgtStagingPort extends MoneyflowHsgtTables {
    void createOutsideStage(String target, String stage, LocalDate from, LocalDate to);
    void awaitWal(String table, BooleanSupplier cancelled) throws Exception;
    int discardTableCount(String table);
    void drop(String table);
    boolean dropSettled(String table);
}
