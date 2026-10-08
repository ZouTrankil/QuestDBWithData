package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface IndexMonthlyTables {
 interface Table {IndexMonthlyState.Identity preflight(); IndexMonthlyState.Snapshot snapshot() throws Exception; IndexMonthlyState.Snapshot window(String code,LocalDate from,LocalDate to)throws Exception; IndexMonthlyState.Snapshot outside(String code,LocalDate from,LocalDate to)throws Exception;}
 Table open(String table); String logicalTargetId(String table); String physicalTargetId(String table,IndexMonthlyState.Identity identity);
 IndexMonthlyState.Snapshot snapshotIfPresent(String table)throws Exception; void rename(String from,String to);
}
