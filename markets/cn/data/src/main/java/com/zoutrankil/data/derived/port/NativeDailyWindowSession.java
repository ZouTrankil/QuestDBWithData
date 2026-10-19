package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
/** A single staging request and its complete physical snapshot/readback lifecycle. */
public interface NativeDailyWindowSession<R extends Record> extends VerifiedWriteSession<R,Instant> {
    String table();String stage();String stagePrefix();Class<R> rowType();List<String> columns();
    NativeDailyWindowSnapshot<R> snapshot(String table);
    NativeDailyWindowSnapshot<R> formalSnapshot();
    void requireSame(NativeDailyWindowSnapshot<R> expected);
    NativeDailyWindowSnapshot<R> prepare(NativeDailyWindowSnapshot<R> before,LocalDate from,LocalDate to)throws Exception;
    void awaitPublished(String table,long expectedId,String expectedDirectory,BooleanSupplier cancelled)throws Exception;
    boolean outside(R row,LocalDate from,LocalDate to);
    void preflight();List<R> readback(List<Instant> keys);boolean walSettled();boolean uncertainSenderStopped();
    List<R> readAll(String table);R row(Map<String,?> map);Map<String,Object> values(R row);
    void requireSchema(String table);String quotedColumns();String digest(List<R> rows);
}
