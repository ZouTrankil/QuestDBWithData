package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface DcIndexStagingPort {
 DcIndexState.Verified create(DcIndexState.Prepared prepared,Path evidence,BooleanSupplier cancelled,String runId,SyncJobDefinition.FrozenRequest request,String logical,String physical)throws Exception;
 DcIndexState.Complete verifyComplete(DcIndexState.Prepared prepared,DcIndexState.Verified stage,String fingerprint,Path evidence,BooleanSupplier cancelled)throws Exception;
}
