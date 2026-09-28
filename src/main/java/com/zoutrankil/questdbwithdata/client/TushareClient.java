package com.zoutrankil.questdbwithdata.client;

import com.zoutrankil.questdbwithdata.config.TushareProperties;
import com.zoutrankil.questdbwithdata.client.dto.TushareStockBasicDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TushareClient {
    private static final String FIELDS = "ts_code,symbol,name,area,industry,list_date";

    private final WebClient webClient;
    private final TushareProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public TushareClient(WebClient tushareWebClient, TushareProperties properties) {
        this.webClient = tushareWebClient;
        this.properties = properties;
    }

    public List<TushareStockBasicDto> fetchCurrentListedStocks() throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("api_name", "stock_basic");
        requestBody.put("token", properties.getToken());
        requestBody.set("params", mapper.createObjectNode()
                .put("exchange", "")
                .put("list_status", "L"));
        requestBody.put("fields", FIELDS);

        String responseBody = webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapper.writeValueAsString(requestBody))
                .retrieve()
                .bodyToMono(String.class)
                .block(properties.getRequestTimeout());
        if (responseBody == null || responseBody.isBlank()) {
            throw new IOException("Tushare returned an empty response");
        }

        JsonNode root = mapper.readTree(responseBody);
        int code = root.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            throw new IOException("Tushare API error " + code + ": "
                    + root.path("msg").asText("unknown error"));
        }

        JsonNode data = root.path("data");
        Map<String, Integer> columns = indexColumns(data.path("fields"));
        JsonNode items = data.path("items");
        if (!items.isArray()) {
            throw new IOException("Tushare response is missing data.items");
        }

        List<TushareStockBasicDto> stocks = new ArrayList<>(items.size());
        for (JsonNode row : items) {
            stocks.add(new TushareStockBasicDto(
                    value(row, columns, "ts_code"),
                    value(row, columns, "symbol"),
                    value(row, columns, "name"),
                    value(row, columns, "area"),
                    value(row, columns, "industry"),
                    value(row, columns, "list_date")));
        }
        return stocks;
    }

    private static Map<String, Integer> indexColumns(JsonNode fields) throws IOException {
        if (!fields.isArray()) {
            throw new IOException("Tushare response is missing data.fields");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            columns.put(fields.get(i).asText(), i);
        }
        return columns;
    }

    private static String value(JsonNode row, Map<String, Integer> columns, String field)
            throws IOException {
        Integer index = columns.get(field);
        if (index == null || index >= row.size()) {
            throw new IOException("Tushare response is missing expected field " + field);
        }
        JsonNode value = row.get(index);
        return value == null || value.isNull() ? "" : value.asText();
    }

}
