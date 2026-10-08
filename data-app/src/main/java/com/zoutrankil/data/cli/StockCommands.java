package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.DailyJobService;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.application.StockFactorJobService;
import com.zoutrankil.data.stock.application.StockLimitJobService;
import com.zoutrankil.data.stock.application.StockStDailyJobService;
import com.zoutrankil.data.stock.application.StockSuspendJobService;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class StockCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-daily-job", "run-daily-job", "plan-daily-basic-job",
            "run-daily-basic-job", "plan-stk-factor-job", "run-stk-factor-job",
            "plan-stk-limit-job", "run-stk-limit-job", "plan-stk-st-daily-job",
            "run-stk-st-daily-job", "plan-stk-suspend-job", "run-stk-suspend-job",
            "finish-stk-suspend-publication", "finish-stk-st-daily-publication", "run-stock-basic-job",
            "plan-stock-detail-job", "run-stock-detail-job", "reconcile-stock-detail-run",
            "finish-stock-detail-publication");
    private final com.zoutrankil.data.stock.application.StockBasicJobService jobService;
    private final com.zoutrankil.data.stock.application.StockDetailInfoJobService stockDetailService;
    private final com.zoutrankil.data.stock.application.DailyJobService dailyService;
    private final com.zoutrankil.data.stock.application.DailyBasicJobService dailyBasicService;
    private final com.zoutrankil.data.stock.application.StockFactorJobService stockFactorService;
    private final com.zoutrankil.data.stock.application.StockLimitJobService stockLimitService;
    private final com.zoutrankil.data.stock.application.StockStDailyJobService stockStDailyService;
    private final com.zoutrankil.data.stock.application.StockSuspendJobService stockSuspendService;

    public StockCommands(com.zoutrankil.data.stock.application.StockBasicJobService jobService,
            com.zoutrankil.data.stock.application.StockDetailInfoJobService stockDetailService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.DailyJobService dailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.DailyBasicJobService dailyBasicService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.StockFactorJobService stockFactorService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.StockLimitJobService stockLimitService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.StockStDailyJobService stockStDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.stock.application.StockSuspendJobService stockSuspendService) {
        this.jobService = jobService;
        this.stockDetailService = stockDetailService;
        this.dailyService = dailyService;
        this.dailyBasicService = dailyBasicService;
        this.stockFactorService = stockFactorService;
        this.stockLimitService = stockLimitService;
        this.stockStDailyService = stockStDailyService;
        this.stockSuspendService = stockSuspendService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-daily-job", "run-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dailyService==null || !command.equals("run-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dailyService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dailyService == null || !options.keySet().containsAll(java.util.Set.of("--from", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --from and --logical-date required; optional --to, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-daily-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = dailyService.plan(java.time.LocalDate.parse(options.get("--from")),
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        java.time.LocalDate.parse(options.get("--logical-date")), mode);
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? dailyService.resume(plan, options.get("--resume-from")) : dailyService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-daily-basic-job", "run-daily-basic-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dailyBasicService==null || !command.equals("run-daily-basic-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dailyBasicService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("daily-basic resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dailyBasicService == null || !options.keySet().containsAll(java.util.Set.of("--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; --from is required for bootstrap/backfill, optional --to, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-daily-basic-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var end = options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : logicalDate;
                var plan = dailyBasicService.plan(options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        end, logicalDate, mode);
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? dailyBasicService.resume(plan, options.get("--resume-from")) : dailyBasicService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("daily_basic sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-factor-job", "run-stk-factor-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockFactorService==null || !command.equals("run-stk-factor-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=stockFactorService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("stk-factor resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockFactorService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--ts-code", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from, --mode, --ts-code, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-factor-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = stockFactorService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")), options.get("--ts-code"));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockFactorService.resume(plan.request(), options.get("--resume-from"))
                            : stockFactorService.run(plan.request());
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_factor sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-limit-job", "run-stk-limit-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockLimitService==null || !command.equals("run-stk-limit-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=stockLimitService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("stk-limit resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockLimitService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-limit-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = stockLimitService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockLimitService.resume(plan, options.get("--resume-from")) : stockLimitService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_limit sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-st-daily-job", "run-stk-st-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockStDailyService==null || !command.equals("run-stk-st-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    String prior=options.get("--resume-from");
                    var restored=stockStDailyService.resume(stockStDailyService.restorePlan(prior),prior);
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            &&restored.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_st_daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockStDailyService == null || !options.containsKey("--logical-date")
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; optional --from, --to (defaults to --logical-date), --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-st-daily-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var plan = stockStDailyService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        logicalDate);
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockStDailyService.resume(plan, options.get("--resume-from")) : stockStDailyService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_st_daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-suspend-job", "run-stk-suspend-job" -> {
                if(stockSuspendService==null) throw new IllegalArgumentException("Stock suspension service unavailable");
                if(options.containsKey("--resume-from")) {
                    if(!command.equals("run-stk-suspend-job") || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var result=stockSuspendService.resume(options.get("--resume-from"));
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if(result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            &&result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_suspend resume incomplete: "+result.state()+"; run="+result.runId());
                    return;
                }
                if(!options.containsKey("--to") || !options.containsKey("--logical-date")
                        || !java.util.Set.of("--from","--to","--logical-date","--mode").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from and --mode");
                var mode=options.containsKey("--mode")?com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)):null;
                var plan=stockSuspendService.planDetailed(mode,
                        options.containsKey("--from")?java.time.LocalDate.parse(options.get("--from")):null,
                        java.time.LocalDate.parse(options.get("--to")),java.time.LocalDate.parse(options.get("--logical-date")));
                if(command.equals("plan-stk-suspend-job")) {
                    CliOutput.printJson(Map.of(
                            "status","PLANNED","executed",false,"dataVerified",false,"targetId",plan.targetId(),
                            "plan",plan,"request",CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result=stockSuspendService.run(plan.request());
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if(result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            &&result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_suspend incomplete: "+result.state()+"; run="+result.runId());
                }
            }
            case "finish-stk-suspend-publication", "finish-stk-st-daily-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        ||!"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                if(command.equals("finish-stk-suspend-publication")) {
                    if(stockSuspendService==null)throw new IllegalArgumentException("Stock suspension service unavailable");
                    stockSuspendService.finishPublication(options.get("--run"),true);
                    CliOutput.printJson(stockSuspendService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    if(stockStDailyService==null)throw new IllegalArgumentException("ST daily service unavailable");
                    CliOutput.printJson(stockStDailyService.finishInterrupted(options.get("--run"),true), CliOutput.Profile.JOB_DEFINITION, true);
                }
            }
            case "run-stock-basic-job" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = options.containsKey("--resume-from")
                        ? jobService.resume(codes,day,options.get("--resume-from")) : jobService.run(codes,day);
                CliOutput.printJson(result, CliOutput.Profile.PLAIN, true);
                if (result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException("Sync did not complete: " + result.state() + "; run=" + result.runId());
            }
            case "plan-stock-detail-job", "run-stock-detail-job" -> {
                if (!options.containsKey("--logical-date")
                        || !java.util.Set.of("--logical-date","--codes","--discover","--resume-from").containsAll(options.keySet())
                        || options.containsKey("--codes") == options.containsKey("--discover"))
                    throw new IllegalArgumentException("Specify --logical-date and exactly one of --codes or --discover true");
                if(command.equals("plan-stock-detail-job") && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                if (options.containsKey("--discover") && !"true".equals(options.get("--discover")))
                    throw new IllegalArgumentException("Discovery requires --discover true");
                var codes=options.containsKey("--codes")
                        ? java.util.Arrays.asList(options.get("--codes").split(",",-1)) : java.util.List.<String>of();
                var day=java.time.LocalDate.parse(options.get("--logical-date"));
                var frozen=stockDetailService.plan(codes,options.containsKey("--discover"),day);
                if (command.equals("plan-stock-detail-job")) {
                    CliOutput.printJson(Map.of(
                                    "status","PLANNED","executed",false,"dataVerified",false,
                                    "targetId",stockDetailService.targetId(),"request",
                                    CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(frozen), CliOutput.Profile.PLAIN)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result=options.containsKey("--resume-from")
                            ? stockDetailService.resume(frozen,options.get("--resume-from"))
                            : stockDetailService.run(frozen);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Stock-detail sync incomplete: "+result.state()+"; run="+result.runId());
                }
            }
            case "reconcile-stock-detail-run", "finish-stock-detail-publication" -> {
                if (!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                var result=command.equals("finish-stock-detail-publication")
                        ?stockDetailService.finishInterrupted(options.get("--run"),true)
                        :stockDetailService.reconcilePublished(options.get("--run"),true);
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
