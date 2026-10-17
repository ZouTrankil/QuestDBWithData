package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.ThsMemberState;

import com.zoutrankil.data.domain.ThsMember;
import com.zoutrankil.data.index.domain.ThsMemberState.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

public interface ThsMemberTarget extends ThsMemberTables {
    String tableName();
    StageWriter newStaging();
    ThsMemberTables publicationTables();

    interface StageWriter {
        Prepared prepare(String target, String board, List<ThsMember> source) throws Exception;
        boolean requiresWrite(Prepared prepared);
        Verified write(Prepared prepared, Path folder, BooleanSupplier cancelled) throws Exception;
    }
}
