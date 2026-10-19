package com.zoutrankil.data.web;

import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Same authenticated Web runtime; numerical production runs entirely in Java. */
@RestController
@RequestMapping(path="/api/v1/main-strategy/data",produces=MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.REACTIVE)
public final class MainStrategyDataController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MainStrategyDataController.class);
    private final MainStrategyDataService data;
    public MainStrategyDataController(MainStrategyDataService data){this.data=data;}
    public record ScoreRequest(LocalDate target,LocalDate startDate) {}
    public record PlanImportRequest(String sourcePath,String sourceSha256,String scoreRunId) {}
    @GetMapping public Mono<?> status(){return call(data::status);}
    @GetMapping("/scores") public Mono<?> scores(@RequestParam(name="target",required=false)LocalDate target){return call(()->data.scores(target));}
    @GetMapping("/input-package") public Mono<?> inputPackage(@RequestParam(name="target",required=false)LocalDate target){return call(()->data.inputPackage(target));}
    @PostMapping("/scores") public Mono<?> produce(@RequestBody ScoreRequest request) {
        if(request==null||request.target()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Score target required");
        return call(()->{data.requireStart(request.startDate());return data.prepareScores(request.target(),"java_scores_"+request.target()+"_"+UUID.randomUUID());});
    }
    @GetMapping("/plans") public Mono<?> plans(){return call(data::inspectPlans);}
    @PostMapping("/plans/import") public Mono<?> importPlan(@RequestBody PlanImportRequest request){
        if(request==null||request.sourcePath()==null||request.sourceSha256()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Canonical source path and SHA256 required");
        return call(()->data.importPlan(java.nio.file.Path.of(request.sourcePath()),request.sourceSha256(),request.scoreRunId()));
    }
    private static Mono<?> call(java.util.concurrent.Callable<?> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic()).doOnError(error->LOG.error("Main strategy data request failed",error)).onErrorMap(IllegalArgumentException.class,e->new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage(),e)).onErrorMap(IllegalStateException.class,e->new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage(),e)).onErrorMap(com.zoutrankil.data.strategy.store.PlanStoreException.class,e->new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage(),e));}
}
