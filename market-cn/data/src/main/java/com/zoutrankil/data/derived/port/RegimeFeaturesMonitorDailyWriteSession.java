package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailyRows;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
/** Independent typed writer session; public row/snapshot types retain their original names. */
public interface RegimeFeaturesMonitorDailyWriteSession extends VerifiedWriteSession<RegimeFeaturesMonitorDailyRow,Instant> {
    VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow,Instant> CODEC=new NativeDailyCodec<>(RegimeFeaturesMonitorDailyRows.projection());
    default VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow,Instant> codec(){return CODEC;}
    NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> nativePort();String table();String stage();
    RegimeFeaturesMonitorDailyTargetSnapshot snapshot(String table);RegimeFeaturesMonitorDailyTargetSnapshot formalSnapshot();
    void requireSame(RegimeFeaturesMonitorDailyTargetSnapshot expected);
    RegimeFeaturesMonitorDailyTargetSnapshot prepare(RegimeFeaturesMonitorDailyTargetSnapshot before,LocalDate from,LocalDate to)throws Exception;
    void preflight();List<RegimeFeaturesMonitorDailyRow> readback(List<Instant> keys);boolean walSettled();boolean uncertainSenderStopped();
    List<RegimeFeaturesMonitorDailyRow> readAll(String table);void requireSchema(String table);
}
