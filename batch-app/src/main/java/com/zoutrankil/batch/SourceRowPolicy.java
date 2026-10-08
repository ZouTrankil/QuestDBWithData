package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

interface SourceRowPolicy {
    List<Map<String,JsonNode>> prepareRows(SourceContract contract, List<Map<String,JsonNode>> rows,
            LocalDate date, Set<String> expectedCodes, String providerCode);
    boolean ignoredFooter(SourceContract contract, Map<String,JsonNode> row, LocalDate date);
    Map<String,Object> normalize(SourceContract contract, Map<String,JsonNode> row, LocalDate date, Set<String> expectedCodes);
    Map<String,Object> validateNormalized(SourceContract contract, Map<String,Object> row, LocalDate date, Set<String> expectedCodes);
    LocalDate observationDate(SourceContract contract, Map<String,JsonNode> row, LocalDate fallback);
}
