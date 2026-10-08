package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface IndexMonthlyStagingPort extends IndexMonthlyTables {
 int tableCount(String table); void createOutsideStage(String stage,String target,String code,String lower,String end);
 void awaitWal(String table,BooleanSupplier cancelled)throws Exception;
}
