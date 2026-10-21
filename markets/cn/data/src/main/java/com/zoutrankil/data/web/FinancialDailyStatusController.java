package com.zoutrankil.data.web;

import com.zoutrankil.data.financial.application.FinancialDailyQueue;
import com.zoutrankil.data.financial.config.FinancialQueueFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.Map;

/** 沿用现有 jobs API 展示原队列，不启用采集，也不改未决运行。 */
@RestController
public final class FinancialDailyStatusController {
    private final FinancialDailyQueue queue;

    public FinancialDailyStatusController(
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        queue = FinancialQueueFactory.forLedger(Path.of(ledger));
    }

    @GetMapping("/api/v1/jobs/financial-daily/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(queue::status).subscribeOn(Schedulers.boundedElastic());
    }
}
