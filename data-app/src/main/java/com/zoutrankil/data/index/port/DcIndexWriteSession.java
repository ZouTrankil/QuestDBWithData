package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface DcIndexWriteSession extends com.zoutrankil.data.sync.port.VerifiedWriteSession<DcIndex,DcIndexKey>,com.zoutrankil.data.sync.port.DateSliceReadPort<DcIndex> {
 DcIndexWriteSession forTarget(String stage,String physicalId);
}
