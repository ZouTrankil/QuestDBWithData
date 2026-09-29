package com.zoutrankil.questdbwithdata.domain;

import java.util.List;

/** Query-bound keyset position, not a source checkpoint or a database snapshot. */
public record DatasetReadCursor(String queryFingerprint, List<Object> keyValues, String sourceVersion) {
    public DatasetReadCursor { keyValues = List.copyOf(keyValues); }
}
