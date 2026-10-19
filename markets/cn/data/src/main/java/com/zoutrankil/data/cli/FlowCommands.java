package com.zoutrankil.data.cli;

import com.zoutrankil.data.flow.application.MoneyflowJobService;
import com.zoutrankil.data.flow.application.MoneyflowThsJobService;
import com.zoutrankil.data.flow.application.MoneyflowDcJobService;
import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import com.zoutrankil.data.margin.application.MarginDetailJobService;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class FlowCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-margin-detail-job", "run-margin-detail-job",
            "plan-moneyflow-hsgt-job", "run-moneyflow-hsgt-job", "plan-moneyflow-dc-job",
            "run-moneyflow-dc-job", "plan-moneyflow-ths-job", "run-moneyflow-ths-job",
            "plan-moneyflow-job", "run-moneyflow-job", "finish-moneyflow-hsgt-publication");
    private final com.zoutrankil.data.flow.application.MoneyflowHsgtJobService moneyflowHsgtService;
    private final com.zoutrankil.data.flow.application.MoneyflowDcJobService moneyflowDcService;
    private final com.zoutrankil.data.flow.application.MoneyflowThsJobService moneyflowThsService;
    private final com.zoutrankil.data.flow.application.MoneyflowJobService moneyflowService;

    private final com.zoutrankil.data.margin.application.MarginDetailJobService marginDetailService;

    @org.springframework.beans.factory.annotation.Autowired
    public FlowCommands(@org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowHsgtJobService moneyflowHsgtService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowDcJobService moneyflowDcService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowThsJobService moneyflowThsService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowJobService moneyflowService,
            @org.springframework.lang.Nullable com.zoutrankil.data.margin.application.MarginDetailJobService marginDetailService) {
        this.moneyflowHsgtService = moneyflowHsgtService;
        this.moneyflowDcService = moneyflowDcService;
        this.moneyflowThsService = moneyflowThsService;
        this.moneyflowService = moneyflowService;
        this.marginDetailService = marginDetailService;
    }

    public FlowCommands(@org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowHsgtJobService moneyflowHsgtService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowDcJobService moneyflowDcService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowThsJobService moneyflowThsService,
            @org.springframework.lang.Nullable com.zoutrankil.data.flow.application.MoneyflowJobService moneyflowService) {
        this(moneyflowHsgtService, moneyflowDcService, moneyflowThsService, moneyflowService, null);
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-margin-detail-job", "run-margin-detail-job" -> {
                if (marginDetailService == null) throw new IllegalStateException("Margin detail owner unavailable");
                if (options.containsKey("--resume-from")) {
                    if (!command.equals("run-margin-detail-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from and restores the frozen request");
                    var result = marginDetailService.resume(options.get("--resume-from"));
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || !java.util.Set.of(
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(result.state()))
                        throw new IncompleteCommandException("margin_detail resume incomplete: " + result.state() + "; run=" + result.runId());
                    return;
                }
                if (!options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --from, --to and --logical-date required; optional --mode");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : com.zoutrankil.data.domain.SyncJobDefinition.Mode.BACKFILL;
                var plan = marginDetailService.planDetailed(mode, java.time.LocalDate.parse(options.get("--from")),
                        java.time.LocalDate.parse(options.get("--to")), java.time.LocalDate.parse(options.get("--logical-date")));
                if (command.equals("plan-margin-detail-job"))
                    CliOutput.printJson(java.util.Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false, "plan", plan), CliOutput.Profile.JOB_DEFINITION, true);
                else {
                    var result = marginDetailService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || !java.util.Set.of(
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(result.state()))
                        throw new IncompleteCommandException("margin_detail sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-hsgt-job", "run-moneyflow-hsgt-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowHsgtService==null || !command.equals("run-moneyflow-hsgt-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowHsgtService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow-hsgt resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowHsgtService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-hsgt-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowHsgtService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = moneyflowHsgtService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow_hsgt sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-dc-job", "run-moneyflow-dc-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowDcService==null || !command.equals("run-moneyflow-dc-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowDcService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow-dc resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowDcService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-dc-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowDcService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = moneyflowDcService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow_dc sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-ths-job", "run-moneyflow-ths-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowThsService==null || !command.equals("run-moneyflow-ths-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowThsService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow-ths resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowThsService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-ths-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowThsService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = moneyflowThsService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow_ths sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-job", "run-moneyflow-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowService==null || !command.equals("run-moneyflow-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION)), CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = moneyflowService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "finish-moneyflow-hsgt-publication" -> {
                if(moneyflowHsgtService==null||!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))||!"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                moneyflowHsgtService.finishInterrupted(options.get("--run"),true);
                CliOutput.printJson(moneyflowHsgtService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
