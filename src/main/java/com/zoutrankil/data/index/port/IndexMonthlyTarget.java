package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface IndexMonthlyTarget {
 String tableName(); String targetId(); String physicalTargetId(); IndexMonthlyWriteSession newWriter(String physicalId);
 IndexMonthlyTables newPublicationTables(); IndexMonthlyStagingPort newStaging();
}
