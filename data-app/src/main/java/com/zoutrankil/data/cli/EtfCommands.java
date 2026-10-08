package com.zoutrankil.data.cli;

import com.zoutrankil.data.etf.application.EtfBasicJobService;
import com.zoutrankil.data.etf.application.EtfPortfolioJobService;
import com.zoutrankil.data.etf.application.EtfShareJobService;

import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.application.EtfFactorJobService;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class EtfCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-etf-daily-job", "run-etf-daily-job", "plan-etf-adj-job",
            "run-etf-adj-job", "plan-etf-share-job", "run-etf-share-job",
            "plan-etf-factor-job", "run-etf-factor-job", "plan-etf-portfolio-job",
            "run-etf-portfolio-job", "plan-etf-basic-job", "run-etf-basic-job");
    private final com.zoutrankil.data.etf.application.EtfDailyJobService etfDailyService;
    private final com.zoutrankil.data.etf.application.EtfAdjJobService etfAdjService;
    private final com.zoutrankil.data.etf.application.EtfShareJobService etfShareService;
    private final com.zoutrankil.data.etf.application.EtfFactorJobService etfFactorService;
    private final com.zoutrankil.data.etf.application.EtfPortfolioJobService etfPortfolioService;
    private final com.zoutrankil.data.etf.application.EtfBasicJobService etfBasicService;

    public EtfCommands(@org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfDailyJobService etfDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfAdjJobService etfAdjService,
            @org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfShareJobService etfShareService,
            @org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfFactorJobService etfFactorService,
            @org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfPortfolioJobService etfPortfolioService,
            @org.springframework.lang.Nullable com.zoutrankil.data.etf.application.EtfBasicJobService etfBasicService) {
        this.etfDailyService = etfDailyService;
        this.etfAdjService = etfAdjService;
        this.etfShareService = etfShareService;
        this.etfFactorService = etfFactorService;
        this.etfPortfolioService = etfPortfolioService;
        this.etfBasicService = etfBasicService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-etf-daily-job", "run-etf-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfDailyService==null || !command.equals("run-etf-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfDailyService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfDailyService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-daily-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfDailyService.plan(mode,
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
                            ? etfDailyService.resume(plan, options.get("--resume-from")) : etfDailyService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-adj-job", "run-etf-adj-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfAdjService==null || !command.equals("run-etf-adj-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfAdjService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-adj resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfAdjService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-adj-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfAdjService.plan(mode,
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
                            ? etfAdjService.resume(plan, options.get("--resume-from")) : etfAdjService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_adj sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-share-job", "run-etf-share-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfShareService==null || !command.equals("run-etf-share-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfShareService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-share resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfShareService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-share-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfShareService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = etfShareService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_share sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-factor-job", "run-etf-factor-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfFactorService==null || !command.equals("run-etf-factor-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfFactorService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-factor resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfFactorService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-factor-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfFactorService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = etfFactorService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_factor sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-portfolio-job", "run-etf-portfolio-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfPortfolioService==null || !command.equals("run-etf-portfolio-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfPortfolioService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-portfolio resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfPortfolioService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-portfolio-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfPortfolioService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = etfPortfolioService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_portfolio sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-basic-job", "run-etf-basic-job" -> {
                if (etfBasicService == null) throw new IllegalArgumentException("ETF basic service unavailable");
                boolean planOnly = command.equals("plan-etf-basic-job");
                if (options.containsKey("--resume-from")) {
                    if (planOnly || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; saved observation time and scope are restored");
                    var result = etfBasicService.resume(options.get("--resume-from"));
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED)
                        throw new IncompleteCommandException("etf_basic resume incomplete: " + result.state() + "; run=" + result.runId());
                } else {
                    if (!options.keySet().equals(java.util.Set.of("--logical-date")))
                        throw new IllegalArgumentException("ETF basic snapshot requires --logical-date YYYY-MM-DD");
                    var plan = etfBasicService.plan(java.time.LocalDate.parse(options.get("--logical-date")));
                    if (planOnly) CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false, "targetId", plan.targetId(),
                            "plan", plan, "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                    else {
                        var result = etfBasicService.run(plan);
                        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                        if (result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED)
                            throw new IncompleteCommandException("etf_basic snapshot incomplete: " + result.state() + "; run=" + result.runId());
                    }
                }
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
