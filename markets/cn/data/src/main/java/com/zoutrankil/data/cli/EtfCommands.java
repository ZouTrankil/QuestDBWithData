package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.application.EtfBasicJobService;
import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.application.EtfFactorJobService;
import com.zoutrankil.data.etf.application.EtfPortfolioJobService;
import com.zoutrankil.data.etf.application.EtfShareJobService;

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
public final class EtfCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of(
                    "plan-etf-daily-job",
                    "run-etf-daily-job",
                    "reconcile-etf-daily-job",
                    "plan-etf-adj-job",
                    "run-etf-adj-job",
                    "reconcile-etf-adj-job",
                    "plan-etf-share-job",
                    "run-etf-share-job",
                    "plan-etf-factor-job",
                    "run-etf-factor-job",
                    "plan-etf-portfolio-job",
                    "run-etf-portfolio-job",
                    "plan-etf-basic-job",
                    "run-etf-basic-job");
    private final EtfDailyJobService etfDailyService;
    private final EtfAdjJobService etfAdjService;
    private final EtfShareJobService etfShareService;
    private final EtfFactorJobService etfFactorService;
    private final EtfPortfolioJobService etfPortfolioService;
    private final EtfBasicJobService etfBasicService;

    public EtfCommands(
            @Nullable EtfDailyJobService etfDailyService,
            @Nullable EtfAdjJobService etfAdjService,
            @Nullable EtfShareJobService etfShareService,
            @Nullable EtfFactorJobService etfFactorService,
            @Nullable EtfPortfolioJobService etfPortfolioService,
            @Nullable EtfBasicJobService etfBasicService) {
        this.etfDailyService = etfDailyService;
        this.etfAdjService = etfAdjService;
        this.etfShareService = etfShareService;
        this.etfFactorService = etfFactorService;
        this.etfPortfolioService = etfPortfolioService;
        this.etfBasicService = etfBasicService;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "reconcile-etf-adj-job" -> {
                if (etfAdjService == null
                        || !options.keySet().equals(Set.of("--run", "--writer-log")))
                    throw new IllegalArgumentException(
                            "Recovery requires --run RUN_ID --writer-log PATH");
                CliOutput.printJson(
                        etfAdjService.reconcile(
                                options.get("--run"), Path.of(options.get("--writer-log"))),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            case "reconcile-etf-daily-job" -> {
                if (etfDailyService == null
                        || !options.keySet().equals(Set.of("--run", "--writer-log")))
                    throw new IllegalArgumentException(
                            "Recovery requires --run RUN_ID --writer-log PATH");
                CliOutput.printJson(
                        etfDailyService.reconcile(
                                options.get("--run"), Path.of(options.get("--writer-log"))),
                        CliOutput.Profile.JOB_DEFINITION,
                        true);
            }
            case "plan-etf-daily-job", "run-etf-daily-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (etfDailyService == null
                            || !command.equals("run-etf-daily-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = etfDailyService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "etf-daily resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (etfDailyService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-daily-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        etfDailyService.plan(
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
                    var result =
                            options.containsKey("--resume-from")
                                    ? etfDailyService.resume(plan, options.get("--resume-from"))
                                    : etfDailyService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "etf_daily sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-etf-adj-job", "run-etf-adj-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (etfAdjService == null
                            || !command.equals("run-etf-adj-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = etfAdjService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "etf-adj resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (etfAdjService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-adj-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        etfAdjService.plan(
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
                    var result =
                            options.containsKey("--resume-from")
                                    ? etfAdjService.resume(plan, options.get("--resume-from"))
                                    : etfAdjService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "etf_adj sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-etf-share-job", "run-etf-share-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (etfShareService == null
                            || !command.equals("run-etf-share-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = etfShareService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "etf-share resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (etfShareService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-share-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        etfShareService.plan(
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
                    var result = etfShareService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "etf_share sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-etf-factor-job", "run-etf-factor-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (etfFactorService == null
                            || !command.equals("run-etf-factor-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = etfFactorService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "etf-factor resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (etfFactorService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-factor-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        etfFactorService.plan(
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
                    var result = etfFactorService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "etf_factor sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-etf-portfolio-job", "run-etf-portfolio-job" -> {
                if (options.containsKey("--resume-from")) {
                    if (etfPortfolioService == null
                            || !command.equals("run-etf-portfolio-job")
                            || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; the original frozen scope is restored");
                    var restored = etfPortfolioService.resume(options.get("--resume-from"));
                    CliOutput.printJson(restored, CliOutput.Profile.JOB_DEFINITION, true);
                    if (restored.errorCode() != null
                            || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                                    .contains(restored.state()))
                        throw new IncompleteCommandException(
                                "etf-portfolio resume incomplete: "
                                        + restored.state()
                                        + "; run="
                                        + restored.runId());
                    return;
                }
                if (etfPortfolioService == null
                        || !options.keySet().containsAll(Set.of("--to", "--logical-date"))
                        || !Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-portfolio-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                var plan =
                        etfPortfolioService.plan(
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
                    var result = etfPortfolioService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null
                            || result.state() != SyncRunState.VERIFIED
                                    && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "etf_portfolio sync incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                }
            }
            case "plan-etf-basic-job", "run-etf-basic-job" -> {
                if (etfBasicService == null)
                    throw new IllegalArgumentException("ETF basic service unavailable");
                boolean planOnly = command.equals("plan-etf-basic-job");
                if (options.containsKey("--resume-from")) {
                    if (planOnly || !options.keySet().equals(Set.of("--resume-from")))
                        throw new IllegalArgumentException(
                                "Resume requires only --resume-from; saved observation time and scope are restored");
                    var result = etfBasicService.resume(options.get("--resume-from"));
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.state() != SyncRunState.VERIFIED)
                        throw new IncompleteCommandException(
                                "etf_basic resume incomplete: "
                                        + result.state()
                                        + "; run="
                                        + result.runId());
                } else {
                    if (!options.keySet().equals(Set.of("--logical-date")))
                        throw new IllegalArgumentException(
                                "ETF basic snapshot requires --logical-date YYYY-MM-DD");
                    var plan = etfBasicService.plan(LocalDate.parse(options.get("--logical-date")));
                    if (planOnly)
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
                    else {
                        var result = etfBasicService.run(plan);
                        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                        if (result.state() != SyncRunState.VERIFIED)
                            throw new IncompleteCommandException(
                                    "etf_basic snapshot incomplete: "
                                            + result.state()
                                            + "; run="
                                            + result.runId());
                    }
                }
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
