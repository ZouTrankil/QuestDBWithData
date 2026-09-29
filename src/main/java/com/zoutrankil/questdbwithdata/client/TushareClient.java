package com.zoutrankil.questdbwithdata.client;

import com.zoutrankil.questdbwithdata.config.TushareProperties;
import com.zoutrankil.questdbwithdata.client.dto.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.Exceptions;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.zoutrankil.questdbwithdata.client.TushareFailure.Kind.*;

@Component
public class TushareClient {
    public static final List<String> STOCK_FIELDS = List.of("ts_code", "symbol", "name", "area", "industry", "list_date");
    private final WebClient webClient;
    private final TushareProperties properties;
    private final SharedRequestBudget budget;
    private final ObjectMapper mapper = new ObjectMapper();

    public TushareClient(WebClient tushareWebClient, TushareProperties properties, SharedRequestBudget budget) {
        this.webClient = tushareWebClient;
        this.properties = properties;
        this.budget = budget;
    }

    /** Cancellation propagates to quota wait, retry backoff and the HTTP subscription. */
    public CompletableFuture<TusharePage> requestAsync(TushareRequest request) {
        return requestMono(request).toFuture();
    }

    public TusharePage request(TushareRequest request) throws IOException {
        try {
            return requestMono(request).block();
        } catch (RuntimeException failure) {
            var cause = Exceptions.unwrap(failure);
            if (cause instanceof TushareFailure safe) throw safe;
            throw new TushareFailure(TRANSPORT, null, "Tushare request interrupted or transport failed");
        }
    }

    /** All callers, including the legacy stock_basic entry, share the same credential budget. */
    public Mono<TusharePage> requestMono(TushareRequest request) {
        Objects.requireNonNull(request);
        return budget.execute(request.apiName(), properties.getToken(), () -> transportAttempt(request));
    }

    private Mono<TusharePage> transportAttempt(TushareRequest request) {
        return Mono.defer(() -> {
            if (properties.getToken() == null || properties.getToken().isBlank()) {
                return Mono.<TusharePage>error(new TushareFailure(CONTRACT, null, "Tushare credential required"));
            }
            var body = mapper.createObjectNode();
            body.put("api_name", request.apiName());
            body.put("token", properties.getToken());
            body.set("params", mapper.valueToTree(request.params()));
            body.put("fields", String.join(",", request.fields()));
            return webClient.post().contentType(MediaType.APPLICATION_JSON).bodyValue(body.toString())
                    .exchangeToMono(response -> {
                        if (!response.statusCode().is2xxSuccessful()) {
                            int status = response.statusCode().value();
                            var retryAfter = parseRetryAfter(response.headers().asHttpHeaders().getFirst("Retry-After"));
                            return response.releaseBody().then(Mono.<TusharePage>error(
                                    new TushareFailure(HTTP, status, "Tushare HTTP status " + status, retryAfter)));
                        }
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(raw -> Mono.fromCallable(() -> decode(raw, request)));
                    });
        }).timeout(properties.getRequestTimeout())
                .onErrorMap(failure -> failure instanceof TushareFailure ? failure
                        : failure instanceof org.springframework.core.io.buffer.DataBufferLimitException
                        ? new TushareFailure(CONTRACT, null, "Tushare response exceeds byte bound")
                        : new TushareFailure(TRANSPORT, null, "Tushare transport or timeout failure"));
    }

    private static java.time.Duration parseRetryAfter(String header) {
        if (header == null) return null;
        try {
            long seconds = Long.parseLong(header.trim());
            return java.time.Duration.ofSeconds(Math.min(86400, Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            try {
                var date = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return java.time.Duration.ofMillis(Math.max(0, Math.min(86400000,
                        java.time.Duration.between(java.time.Instant.now(), date.toInstant()).toMillis())));
            } catch (java.time.DateTimeException invalid) { return null; }
        }
    }

    private TusharePage decode(String raw, TushareRequest request) throws TushareFailure {
        JsonNode root;
        try { root = mapper.readTree(raw); }
        catch (IOException ignored) { throw new TushareFailure(CONTRACT, null, "Invalid Tushare JSON"); }
        if (root == null || !root.path("code").isIntegralNumber() || !root.path("code").canConvertToInt()) {
            throw new TushareFailure(CONTRACT, null, "Tushare response missing valid code");
        }
        int code = root.get("code").intValue();
        if (code != 0) {
            String message = root.path("msg").asText("");
            String normalized = message.toLowerCase(Locale.ROOT);
            boolean rateLimited = code != 2002 && (normalized.contains("每分钟最多")
                    || normalized.contains("每秒最多") || normalized.contains("每小时最多")
                    || normalized.contains("访问频率") || normalized.contains("频率超限")
                    || normalized.contains("rate limit"));
            throw new TushareFailure(rateLimited ? BUSINESS_RATE_LIMIT : BUSINESS, code, "Tushare business error " + code);
        }
        var fields = root.path("data").path("fields");
        var items = root.path("data").path("items");
        if (!fields.isArray() || !items.isArray()) throw new TushareFailure(CONTRACT, null, "Missing fields/items arrays");
        var names = new ArrayList<String>();
        for (var field : fields) {
            if (!field.isTextual() || !field.asText().matches("[A-Za-z_][A-Za-z0-9_]*") || names.contains(field.asText())) {
                throw new TushareFailure(CONTRACT, null, "Invalid or duplicate response field");
            }
            names.add(field.asText());
        }
        if (!names.containsAll(request.fields())) throw new TushareFailure(CONTRACT, null, "Missing requested response field");
        if (items.size() > request.maxRows()) throw new TushareFailure(CONTRACT, null, "Response exceeds declared row bound");
        var rows = new ArrayList<Map<String, JsonNode>>();
        for (var item : items) {
            if (!item.isArray() || item.size() != names.size()) throw new TushareFailure(CONTRACT, null, "Invalid response row width");
            var row = new LinkedHashMap<String, JsonNode>();
            for (int i = 0; i < names.size(); i++) {
                if (!item.get(i).isValueNode()) throw new TushareFailure(CONTRACT, null, "Non-scalar response cell");
                row.put(names.get(i), item.get(i));
            }
            rows.add(row);
        }
        return new TusharePage(names, rows);
    }

    public List<TushareStockBasicDto> fetchCurrentListedStocks() throws IOException {
        var page = request(new TushareRequest("stock_basic", Map.of("exchange", "", "list_status", "L"), STOCK_FIELDS, 10_000));
        return page.rows().stream().map(row -> new TushareStockBasicDto(text(row, "ts_code"), text(row, "symbol"),
                text(row, "name"), text(row, "area"), text(row, "industry"), text(row, "list_date"))).toList();
    }

    private static String text(Map<String, JsonNode> row, String field) {
        return row.get(field).isNull() ? null : row.get(field).asText();
    }
}
