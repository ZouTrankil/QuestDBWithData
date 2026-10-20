package com.zoutrankil.data.web;

import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import com.zoutrankil.data.strategy.store.PlanStoreException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;

/** 复用受鉴权保护的 Web 运行时；数值计算全部由 Java 执行。 */
@RestController
@RequestMapping(path = "/api/v1/main-strategy/data", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public final class MainStrategyDataController {
    private static final Logger LOG = LoggerFactory.getLogger(MainStrategyDataController.class);
    private final MainStrategyDataService data;

    public MainStrategyDataController(MainStrategyDataService data) {
        this.data = data;
    }

    public record ScoreRequest(LocalDate target, LocalDate startDate) {}

    public record PlanImportRequest(String sourcePath, String sourceSha256, String scoreRunId) {}

    @GetMapping
    public Mono<?> status() {
        return call(data::status);
    }

    @GetMapping("/scores")
    public Mono<?> scores(@RequestParam(name = "target", required = false) LocalDate target) {
        return call(() -> data.scores(target));
    }

    @GetMapping("/input-package")
    public Mono<?> inputPackage(@RequestParam(name = "target", required = false) LocalDate target) {
        return call(() -> data.inputPackage(target));
    }

    @PostMapping("/scores")
    public Mono<?> produce(@RequestBody ScoreRequest request) {
        if (request == null || request.target() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score target required");
        return call(
                () -> {
                    data.requireStart(request.startDate());
                    return data.prepareScores(
                            request.target(),
                            "java_scores_" + request.target() + "_" + UUID.randomUUID());
                });
    }

    @GetMapping("/plans")
    public Mono<?> plans() {
        return call(data::inspectPlans);
    }

    @PostMapping("/plans/import")
    public Mono<?> importPlan(@RequestBody PlanImportRequest request) {
        if (request == null || request.sourcePath() == null || request.sourceSha256() == null)
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Canonical source path and SHA256 required");
        return call(
                () ->
                        data.importPlan(
                                Path.of(request.sourcePath()),
                                request.sourceSha256(),
                                request.scoreRunId()));
    }

    private static Mono<?> call(Callable<?> work) {
        return Mono.fromCallable(work)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> LOG.error("Main strategy data request failed", error))
                .onErrorMap(
                        IllegalArgumentException.class,
                        e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e))
                .onErrorMap(
                        IllegalStateException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e))
                .onErrorMap(
                        PlanStoreException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e));
    }
}
