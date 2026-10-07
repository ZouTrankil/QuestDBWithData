package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded physical process lifecycle; exit is not publication acknowledgement. */
public interface EtfMarketOverviewProcess {
    void invoke(EtfMarketOverviewOwnerInvocation invocation, BooleanSupplier cancelled) throws Exception;
    boolean endedIdentity(long pid, String birth);
    boolean nativeProcessPresent(long pid);
}
