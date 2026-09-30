package com.zoutrankil.data.client.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** A single bounded response. It does not imply the source window is complete. */
public record TusharePage(List<String> fields, List<Map<String, JsonNode>> rows) {
    public TusharePage {
        fields = List.copyOf(fields);
        rows = rows.stream().map(Map::copyOf).toList();
    }
}
