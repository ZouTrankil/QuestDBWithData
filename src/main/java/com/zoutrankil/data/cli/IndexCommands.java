package com.zoutrankil.data.cli;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class IndexCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-ths-member-job", "run-ths-member-job", "finish-ths-member-publication",
            "discover-index-member-catalog", "plan-index-member-job", "run-index-member-job",
            "finish-index-member-child", "finish-index-member-prepared", "plan-ths-index-job",
            "run-ths-index-job", "finish-ths-index-publication", "plan-index-catalog-job",
            "run-index-catalog-job", "finish-index-catalog-publication", "plan-dc-index-job",
            "run-dc-index-job", "plan-index-daily-market-job", "run-index-daily-market-job",
            "plan-index-monthly-job", "run-index-monthly-job", "plan-index-daily-basic-job",
            "run-index-daily-basic-job", "plan-index-weight-job", "run-index-weight-job",
            "list-index-daily-market-universe", "finish-index-monthly-publication", "finish-dc-index-publication");
    private final com.zoutrankil.data.service.ThsIndexJobService thsIndexService;
    private final com.zoutrankil.data.service.IndexMembershipJobService indexMembershipService;
    private final com.zoutrankil.data.service.ThsMemberJobService thsMemberService;
    private final com.zoutrankil.data.service.IndexCatalogJobService indexCatalogService;
    private final com.zoutrankil.data.service.IndexDailyMarketJobService indexDailyMarketService;
    private final com.zoutrankil.data.service.IndexDailyBasicJobService indexDailyBasicService;
    private final com.zoutrankil.data.service.IndexWeightJobService indexWeightService;
    private final com.zoutrankil.data.service.IndexMonthlyJobService indexMonthlyService;
    private final com.zoutrankil.data.service.DcIndexJobService dcIndexService;

    public IndexCommands(@org.springframework.lang.Nullable com.zoutrankil.data.service.ThsIndexJobService thsIndexService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.IndexMembershipJobService indexMembershipService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.ThsMemberJobService thsMemberService,
            com.zoutrankil.data.service.IndexCatalogJobService indexCatalogService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.IndexDailyMarketJobService indexDailyMarketService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.IndexDailyBasicJobService indexDailyBasicService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.IndexWeightJobService indexWeightService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.IndexMonthlyJobService indexMonthlyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.DcIndexJobService dcIndexService) {
        this.thsIndexService = thsIndexService;
        this.indexMembershipService = indexMembershipService;
        this.thsMemberService = thsMemberService;
        this.indexCatalogService = indexCatalogService;
        this.indexDailyMarketService = indexDailyMarketService;
        this.indexDailyBasicService = indexDailyBasicService;
        this.indexWeightService = indexWeightService;
        this.indexMonthlyService = indexMonthlyService;
        this.dcIndexService = dcIndexService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-ths-member-job", "run-ths-member-job" -> {
                if (thsMemberService == null || !options.keySet().equals(java.util.Set.of("--board-code", "--logical-date")))
                    throw new IllegalArgumentException("Exact THS board and logical date required");
                var request = thsMemberService.plan(options.get("--board-code"),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (command.equals("plan-ths-member-job"))
                    CliOutput.printJson(Map.of("status", "PLANNED", "executed", false,
                            "dataVerified", false, "targetId", thsMemberService.targetId(),
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(request), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, false);
                else {
                    var result = thsMemberService.run(request);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("THS member sync incomplete: " + result.state());
                }
            }
            case "finish-ths-member-publication" -> {
                if (thsMemberService == null || !options.keySet().equals(java.util.Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit THS member run and stopped writer proof required");
                var result = thsMemberService.finishInterrupted(options.get("--run"), true);
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "discover-index-member-catalog", "plan-index-member-job", "run-index-member-job", "finish-index-member-child", "finish-index-member-prepared" ->
                    IndexMembershipCommands.execute(command,options,indexMembershipService);
            case "plan-ths-index-job", "run-ths-index-job" -> {
                if(thsIndexService==null || !options.keySet().contains("--logical-date")
                        || !java.util.Set.of("--logical-date","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit THS logical-date and registered owner required");
                boolean plan=command.equals("plan-ths-index-job");
                if(plan && options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume is an execution option");
                var request=thsIndexService.plan(java.time.LocalDate.parse(options.get("--logical-date")));
                if(plan) CliOutput.printJson(Map.of("status","PLANNED","executed",false,
                        "dataVerified",false,"request",CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(request), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, false);
                else {
                    var result=options.containsKey("--resume-from")
                            ? thsIndexService.resume(request,options.get("--resume-from")) : thsIndexService.run(request);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
                    if(result.errorCode()!=null || result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED)
                        throw new IncompleteCommandException("THS sync incomplete: "+result.state());
                }
            }
            case "finish-ths-index-publication" -> {
                if(thsIndexService==null || !options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit run and writer-stopped true required");
                var result=thsIndexService.finishInterrupted(options.get("--run"),true);
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "plan-index-catalog-job", "run-index-catalog-job" -> {
                if(!options.keySet().containsAll(java.util.Set.of("--file","--logical-date"))
                        || !java.util.Set.of("--file","--logical-date","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit catalog file and logical-date required");
                boolean plan=command.equals("plan-index-catalog-job");
                if(plan && options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume is an execution option");
                var request=indexCatalogService.plan(Path.of(options.get("--file")),java.time.LocalDate.parse(options.get("--logical-date")));
                if(plan) CliOutput.printJson(Map.of("status","PLANNED","executed",false,
                        "dataVerified",false,"request",CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(request), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, false);
                else {
                    var result=options.containsKey("--resume-from")
                            ? indexCatalogService.resume(request,options.get("--resume-from")) : indexCatalogService.run(request);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
                    if(result.errorCode()!=null || result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Catalog sync incomplete: "+result.state());
                }
            }
            case "finish-index-catalog-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit run and writer-stopped true required");
                var result=indexCatalogService.finishInterrupted(options.get("--run"),true);
                CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, false);
            }
            case "plan-dc-index-job", "run-dc-index-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dcIndexService==null || !command.equals("run-dc-index-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dcIndexService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("dc-index resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dcIndexService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-dc-index-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = dcIndexService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = dcIndexService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("dc_index sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-daily-market-job", "run-index-daily-market-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexDailyMarketService==null || !command.equals("run-index-daily-market-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexDailyMarketService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-daily-market resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexDailyMarketService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-daily-market-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexDailyMarketService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = indexDailyMarketService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_daily_market sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-monthly-job", "run-index-monthly-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexMonthlyService==null || !command.equals("run-index-monthly-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexMonthlyService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-monthly resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexMonthlyService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-monthly-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexMonthlyService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = indexMonthlyService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_monthly sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-daily-basic-job", "run-index-daily-basic-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexDailyBasicService==null || !command.equals("run-index-daily-basic-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexDailyBasicService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-daily-basic resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexDailyBasicService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-daily-basic-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexDailyBasicService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = indexDailyBasicService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_daily_basic sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-weight-job", "run-index-weight-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexWeightService==null || !command.equals("run-index-weight-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexWeightService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-weight resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexWeightService == null || !options.keySet().containsAll(java.util.Set.of("--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from", "--force").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; optional --mode SNAPSHOT|BACKFILL, --from/--to for BACKFILL, --force true|false, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-weight-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                if(options.containsKey("--force") && !java.util.Set.of("true","false").contains(options.get("--force")))
                    throw new IllegalArgumentException("--force must be true or false");
                var plan = indexWeightService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        java.time.LocalDate.parse(options.get("--logical-date")), Boolean.parseBoolean(options.getOrDefault("--force","false")));
                if (plan.notDue()) {
                    CliOutput.printJson(Map.of(
                            "status", "NOT_DUE", "executed", false, "dataVerified", false, "sourceRequests", 0,
                            "lastRefreshDate", plan.lastRefreshDate(), "nextRefreshDate", plan.nextRefreshDate(), "plan", plan), CliOutput.Profile.JOB_DEFINITION, true);
                    return;
                }
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = indexWeightService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_weight sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "list-index-daily-market-universe" -> {
                if(!options.isEmpty())throw new IllegalArgumentException("Universe listing accepts no options");
                CliOutput.printJson(Map.of(
                        "CORE57",com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse.CORE57,
                        "SW2021_L1_31",com.zoutrankil.data.domain.policy.IndexDailyMarketUniverse.SW2021_L1_31), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "finish-index-monthly-publication", "finish-dc-index-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped")) || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                if(command.equals("finish-index-monthly-publication")) {
                    if(indexMonthlyService==null)throw new IllegalArgumentException("Monthly service unavailable");
                    CliOutput.printJson(indexMonthlyService.finishInterrupted(options.get("--run"),true), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    if(dcIndexService==null)throw new IllegalArgumentException("DC index service unavailable");
                    dcIndexService.finishPublication(options.get("--run"),true);
                    CliOutput.printJson(Map.of("status","RECOVERED","runId",options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
                }
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
