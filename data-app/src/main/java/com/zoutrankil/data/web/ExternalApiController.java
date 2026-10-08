package com.zoutrankil.data.web;

import com.zoutrankil.data.domain.StockBasicLatest;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.service.DatasetReadService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.service.SyncJobRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Stable, read-first API for external callers. Blocking QuestDB calls run off the event loop. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExternalApiController {
    private final DatasetRegistry datasets;
    private final SyncJobRegistry jobs;
    private final StockBasicSyncService stockBasic;
    private final DatasetReadService reader;

    public ExternalApiController(DatasetRegistry datasets, SyncJobRegistry jobs,
                                 StockBasicSyncService stockBasic, DatasetReadService reader) {
        this.datasets = datasets;
        this.jobs = jobs;
        this.stockBasic = stockBasic;
        this.reader = reader;
    }

    @GetMapping("/health")
    public Mono<Map<String, Object>> health() {
        return Mono.just(Map.of("status", "UP", "service", "questdb-with-data", "webflux", true,
                "timestamp", Instant.now().toString()));
    }

    @GetMapping("/datasets")
    public Mono<List<?>> datasets() {
        return Mono.just(datasets.definitions());
    }

    @GetMapping("/jobs")
    public Mono<List<?>> jobs() {
        return Mono.just(jobs.definitions());
    }

    @GetMapping("/questdb/ping")
    public Mono<Map<String, String>> questDbPing() {
        return Mono.fromCallable(() -> {
            stockBasic.verifyQuestDbConnection();
            return Map.of("status", "UP", "database", "QuestDB");
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(org.springframework.dao.DataAccessException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QuestDB unavailable"));
    }

    @GetMapping("/datasets/{datasetId}/sample")
    public Mono<DatasetReadPage<DatasetValues>> datasetSample(
            @PathVariable("datasetId") String datasetId, @RequestParam(name = "limit", defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 100");
        }
        DatasetDefinition definition;
        try {
            definition = datasets.require(datasetId).definition();
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown dataset");
        }
        if (!definition.capabilities().contains(DatasetDefinition.Capability.READ)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset does not support reading");
        }
        var query = new DatasetReadQuery(definition.columns().stream()
                .map(DatasetDefinition.Column::logicalName).toList(), Map.of(), null, null, null, limit, null);
        return Mono.fromCallable(() -> reader.read(definition, query))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(DatasetReadService.NotReadyException.class,
                        error -> new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error))
                .onErrorMap(org.springframework.dao.DataAccessException.class,
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "QuestDB unavailable"));
    }

    @GetMapping("/stock-basic/latest")
    public Mono<List<StockBasicLatest>> latestStockBasic() {
        return Mono.fromCallable(stockBasic::loadLatestStocks).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/stock-basic/sync")
    public Mono<?> syncStockBasic() {
        return Mono.fromCallable(stockBasic::syncToQuestDb).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/info")
    public Mono<Map<String, Object>> info() {
        var body = new LinkedHashMap<String, Object>();
        body.put("apiVersion", "v1");
        body.put("endpoints", List.of("GET /api/v1/health", "GET /api/v1/questdb/ping",
                "GET /api/v1/datasets", "GET /api/v1/datasets/{datasetId}/sample?limit=20", "GET /api/v1/jobs",
                "GET /api/v1/stock-basic/latest", "POST /api/v1/stock-basic/sync"));
        body.put("authentication", "Bearer APP_API_TOKEN when configured");
        return Mono.just(body);
    }
}
