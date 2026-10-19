package com.zoutrankil.data.client.dto;

import java.util.*;

/** 凭据只在传输边界注入，不保存在此请求对象中。 */
public record TushareRequest(String apiName, Map<String, Object> params, List<String> fields, int maxRows) {
    public TushareRequest {
        if (apiName == null || !apiName.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid API name");
        params = Map.copyOf(params);
        fields = List.copyOf(fields);
        if (fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) throw new IllegalArgumentException("Unique fields required");
        for (String field : fields) {
            if (!field.matches("[a-zA-Z0-9_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid field name");
        }
        if (maxRows < 1 || maxRows > 100_000) throw new IllegalArgumentException("Response row bound required (1..100000)");
        for (var entry : params.entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]*") || entry.getKey().equalsIgnoreCase("token")) {
                throw new IllegalArgumentException("Invalid parameter name");
            }
            if (!(entry.getValue() instanceof String || entry.getValue() instanceof Number || entry.getValue() instanceof Boolean)) {
                throw new IllegalArgumentException("Only scalar parameters supported");
            }
        }
    }
    @Override public String toString() { return "TushareRequest[api=" + apiName + ", fields=" + fields.size() + ", maxRows=" + maxRows + "]"; }
}
