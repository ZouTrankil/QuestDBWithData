package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.RegimeFeaturesMonitorDailyRows;
import com.zoutrankil.data.domain.derived.RegimeFeaturesMonitorDailyTargetSnapshot;
import com.zoutrankil.data.domain.derived.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Independent typed writer session; public row/snapshot types retain their original names. */
public interface RegimeFeaturesMonitorDailyWriteSession
        extends VerifiedWriteSession<RegimeFeaturesMonitorDailyRow, Instant> {
    VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow, Instant> CODEC =
            new NativeDailyCodec<>(RegimeFeaturesMonitorDailyRows.projection());

    default VerifiedBatchExecutor.Codec<RegimeFeaturesMonitorDailyRow, Instant> codec() {
        return CODEC;
    }

    NativeDailyWindowSession<RegimeFeaturesMonitorDailyRow> nativePort();

    String table();

    String stage();

    RegimeFeaturesMonitorDailyTargetSnapshot snapshot(String table);

    RegimeFeaturesMonitorDailyTargetSnapshot formalSnapshot();

    void requireSame(RegimeFeaturesMonitorDailyTargetSnapshot expected);

    RegimeFeaturesMonitorDailyTargetSnapshot prepare(
            RegimeFeaturesMonitorDailyTargetSnapshot before, LocalDate from, LocalDate to)
            throws Exception;

    void preflight();

    List<RegimeFeaturesMonitorDailyRow> readback(List<Instant> keys);

    boolean walSettled();

    boolean uncertainSenderStopped();

    List<RegimeFeaturesMonitorDailyRow> readAll(String table);

    void requireSchema(String table);
}
