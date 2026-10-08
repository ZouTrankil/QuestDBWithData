package com.zoutrankil.data.web;

import com.zoutrankil.batch.MainStrategyDailyBatchRuntime;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Existing Web API authentication protects these controls; all work enters the same coordinator. */
@RestController
@RequestMapping(path="/api/v1/batch/main-strategy",produces=MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(prefix="app.sync.batch",name="enabled",havingValue="true",matchIfMissing=true)
public class MainStrategyDailyBatchController {
    private final MainStrategyDailyBatchRuntime runtime;
    public MainStrategyDailyBatchController(MainStrategyDailyBatchRuntime runtime) { this.runtime=runtime; }
    @GetMapping public Mono<?> status() { return call(runtime::status); }
    @GetMapping("/instances/{instance}") public Mono<?> detail(@PathVariable("instance")String instance) {
        if(!instance.matches("[a-f0-9]{64}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid instance identity");
        return call(()->runtime.detail(instance));
    }
    @PostMapping("/catch-up") public Mono<?> catchup(@RequestHeader(name="Idempotency-Key",required=false)String key) {
        String request=key==null?"manual:"+UUID.randomUUID():"manual:"+key;
        if(request.length()>256||request.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bounded idempotency key required");
        return call(()->runtime.catchup(request));
    }
    @PostMapping("/pause") public Mono<?> pause() { return call(()->runtime.setEnabled(false)); }
    public record PublicationReconciliation(String stage,String runId,boolean writerStopped){}
    @PostMapping("/instances/{instance}/reconcile-publication") public Mono<?> reconcilePublication(@PathVariable("instance")String instance,
            @RequestBody PublicationReconciliation body,@RequestHeader(name="Idempotency-Key",required=false)String key) {
        if(body==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Original publication request required");
        String request=key==null?"reconcile:"+UUID.randomUUID():"reconcile:"+key;
        if(request.length()>256||request.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Bounded idempotency key required");
        return call(()->runtime.reconcilePublication(instance,body.stage(),body.runId(),body.writerStopped(),request));
    }
    @PostMapping("/resume") public Mono<?> resume() { return call(()->runtime.setEnabled(true)); }
    private static Mono<?> call(java.util.concurrent.Callable<Map<String,Object>> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalStateException.class,error -> new ResponseStatusException(HttpStatus.CONFLICT,error.getMessage(),error))
                .onErrorMap(IllegalArgumentException.class,error -> new ResponseStatusException(HttpStatus.BAD_REQUEST,error.getMessage(),error));
    }
}
