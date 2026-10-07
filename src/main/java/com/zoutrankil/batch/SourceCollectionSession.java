package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.service.PageExecutor;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** One collection's response processing. A session is never shared between collections. */
interface SourceCollectionSession {
    void validateResponse(Map<String,Object> pageParams, PageExecutor.Page page);
    List<Map<String,JsonNode>> prepareRows(String providerCode, List<Map<String,JsonNode>> rows);
    void validateRow(Map<String,JsonNode> row);
    void acceptPage(PageExecutor.Page page, Consumer<Map<String,Object>> normalizedSink);
    void finish(List<Map<String,Object>> normalized);
}
