package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.stock.domain.StockSuspendState.Prepared;
import com.zoutrankil.data.stock.domain.StockSuspendState.Verified;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
public interface StockSuspendStagingPort {
    Verified write(Prepared prepared,Path evidenceFolder,BooleanSupplier cancelled) throws Exception;
}
