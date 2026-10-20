package com.zoutrankil.data.client.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** 一次有界响应；单页成功不代表来源日期窗口已经完整。 */
public record TusharePage(List<String> fields, List<Map<String, JsonNode>> rows) {
    public TusharePage {
        fields = List.copyOf(fields);
        rows = rows.stream().map(Map::copyOf).toList();
    }
}
