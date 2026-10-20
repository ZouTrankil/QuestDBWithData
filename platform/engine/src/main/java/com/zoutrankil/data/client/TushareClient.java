package com.zoutrankil.data.client;

import static com.zoutrankil.data.client.TushareFailure.Kind.BUSINESS;
import static com.zoutrankil.data.client.TushareFailure.Kind.BUSINESS_RATE_LIMIT;
import static com.zoutrankil.data.client.TushareFailure.Kind.CONTRACT;
import static com.zoutrankil.data.client.TushareFailure.Kind.HTTP;
import static com.zoutrankil.data.client.TushareFailure.Kind.TRANSPORT;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.client.dto.TusharePage;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.config.TushareProperties;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
public class TushareClient {
    private final WebClient webClient;
    private final TushareProperties properties;
    private final SharedRequestBudget budget;
    private final ObjectMapper mapper = new ObjectMapper();

    public TushareClient(
            WebClient tushareWebClient, TushareProperties properties, SharedRequestBudget budget) {
        this.webClient = tushareWebClient;
        this.properties = properties;
        this.budget = budget;
    }

    /** 取消信号会传递到配额等待、重试退避和 HTTP 请求订阅。 */
    public CompletableFuture<TusharePage> requestAsync(TushareRequest request) {
        return requestMono(request).toFuture();
    }

    public TusharePage request(TushareRequest request) throws IOException {
        try {
            return requestMono(request).block();
        } catch (RuntimeException failure) {
            var cause = Exceptions.unwrap(failure);
            if (cause instanceof TushareFailure safe) throw safe;
            throw new TushareFailure(
                    TRANSPORT, null, "Tushare request interrupted or transport failed");
        }
    }

    /** 所有调用方，包括旧 stock_basic 入口，都共用同一凭据预算。 */
    public Mono<TusharePage> requestMono(TushareRequest request) {
        Objects.requireNonNull(request);
        return budget.execute(
                request.apiName(), properties.getToken(), () -> transportAttempt(request));
    }

    private Mono<TusharePage> transportAttempt(TushareRequest request) {
        return Mono.defer(
                        () -> {
                            if (properties.getToken() == null || properties.getToken().isBlank()) {
                                return Mono.<TusharePage>error(
                                        new TushareFailure(
                                                CONTRACT, null, "Tushare credential required"));
                            }
                            var body = mapper.createObjectNode();
                            body.put("api_name", request.apiName());
                            body.put("token", properties.getToken());
                            body.set("params", mapper.valueToTree(request.params()));
                            body.put("fields", String.join(",", request.fields()));
                            return webClient
                                    .post()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(body.toString())
                                    .exchangeToMono(
                                            response -> {
                                                if (!response.statusCode().is2xxSuccessful()) {
                                                    int status = response.statusCode().value();
                                                    var retryAfter =
                                                            parseRetryAfter(
                                                                    response.headers()
                                                                            .asHttpHeaders()
                                                                            .getFirst(
                                                                                    "Retry-After"));
                                                    return response.releaseBody()
                                                            .then(
                                                                    Mono.<TusharePage>error(
                                                                            new TushareFailure(
                                                                                    HTTP,
                                                                                    status,
                                                                                    "Tushare HTTP status "
                                                                                            + status,
                                                                                    retryAfter)));
                                                }
                                                return response.bodyToMono(String.class)
                                                        .defaultIfEmpty("")
                                                        .flatMap(
                                                                raw ->
                                                                        Mono.fromCallable(
                                                                                () ->
                                                                                        decode(
                                                                                                raw,
                                                                                                request)));
                                            });
                        })
                .timeout(properties.getRequestTimeout())
                .onErrorMap(
                        failure ->
                                failure instanceof TushareFailure
                                        ? failure
                                        : failure instanceof DataBufferLimitException
                                                ? new TushareFailure(
                                                        CONTRACT,
                                                        null,
                                                        "Tushare response exceeds byte bound")
                                                : new TushareFailure(
                                                        TRANSPORT,
                                                        null,
                                                        "Tushare transport or timeout failure"));
    }

    private static Duration parseRetryAfter(String header) {
        if (header == null) return null;
        try {
            long seconds = Long.parseLong(header.trim());
            return Duration.ofSeconds(Math.min(86400, Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            try {
                var date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Duration.ofMillis(
                        Math.max(
                                0,
                                Math.min(
                                        86400000,
                                        Duration.between(Instant.now(), date.toInstant())
                                                .toMillis())));
            } catch (DateTimeException invalid) {
                return null;
            }
        }
    }

    private TusharePage decode(String raw, TushareRequest request) throws TushareFailure {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (IOException ignored) {
            throw new TushareFailure(CONTRACT, null, "Invalid Tushare JSON");
        }
        if (root == null
                || !root.path("code").isIntegralNumber()
                || !root.path("code").canConvertToInt()) {
            throw new TushareFailure(CONTRACT, null, "Tushare response missing valid code");
        }
        int code = root.get("code").intValue();
        if (code != 0) {
            String message = root.path("msg").asText("");
            String normalized = message.toLowerCase(Locale.ROOT);
            boolean rateLimited =
                    code != 2002
                            && (normalized.contains("每分钟最多")
                                    || normalized.contains("每秒最多")
                                    || normalized.contains("每小时最多")
                                    || normalized.contains("访问频率")
                                    || normalized.contains("频率超限")
                                    || normalized.contains("rate limit"));
            throw new TushareFailure(
                    rateLimited ? BUSINESS_RATE_LIMIT : BUSINESS,
                    code,
                    "Tushare business error " + code);
        }
        var fields = root.path("data").path("fields");
        var items = root.path("data").path("items");
        if (!fields.isArray() || !items.isArray())
            throw new TushareFailure(CONTRACT, null, "Missing fields/items arrays");
        var names = new ArrayList<String>();
        for (var field : fields) {
            if (!field.isTextual()
                    || !field.asText().matches("[A-Za-z0-9_][A-Za-z0-9_]*")
                    || names.contains(field.asText())) {
                throw new TushareFailure(CONTRACT, null, "Invalid or duplicate response field");
            }
            names.add(field.asText());
        }
        if (!names.containsAll(request.fields()))
            throw new TushareFailure(CONTRACT, null, "Missing requested response field");
        if (items.size() > request.maxRows())
            throw new TushareFailure(CONTRACT, null, "Response exceeds declared row bound");
        var rows = new ArrayList<Map<String, JsonNode>>();
        for (var item : items) {
            if (!item.isArray() || item.size() != names.size())
                throw new TushareFailure(CONTRACT, null, "Invalid response row width");
            var row = new LinkedHashMap<String, JsonNode>();
            for (int i = 0; i < names.size(); i++) {
                if (!item.get(i).isValueNode())
                    throw new TushareFailure(CONTRACT, null, "Non-scalar response cell");
                row.put(names.get(i), item.get(i));
            }
            rows.add(row);
        }
        return new TusharePage(names, rows);
    }
}
