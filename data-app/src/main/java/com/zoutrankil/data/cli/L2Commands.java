package com.zoutrankil.data.cli;

import com.zoutrankil.data.l2.application.L2DailyFeaturesJobService;
import com.zoutrankil.data.l2.application.L2DatasetManifestJobService;
import com.zoutrankil.data.l2.application.L2EventResponseFeaturesJobService;
import com.zoutrankil.data.l2.application.L2IntradayBarFeaturesJobService;
import com.zoutrankil.data.l2.application.L2T0TrainingLabelsJobService;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class L2Commands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "create-l2-manifest-test-target", "plan-l2-manifest-job", "run-l2-manifest-job",
            "l2-manifest-job-status", "cancel-l2-manifest-run", "create-l2-daily-features-test-target",
            "plan-l2-daily-features-job", "run-l2-daily-features-job", "l2-daily-features-job-status",
            "cancel-l2-daily-features-run", "create-l2-intraday-bar-features-test-target", "plan-l2-intraday-bar-features-job",
            "run-l2-intraday-bar-features-job", "l2-intraday-bar-features-job-status", "cancel-l2-intraday-bar-features-run",
            "create-l2-event-response-features-test-target", "plan-l2-event-response-features-job", "run-l2-event-response-features-job",
            "l2-event-response-features-job-status", "cancel-l2-event-response-features-run", "create-l2-t0-training-labels-test-target",
            "plan-l2-t0-training-labels-job", "run-l2-t0-training-labels-job", "l2-t0-training-labels-job-status",
            "cancel-l2-t0-training-labels-run");
    private final com.zoutrankil.data.l2.application.L2DatasetManifestJobService l2ManifestService;
    private final com.zoutrankil.data.l2.application.L2DailyFeaturesJobService l2DailyFeaturesService;
    private final com.zoutrankil.data.l2.application.L2IntradayBarFeaturesJobService l2IntradayBarFeaturesService;
    private final com.zoutrankil.data.l2.application.L2EventResponseFeaturesJobService l2EventResponseFeaturesService;
    private final com.zoutrankil.data.l2.application.L2T0TrainingLabelsJobService l2T0TrainingLabelsService;

    public L2Commands(@org.springframework.lang.Nullable com.zoutrankil.data.l2.application.L2DatasetManifestJobService l2ManifestService,
            @org.springframework.lang.Nullable com.zoutrankil.data.l2.application.L2DailyFeaturesJobService l2DailyFeaturesService,
            @org.springframework.lang.Nullable com.zoutrankil.data.l2.application.L2IntradayBarFeaturesJobService l2IntradayBarFeaturesService,
            @org.springframework.lang.Nullable com.zoutrankil.data.l2.application.L2EventResponseFeaturesJobService l2EventResponseFeaturesService,
            @org.springframework.lang.Nullable com.zoutrankil.data.l2.application.L2T0TrainingLabelsJobService l2T0TrainingLabelsService) {
        this.l2ManifestService = l2ManifestService;
        this.l2DailyFeaturesService = l2DailyFeaturesService;
        this.l2IntradayBarFeaturesService = l2IntradayBarFeaturesService;
        this.l2EventResponseFeaturesService = l2EventResponseFeaturesService;
        this.l2T0TrainingLabelsService = l2T0TrainingLabelsService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "create-l2-manifest-test-target" -> {
                if (l2ManifestService == null || !options.keySet().equals(java.util.Set.of("--table")))
                    throw new IllegalArgumentException("Exact isolated D085 --table required");
                l2ManifestService.createIsolatedTarget(options.get("--table"));
                CliOutput.printJson(Map.of("status", "READY",
                        "table", options.get("--table"), "executed", true, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "plan-l2-manifest-job", "run-l2-manifest-job" -> {
                if (l2ManifestService == null
                        || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--symbols", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit D085 --from, --to and --logical-date required; "
                            + "optional --mode, --symbols, and run-only --resume-from");
                boolean planOnly = command.equals("plan-l2-manifest-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a D085 run");
                var from = java.time.LocalDate.parse(options.get("--from"));
                var to = java.time.LocalDate.parse(options.get("--to"));
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode
                                .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)) : null;
                var symbols = options.containsKey("--symbols")
                        ? java.util.Arrays.asList(options.get("--symbols").split(",", -1)) : java.util.List.<String>of();
                var plan = l2ManifestService.plan(from, to, logicalDate, mode, symbols);
                if (planOnly) {
                    var output = new java.util.LinkedHashMap<String,Object>();
                    output.put("status", "PLANNED"); output.put("executed", false); output.put("dataVerified", false);
                    output.put("targetId", plan.targetId()); output.put("bootstrapFrom", plan.bootstrapFrom().toString());
                    output.put("verifiedThrough", plan.verifiedThrough() == null ? null : plan.verifiedThrough().toString());
                    output.put("targetDatesChecked", plan.targetDatesChecked());
                    output.put("source", Map.of("rows", plan.source().sourceRows(), "selectedRows", plan.source().selectedRows(),
                            "files", plan.source().files(), "pages", plan.source().pages(),
                            "bytes", plan.source().sourceBytes(), "sourceFingerprint", plan.source().sourceFingerprint(),
                            "schemaFingerprint", plan.source().schemaFingerprint(), "dates", plan.source().dates()));
                    output.put("request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity
                            .snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION));
                    CliOutput.printJson(output, CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? l2ManifestService.resume(plan, options.get("--resume-from"))
                            : l2ManifestService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("D085 sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "l2-manifest-job-status" -> {
                if (l2ManifestService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D085 --run required");
                CliOutput.printJson(l2ManifestService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-l2-manifest-run" -> {
                if (l2ManifestService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D085 --run required");
                boolean accepted = l2ManifestService.cancel(options.get("--run"));
                CliOutput.printJson(Map.of("status", accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL",
                        "runId", options.get("--run"), "executed", false, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "create-l2-daily-features-test-target" -> {
                if (l2DailyFeaturesService == null || !options.keySet().equals(java.util.Set.of("--table")))
                    throw new IllegalArgumentException("Exact isolated D086 --table required");
                l2DailyFeaturesService.createIsolatedTarget(options.get("--table"));
                CliOutput.printJson(Map.of("status", "READY",
                        "table", options.get("--table"), "executed", true, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "plan-l2-daily-features-job", "run-l2-daily-features-job" -> {
                if (l2DailyFeaturesService == null
                        || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--symbols", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit D086 --from, --to and --logical-date required; "
                            + "optional --mode, --symbols, and run-only --resume-from");
                boolean planOnly = command.equals("plan-l2-daily-features-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a D086 run");
                var from = java.time.LocalDate.parse(options.get("--from"));
                var to = java.time.LocalDate.parse(options.get("--to"));
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode
                                .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)) : null;
                var symbols = options.containsKey("--symbols")
                        ? java.util.Arrays.asList(options.get("--symbols").split(",", -1)) : java.util.List.<String>of();
                var plan = l2DailyFeaturesService.plan(from, to, logicalDate, mode, symbols);
                if (planOnly) {
                    var output = new java.util.LinkedHashMap<String,Object>();
                    output.put("status", "PLANNED"); output.put("executed", false); output.put("dataVerified", false);
                    output.put("targetId", plan.targetId()); output.put("bootstrapFrom", plan.bootstrapFrom().toString());
                    output.put("verifiedThrough", plan.verifiedThrough() == null ? null : plan.verifiedThrough().toString());
                    output.put("targetDatesChecked", plan.targetDatesChecked());
                    output.put("source", Map.of("rows", plan.source().sourceRows(), "selectedRows", plan.source().selectedRows(),
                            "files", plan.source().files(), "pages", plan.source().pages(),
                            "bytes", plan.source().sourceBytes(), "sourceFingerprint", plan.source().sourceFingerprint(),
                            "schemaFingerprint", plan.source().schemaFingerprint(), "dates", plan.source().dates(),
                            "completeForSelectedSymbols", plan.source().completeForSelectedSymbols()));
                    output.put("request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity
                            .snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION));
                    CliOutput.printJson(output, CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? l2DailyFeaturesService.resume(plan, options.get("--resume-from"))
                            : l2DailyFeaturesService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("D086 sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "l2-daily-features-job-status" -> {
                if (l2DailyFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D086 --run required");
                CliOutput.printJson(l2DailyFeaturesService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-l2-daily-features-run" -> {
                if (l2DailyFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D086 --run required");
                boolean accepted = l2DailyFeaturesService.cancel(options.get("--run"));
                CliOutput.printJson(Map.of("status", accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL",
                        "runId", options.get("--run"), "executed", false, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "create-l2-intraday-bar-features-test-target" -> {
                if (l2IntradayBarFeaturesService == null || !options.keySet().equals(java.util.Set.of("--table")))
                    throw new IllegalArgumentException("Exact isolated D087 --table required");
                l2IntradayBarFeaturesService.createIsolatedTarget(options.get("--table"));
                CliOutput.printJson(Map.of("status", "READY",
                        "table", options.get("--table"), "executed", true, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "plan-l2-intraday-bar-features-job", "run-l2-intraday-bar-features-job" -> {
                if (l2IntradayBarFeaturesService == null
                        || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date", "--symbols"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--symbols", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit D087 --from, --to, --logical-date and --symbols required; "
                            + "optional --mode and run-only --resume-from");
                boolean planOnly = command.equals("plan-l2-intraday-bar-features-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a D087 run");
                var from = java.time.LocalDate.parse(options.get("--from"));
                var to = java.time.LocalDate.parse(options.get("--to"));
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode
                                .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)) : null;
                var symbols = java.util.Arrays.asList(options.get("--symbols").split(",", -1));
                var plan = l2IntradayBarFeaturesService.plan(from, to, logicalDate, mode, symbols);
                if (planOnly) {
                    var output = new java.util.LinkedHashMap<String,Object>();
                    output.put("status", "PLANNED"); output.put("executed", false); output.put("dataVerified", false);
                    output.put("targetId", plan.targetId()); output.put("bootstrapFrom", plan.bootstrapFrom().toString());
                    output.put("verifiedThrough", plan.verifiedThrough() == null ? null : plan.verifiedThrough().toString());
                    output.put("targetRowsChecked", plan.targetDatesChecked());
                    output.put("source", Map.of("rows", plan.source().sourceRows(), "selectedRows", plan.source().selectedRows(),
                            "files", plan.source().files(), "pages", plan.source().pages(),
                            "bytes", plan.source().sourceBytes(), "sourceFingerprint", plan.source().sourceFingerprint(),
                            "schemaFingerprint", plan.source().schemaFingerprint(), "dates", plan.source().dates(),
                            "completeForSelectedSymbols", plan.source().completeForSelectedSymbols()));
                    output.put("request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity
                            .snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION));
                    CliOutput.printJson(output, CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? l2IntradayBarFeaturesService.resume(plan, options.get("--resume-from"))
                            : l2IntradayBarFeaturesService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("D087 sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "l2-intraday-bar-features-job-status" -> {
                if (l2IntradayBarFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D087 --run required");
                CliOutput.printJson(l2IntradayBarFeaturesService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-l2-intraday-bar-features-run" -> {
                if (l2IntradayBarFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D087 --run required");
                boolean accepted = l2IntradayBarFeaturesService.cancel(options.get("--run"));
                CliOutput.printJson(Map.of("status", accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL",
                        "runId", options.get("--run"), "executed", false, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "create-l2-event-response-features-test-target" -> {
                if (l2EventResponseFeaturesService == null || !options.keySet().equals(java.util.Set.of("--table")))
                    throw new IllegalArgumentException("Exact isolated D088 --table required");
                l2EventResponseFeaturesService.createIsolatedTarget(options.get("--table"));
                CliOutput.printJson(Map.of("status", "READY",
                        "table", options.get("--table"), "executed", true, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "plan-l2-event-response-features-job", "run-l2-event-response-features-job" -> {
                if (l2EventResponseFeaturesService == null
                        || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date", "--symbols"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--symbols", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit D088 --from, --to, --logical-date and --symbols required; "
                            + "optional --mode and run-only --resume-from");
                boolean planOnly = command.equals("plan-l2-event-response-features-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a D088 run");
                var from = java.time.LocalDate.parse(options.get("--from"));
                var to = java.time.LocalDate.parse(options.get("--to"));
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode
                                .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)) : null;
                var symbols = java.util.Arrays.asList(options.get("--symbols").split(",", -1));
                var plan = l2EventResponseFeaturesService.plan(from, to, logicalDate, mode, symbols);
                if (planOnly) {
                    var output = new java.util.LinkedHashMap<String,Object>();
                    output.put("status", "PLANNED"); output.put("executed", false); output.put("dataVerified", false);
                    output.put("targetId", plan.targetId()); output.put("bootstrapFrom", plan.bootstrapFrom().toString());
                    output.put("verifiedThrough", plan.verifiedThrough() == null ? null : plan.verifiedThrough().toString());
                    output.put("targetRowsChecked", plan.targetDatesChecked());
                    output.put("source", Map.of("rows", plan.source().sourceRows(), "selectedRows", plan.source().selectedRows(),
                            "files", plan.source().files(), "pages", plan.source().pages(),
                            "bytes", plan.source().sourceBytes(), "sourceFingerprint", plan.source().sourceFingerprint(),
                            "schemaFingerprint", plan.source().schemaFingerprint(), "dates", plan.source().dates(),
                            "completeForSelectedSymbols", plan.source().completeForSelectedSymbols()));
                    output.put("request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity
                            .snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION));
                    CliOutput.printJson(output, CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? l2EventResponseFeaturesService.resume(plan, options.get("--resume-from"))
                            : l2EventResponseFeaturesService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("D088 sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "l2-event-response-features-job-status" -> {
                if (l2EventResponseFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D088 --run required");
                CliOutput.printJson(l2EventResponseFeaturesService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-l2-event-response-features-run" -> {
                if (l2EventResponseFeaturesService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D088 --run required");
                boolean accepted = l2EventResponseFeaturesService.cancel(options.get("--run"));
                CliOutput.printJson(Map.of("status", accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL",
                        "runId", options.get("--run"), "executed", false, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "create-l2-t0-training-labels-test-target" -> {
                if (l2T0TrainingLabelsService == null || !options.keySet().equals(java.util.Set.of("--table")))
                    throw new IllegalArgumentException("Exact isolated D089 --table required");
                l2T0TrainingLabelsService.createIsolatedTarget(options.get("--table"));
                CliOutput.printJson(Map.of("status", "READY",
                        "table", options.get("--table"), "executed", true, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            case "plan-l2-t0-training-labels-job", "run-l2-t0-training-labels-job" -> {
                if (l2T0TrainingLabelsService == null
                        || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date", "--symbols"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--symbols", "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit D089 --from, --to, --logical-date and --symbols required; "
                            + "optional --mode and run-only --resume-from");
                boolean planOnly = command.equals("plan-l2-t0-training-labels-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a D089 run");
                var from = java.time.LocalDate.parse(options.get("--from"));
                var to = java.time.LocalDate.parse(options.get("--to"));
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode
                                .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)) : null;
                var symbols = java.util.Arrays.asList(options.get("--symbols").split(",", -1));
                var plan = l2T0TrainingLabelsService.plan(from, to, logicalDate, mode, symbols);
                if (planOnly) {
                    var output = new java.util.LinkedHashMap<String,Object>();
                    output.put("status", "PLANNED"); output.put("executed", false); output.put("dataVerified", false);
                    output.put("targetId", plan.targetId()); output.put("bootstrapFrom", plan.bootstrapFrom().toString());
                    output.put("verifiedThrough", plan.verifiedThrough() == null ? null : plan.verifiedThrough().toString());
                    output.put("targetRowsChecked", plan.targetDatesChecked());
                    output.put("source", Map.of("rows", plan.source().sourceRows(), "selectedRows", plan.source().selectedRows(),
                            "files", plan.source().files(), "pages", plan.source().pages(),
                            "bytes", plan.source().sourceBytes(), "sourceFingerprint", plan.source().sourceFingerprint(),
                            "schemaFingerprint", plan.source().schemaFingerprint(), "dates", plan.source().dates(),
                            "completeForSelectedSymbols", plan.source().completeForSelectedSymbols(),
                            "outcomeCoverageByHorizon", plan.source().outcomeCoverageByHorizon()));
                    output.put("request", CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity
                            .snapshotJson(plan.request()), CliOutput.Profile.JOB_DEFINITION));
                    CliOutput.printJson(output, CliOutput.Profile.JOB_DEFINITION, true);
                } else {
                    var result = options.containsKey("--resume-from")
                            ? l2T0TrainingLabelsService.resume(plan, options.get("--resume-from"))
                            : l2T0TrainingLabelsService.run(plan);
                    CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
                    if (result.errorCode() != null || result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("D089 sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "l2-t0-training-labels-job-status" -> {
                if (l2T0TrainingLabelsService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D089 --run required");
                CliOutput.printJson(l2T0TrainingLabelsService.status(options.get("--run")), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "cancel-l2-t0-training-labels-run" -> {
                if (l2T0TrainingLabelsService == null || !options.keySet().equals(java.util.Set.of("--run")))
                    throw new IllegalArgumentException("Exact D089 --run required");
                boolean accepted = l2T0TrainingLabelsService.cancel(options.get("--run"));
                CliOutput.printJson(Map.of("status", accepted ? "CANCEL_REQUESTED" : "ALREADY_TERMINAL",
                        "runId", options.get("--run"), "executed", false, "dataVerified", false), CliOutput.Profile.PLAIN, false);
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
