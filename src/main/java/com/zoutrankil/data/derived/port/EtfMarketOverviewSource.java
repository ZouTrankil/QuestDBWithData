package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Original-owner SELECT-only source preview. */
public interface EtfMarketOverviewSource {
    EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date) throws Exception;
}
