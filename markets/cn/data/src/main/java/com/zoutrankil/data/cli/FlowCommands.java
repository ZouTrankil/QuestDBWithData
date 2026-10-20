package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import com.zoutrankil.data.flow.application.MoneyflowJobService;
import com.zoutrankil.data.flow.application.MoneyflowThsJobService;
import com.zoutrankil.data.margin.application.MarginDetailJobService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class FlowCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of(
                    "plan-margin-detail-job",
                    "run-margin-detail-job",
                    "plan-moneyflow-hsgt-job",
                    "run-moneyflow-hsgt-job",
                    "plan-moneyflow-ths-job",
                    "run-moneyflow-ths-job",
                    "plan-moneyflow-job",
                    "run-moneyflow-job",
                    "reconcile-moneyflow-job",
                    "finish-moneyflow-hsgt-publication",
                    "discard-moneyflow-hsgt-stage");
    private final MoneyflowHsgtJobService moneyflowHsgtService;

    private final MoneyflowThsJobService moneyflowThsService;
    private final MoneyflowJobService moneyflowService;

    private final MarginDetailJobService marginDetailService;

    @Autowired
    public FlowCommands(
            @Nullable MoneyflowHsgtJobService moneyflowHsgtService,
            @Nullable MoneyflowThsJobService moneyflowThsService,
            @Nullable MoneyflowJobService moneyflowService,
            @Nullable MarginDetailJobService marginDetailService) {
        this.moneyflowHsgtService = moneyflowHsgtService;
        this.moneyflowThsService = moneyflowThsService;
        this.moneyflowService = moneyflowService;
        this.marginDetailService = marginDetailService;
    }

    public FlowCommands(
            @Nullable MoneyflowHsgtJobService moneyflowHsgtService,
            @Nullable MoneyflowThsJobService moneyflowThsService,
            @Nullable MoneyflowJobService moneyflowService) {
        this(moneyflowHsgtService, moneyflowThsService, moneyflowService, null);
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "discard-moneyflow-hsgt-stage" -> {
                if (moneyflowHsgtService == null
                        || !options.keySet().equals(Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException(
                            "Explicit --run and --writer-stopped true required");
                moneyflowHsgtService.discardUnpublished(options.get("--run"), true);
                CliOutput.printJson(
                        Map.of(
                                "runId",
                                options.get("--run"),
                                "unpublishedStageDiscarded",
                                true,
                                "formalPlanOrDataPublished",
                                false),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            case "reconcile-moneyflow-job" -> {
                if (moneyflowService == null
                        || !options.keySet().equals(Set.of("--run", "--writer-log")))
                    throw new IllegalArgumentException(
                            "Recovery requires --run RUN_ID --writer-log PATH");
                CliOutput.printJson(
                        moneyflowService.reconcile(
                                options.get("--run"), Path.of(options.get("--writer-log"))),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            case "plan-margin-detail-job", "run-margin-detail-job" -> {
                if (marginDetailService == null)
                    throw new IllegalStateException("Margin detail owner unavailable");
                if (options.containsKey("--resume-from")) {
                    if (!command.equals("run-margin-detail-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from and restores the frozen request");
                    var result = marginDetailService.resume(options.get("--resume-from"));
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(result.state()))
                        throw new IncompleteCommandException(
                                "margin_detail resume incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                    return;
                }
                if (!options.keySet().containsAll(Set.of("--from", "--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --from, --to and --logical-date required; optional --mode");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : Mode.BACKFILL;
                var plan =
                        marginDetailService.planDetailed(
                                mode,
                                LocalDate.parse(options.get("--from")),
                                LocalDate.parse(options.get("--to")),
                                LocalDate.parse(options.get("--logical-date")));
                if (command.equals("plan-margin-detail-job"))
                    CliOutput.printJson(
                            Map.of(
                                    "status",
                                    "PLANNED",
                                    "executed",
                                    false,
                                    "dataVerified",
                                    false,
                                    "plan",
                                    plan),
                            CliOutput.Profile.JOB_DEFINITION,
                            true);
                else {
                    var result = marginDetailService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(result.state()))
                        throw new IncompleteCommandException(
                                "margin_detail sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-moneyflow-hsgt-job", "run-moneyflow-hsgt-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (moneyflowHsgtService == null
                            || !command.equals("run-moneyflow-hsgt-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = moneyflowHsgtService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "moneyflow-hsgt resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (moneyflowHsgtService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-hsgt-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        moneyflowHsgtService.plan(
                                mode,
                                options.containsKey("--from")
                                        ? LocalDate.parse(options.get("--from"))
                                        : null,
                                LocalDate.parse(options.get("--to")),
                                LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(
                            Map.of(
                                    "status",
                                    "PLANNED",
                                    "executed",
                                    false,
                                    "dataVerified",
                                    false,
                                    "targetId",
                                    plan.targetId(),
                                    "plan",
                                    plan,
                                    "request",
                                    CliOutput.readTree(
                                            SyncRequestIdentity.snapshotJson(plan.request()),
                                            CliOutput.Profile.JOB_DEFINITION)),
                            CliOutput.Profile.JOB_DEFINITION,
                            true);
                } else {
                    var result = moneyflowHsgtService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "moneyflow_hsgt sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }

            case "plan-moneyflow-ths-job", "run-moneyflow-ths-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (moneyflowThsService == null
                            || !command.equals("run-moneyflow-ths-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = moneyflowThsService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "moneyflow-ths resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (moneyflowThsService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-ths-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        moneyflowThsService.plan(
                                mode,
                                options.containsKey("--from")
                                        ? LocalDate.parse(options.get("--from"))
                                        : null,
                                LocalDate.parse(options.get("--to")),
                                LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(
                            Map.of(
                                    "status",
                                    "PLANNED",
                                    "executed",
                                    false,
                                    "dataVerified",
                                    false,
                                    "targetId",
                                    plan.targetId(),
                                    "plan",
                                    plan,
                                    "request",
                                    CliOutput.readTree(
                                            SyncRequestIdentity.snapshotJson(plan.request()),
                                            CliOutput.Profile.JOB_DEFINITION)),
                            CliOutput.Profile.JOB_DEFINITION,
                            true);
                } else {
                    var result = moneyflowThsService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "moneyflow_ths sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-moneyflow-job", "run-moneyflow-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (moneyflowService == null
                            || !command.equals("run-moneyflow-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = moneyflowService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "moneyflow resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (moneyflowService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        moneyflowService.planDetailed(
                                mode,
                                options.containsKey("--from")
                                        ? LocalDate.parse(options.get("--from"))
                                        : null,
                                LocalDate.parse(options.get("--to")),
                                LocalDate.parse(options.get("--logical-date")));
                if (planOnly) {
                    CliOutput.printJson(
                            Map.of(
                                    "status",
                                    "PLANNED",
                                    "executed",
                                    false,
                                    "dataVerified",
                                    false,
                                    "targetId",
                                    plan.targetId(),
                                    "plan",
                                    plan,
                                    "request",
                                    CliOutput.readTree(
                                            SyncRequestIdentity.snapshotJson(plan.request()),
                                            CliOutput.Profile.JOB_DEFINITION)),
                            CliOutput.Profile.JOB_DEFINITION,
                            true);
                } else {
                    var result = moneyflowService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "moneyflow sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "finish-moneyflow-hsgt-publication" -> {
                if (moneyflowHsgtService == null
                        || !options.keySet().equals(Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException(
                            "Explicit --run and --writer-stopped true required");
                moneyflowHsgtService.finishInterrupted(options.get("--run"), true);
                CliOutput.printJson(
                        moneyflowHsgtService.status(options.get("--run")),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
