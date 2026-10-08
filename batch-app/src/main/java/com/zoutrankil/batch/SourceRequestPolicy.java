package com.zoutrankil.batch;

import com.zoutrankil.data.domain.PageContract;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider request rules; implementations are immutable and collection state is opened separately. */
interface SourceRequestPolicy {
    Set<String> validate(SourceContract contract, LocalDate logicalDate, Set<String> expectedCodes,
                         String universeVersion, String tsCode, LocalDate rangeStart, LocalDate rangeEnd);
    PageContract pageContract(SourceContract contract);
    PageContract providerContract(SourceContract contract, String code);
    List<Map<String,Object>> queries(SourceContract contract, SourceCollector.Request request);
    SourceCollectionSession openSession(SourceContract contract, SourceCollector.Request request, SourceRowPolicy rows);
}
