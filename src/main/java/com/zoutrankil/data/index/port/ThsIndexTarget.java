package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.ThsIndexState;

import com.zoutrankil.data.domain.ThsIndex;
import com.zoutrankil.data.index.domain.ThsIndexState.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface ThsIndexTarget extends ThsIndexTables {
    String tableName();
    Prepared prepare(Snapshot before, List<ThsIndex> source, Scope scope) throws Exception;
    Prepared preparePrepared(Snapshot before, List<ThsIndex> rows) throws Exception;
    StageWriter newStaging();
    ThsIndexTables publicationTables();

    interface StageWriter {
        Verified write(Prepared prepared, Path evidence, BooleanSupplier cancelled) throws Exception;
    }
}
