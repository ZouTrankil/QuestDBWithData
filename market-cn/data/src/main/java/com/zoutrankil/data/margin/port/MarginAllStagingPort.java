package com.zoutrankil.data.margin.port;
import java.util.function.BooleanSupplier;
public interface MarginAllStagingPort extends MarginAllTables {
    void createOutsideStage(String stage,String target,String lower,String upper);
    void awaitWal(String table,BooleanSupplier cancelled) throws Exception;
}
