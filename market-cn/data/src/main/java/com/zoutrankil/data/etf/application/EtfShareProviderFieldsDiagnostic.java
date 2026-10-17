package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.service.TusharePageService;

import com.zoutrankil.data.repository.FileEvidenceStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Explicit-field, read-only provider diagnostics for the legacy etf_share extension columns.
 * This class never writes QuestDB and is not invoked automatically by the job runner.
 */
public final class EtfShareProviderFieldsDiagnostic {
    public static final int SOURCE_ROW_CAP = 2_000;
    public static final int MAX_REQUESTS = 6;
    public static final int SAMPLE_ROWS_PER_PROBE = 8;
    public static final int SAMPLE_VALUES_PER_FIELD = 8;
    public static final int MAX_EVIDENCE_BYTES = 4 * 1024 * 1024;
    private static final List<String> BASE_FIELDS = List.of("ts_code", "trade_date", "fd_share");
    private static final List<String> CANDIDATE_FIELDS = List.of("fund_type", "market", "update_time");
    private static final List<String> MARKETS = List.of("SH", "SZ");

    private final TusharePageService pages;
    private final Path evidenceRoot;

    public EtfShareProviderFieldsDiagnostic(TusharePageService pages, Path evidenceRoot) {
        this.pages = Objects.requireNonNull(pages);
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }

    /**
     * Make at most six one-date source reads: each candidate field is requested separately for SH and SZ.
     * Evidence is a bounded diagnostic sample, never a completion receipt or a dataset write payload.
     */
    public Path probe(LocalDate tradeDate, BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(tradeDate);
        Objects.requireNonNull(cancelled);
        String basicDate = tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        var probeEvidence = new ArrayList<Map<String, Object>>();
        boolean stopForRateLimit = false;

        outer:
        for (String market : MARKETS) {
            for (String candidate : CANDIDATE_FIELDS) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new CancellationException("fund_share field diagnostic cancelled");
                if (stopForRateLimit) {
                    probeEvidence.add(notAttempted(market, candidate, "prior_request_rate_limited"));
                    continue;
                }
                var requestedFields = new ArrayList<>(BASE_FIELDS);
                requestedFields.add(candidate);
                var contract = new PageContract("fund_share", requestedFields,
                        List.of("ts_code", "trade_date"), Set.of("trade_date", "market"),
                        PageContract.Paging.NONE, PageContract.Completion.SHORT_PAGE,
                        null, null, SOURCE_ROW_CAP, SOURCE_ROW_CAP, 1, SOURCE_ROW_CAP,
                        "One explicit optional-field diagnostic request; max 2000 rows; not a completeness receipt");
                var parameters = new LinkedHashMap<String, Object>();
                parameters.put("trade_date", basicDate);
                parameters.put("market", market);
                try {
                    var response = pages.fetcher(contract, cancelled).fetch(parameters);
                    var rows = canonicalRows(response.rows(), requestedFields, basicDate, market);
                    probeEvidence.add(observed(market, candidate, requestedFields, rows));
                } catch (CancellationException cancelledRequest) {
                    throw cancelledRequest;
                } catch (Exception failure) {
                    probeEvidence.add(failed(market, candidate, requestedFields, failure));
                    stopForRateLimit = isRateLimited(failure);
                }
            }
            if (stopForRateLimit) {
                // Do not consume the remaining shared endpoint budget after an explicit rate-limit response.
                for (int remainingMarket = MARKETS.indexOf(market) + 1; remainingMarket < MARKETS.size(); remainingMarket++) {
                    for (String candidate : CANDIDATE_FIELDS)
                        probeEvidence.add(notAttempted(MARKETS.get(remainingMarket), candidate, "prior_request_rate_limited"));
                }
                break outer;
            }
        }

