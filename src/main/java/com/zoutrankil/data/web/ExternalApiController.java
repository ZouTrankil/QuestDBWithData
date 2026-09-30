package com.zoutrankil.data.web;

import com.zoutrankil.data.domain.StockBasicLatest;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.StockBasicSyncService;
import com.zoutrankil.data.service.SyncJobRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Stable, read-first API for external callers. Blocking QuestDB calls run off the event loop. */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExternalApiController {
    private final DatasetRegistry datasets;
    private final SyncJobRegistry jobs;
    private final StockBasicSyncService stockBasic;

    public ExternalApiController(DatasetRegistry datasets, SyncJobRegistry jobs, StockBasicSyncService stockBasic) {
        this.datasets = datasets;
        this.jobs = jobs;
        this.stockBasic = stockBasic;
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
        body.put("endpoints", List.of("GET /api/v1/health", "GET /api/v1/datasets", "GET /api/v1/jobs",
                "GET /api/v1/stock-basic/latest", "POST /api/v1/stock-basic/sync"));
        body.put("authentication", "Bearer APP_API_TOKEN when configured");
        return Mono.just(body);
    }
}
