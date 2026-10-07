package com.zoutrankil.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

interface SourceCoveragePolicy {
    boolean isMarketAggregate(SourceContract contract);
    boolean validCode(SourceContract contract, String code);
    Set<String> observedCodes(SourceContract contract, List<Map<String,Object>> rows);
    boolean covers(SourceContract contract, List<Map<String,Object>> rows, Set<String> expectedCodes);
    boolean coversRange(SourceContract contract, List<Map<String,Object>> rows, Set<String> expectedCodes,
            LocalDate start, LocalDate end);
}