        var body = new LinkedHashMap<String, Object>();
        body.put("evidenceKind", "unverified_provider_field_diagnostic");
        body.put("endpoint", "fund_share");
        body.put("tradeDate", tradeDate);
        body.put("markets", MARKETS);
        body.put("candidateFields", CANDIDATE_FIELDS);
        body.put("baseFields", BASE_FIELDS);
        body.put("sourceRowCapPerRequest", SOURCE_ROW_CAP);
        body.put("maximumRequests", MAX_REQUESTS);
        body.put("sampleRowsPerProbe", SAMPLE_ROWS_PER_PROBE);
        body.put("emptyFieldsProbeSupported", false);
        body.put("emptyFieldsProbeLimitation", "TushareRequest and PageContract reject empty field lists; TusharePageService always sends contract.fields");
        body.put("probes", probeEvidence);
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(body);
        if (bytes.length > MAX_EVIDENCE_BYTES)
            throw new IllegalStateException("fund_share diagnostic evidence exceeds 4 MiB budget");
        Files.createDirectories(evidenceRoot);
        Path receipt = evidenceRoot.resolve("etf-share-field-probe-" + basicDate + "-" + UUID.randomUUID() + ".json");
        FileEvidenceStore.writeNew(receipt,bytes);
        return receipt;
    }

    private static List<Map<String, JsonNode>> canonicalRows(List<Map<String, JsonNode>> responseRows,
                                                              List<String> requestedFields,
                                                              String basicDate, String market) {
        if (responseRows.size() > SOURCE_ROW_CAP)
            throw new IllegalStateException("fund_share response exceeds diagnostic row cap");
        var uniqueCodes = new LinkedHashSet<String>();
        var rows = new ArrayList<Map<String, JsonNode>>();
        for (var row : responseRows) {
            if (row == null || !row.keySet().containsAll(requestedFields))
                throw new IllegalArgumentException("Provider response omits requested field");
            var code = text(row.get("ts_code"));
            var date = text(row.get("trade_date"));
            if (code == null || !code.endsWith("." + market) || !basicDate.equals(date))
                throw new IllegalArgumentException("Provider response falls outside requested date/market");
            if (!uniqueCodes.add(code)) throw new IllegalArgumentException("Duplicate fund_share business key in diagnostic response");
            var sorted = new LinkedHashMap<String, JsonNode>();
            var names = new TreeSet<>(row.keySet());
            for (String name : names) sorted.put(name, row.get(name));
            rows.add(sorted);
        }
        rows.sort(Comparator.comparing((Map<String, JsonNode> row) -> text(row.get("ts_code")))
                .thenComparing(row -> row.toString()));
        return List.copyOf(rows);
    }

    private static Map<String, Object> observed(String market, String candidate, List<String> requestedFields,
                                                List<Map<String, JsonNode>> rows) {
        var actualFields = new TreeSet<String>();
        var candidateSamples = new TreeSet<String>();
        int nonNull = 0;
        var samples = new ArrayList<Map<String, JsonNode>>();
        for (var row : rows) {
            actualFields.addAll(row.keySet());
            JsonNode value = row.get(candidate);
            if (value != null && !value.isNull()) {
                nonNull++;
                if (candidateSamples.size() < SAMPLE_VALUES_PER_FIELD) candidateSamples.add(value.asText());
            }
            if (samples.size() < SAMPLE_ROWS_PER_PROBE) samples.add(row);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("market", market);
        result.put("candidateField", candidate);
        result.put("requestedFields", requestedFields);
        result.put("status", rows.size() == SOURCE_ROW_CAP ? "at_source_cap_incomplete"
                : rows.isEmpty() ? "empty_response_fields_unobservable" : "observed_below_source_cap");
        result.put("returnedRows", rows.size());
        result.put("actualFieldsObserved", List.copyOf(actualFields));
        result.put("candidateNonNullRows", nonNull);
        result.put("candidateDistinctValueSample", List.copyOf(candidateSamples));
        result.put("canonicalSampleRows", samples);
        return result;
    }

    private static Map<String, Object> failed(String market, String candidate, List<String> requestedFields,
                                               Exception failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        var result = new LinkedHashMap<String, Object>();
        result.put("market", market);
        result.put("candidateField", candidate);
        result.put("requestedFields", requestedFields);
        result.put("status", "request_or_contract_failed");
        result.put("failureType", root.getClass().getSimpleName());
        // Safe structured source diagnostics only. Never serialize exception messages, request bodies, or credentials.
        if (root instanceof com.zoutrankil.data.client.TushareFailure safe) {
            result.put("tushareFailureKind", safe.kind().name());
            result.put("tushareFailureCode", safe.code());
            result.put("rateLimited", safe.kind() == com.zoutrankil.data.client.TushareFailure.Kind.BUSINESS_RATE_LIMIT);
        }
        return result;
    }

    private static Map<String, Object> notAttempted(String market, String candidate, String reason) {
        var result = new LinkedHashMap<String, Object>();
        result.put("market", market);
        result.put("candidateField", candidate);
        result.put("requestedFields", List.of());
        result.put("status", "not_attempted");
        result.put("reason", reason);
        return result;
    }

    private static boolean isRateLimited(Throwable failure) {
        for (Throwable current = failure; current != null && current.getCause() != current; current = current.getCause()) {
            if (current instanceof com.zoutrankil.data.client.TushareFailure safe
                    && safe.kind() == com.zoutrankil.data.client.TushareFailure.Kind.BUSINESS_RATE_LIMIT) return true;
        }
        return false;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
