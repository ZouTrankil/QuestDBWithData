package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface DcIndexTarget {
 String tableName();String targetId();String physicalTargetId();DcIndexState.DateRange range();
 DcIndexWriteSession newWriter(String physicalId);DcIndexWriteSession stageWriter(String stage,String physicalId);
 DcIndexTables newPublicationTables();DcIndexStagingPort newStaging();
}
