package com.zoutrankil.data.margin.port;

import com.zoutrankil.data.margin.domain.MarginSecsState.Snapshot;

import java.time.LocalDate;

/** Immutable target configuration with fresh write sessions. */
public interface MarginSecsTarget {
    String tableName();

    String targetId();

    Snapshot snapshot();

    /** 正式v2只冻结明确日期窗，不把范围外旧行作为覆盖依据。 */
    default Snapshot snapshot(LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException("需要范围快照端口");
    }

    MarginSecsWriteSession newWriter(String physicalTargetId);
}
