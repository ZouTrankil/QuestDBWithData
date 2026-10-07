package com.zoutrankil.data.cli;

import com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService;
import com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService;
import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import com.zoutrankil.data.derived.application.MarketBreadthDailyV1JobService;
import com.zoutrankil.data.derived.application.RetailSentimentDailyV1JobService;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class MaterializationCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "plan-regime-features-monitor-daily-job", "run-regime-features-monitor-daily-job", "finish-regime-monitor-publication", "plan-market-sentiment-daily-job", "run-market-sentiment-daily-job", "finish-market-sentiment-publication",
            "install-macro-core-monthly-isolated", "plan-macro-core-monthly-job", "run-macro-core-monthly-job",
            "macro-core-monthly-job-status", "cancel-macro-core-monthly-run", "resume-macro-core-monthly-run",
            "reconcile-macro-core-monthly-run", "install-equity-style-monthly-isolated", "plan-equity-style-monthly-job",
            "run-equity-style-monthly-job", "equity-style-monthly-job-status", "cancel-equity-style-monthly-run",
            "resume-equity-style-monthly-run", "reconcile-equity-style-monthly-run", "plan-etf-market-overview-cache-job",
            "run-etf-market-overview-cache-job", "etf-market-overview-cache-job-status", "cancel-etf-market-overview-cache-run",
            "resume-etf-market-overview-cache-run", "reconcile-etf-market-overview-cache-run", "install-retail-sentiment-daily-isolated",
            "repair-retail-sentiment-daily-isolated", "plan-retail-sentiment-daily-job", "run-retail-sentiment-daily-job",
            "retail-sentiment-daily-job-status", "cancel-retail-sentiment-daily-run", "resume-retail-sentiment-daily-run",
            "reconcile-retail-sentiment-daily-run", "install-market-breadth-daily-isolated", "repair-market-breadth-daily-isolated",
            "plan-market-breadth-daily-job", "run-market-breadth-daily-job", "market-breadth-daily-job-status",
            "cancel-market-breadth-daily-run", "resume-market-breadth-daily-run", "reconcile-market-breadth-daily-run");
    private final com.zoutrankil.data.derived.application.MarketBreadthDailyV1JobService marketBreadthDailyService;
    private final com.zoutrankil.data.derived.application.RetailSentimentDailyV1JobService retailSentimentDailyService;
    private final com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService etfMarketOverviewDailyCacheService;
    private final com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService equityStyleMonthlyService;
    private final com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService macroCoreMonthlyService;

    private final com.zoutrankil.data.service.MarketSentimentDailyJobService marketSentimentService;
    private final com.zoutrankil.data.service.RegimeFeaturesMonitorDailyJobService regimeMonitorService;

    @org.springframework.beans.factory.annotation.Autowired
    public MaterializationCommands(@org.springframework.lang.Nullable com.zoutrankil.data.derived.application.MarketBreadthDailyV1JobService marketBreadthDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.RetailSentimentDailyV1JobService retailSentimentDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService etfMarketOverviewDailyCacheService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService equityStyleMonthlyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService macroCoreMonthlyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.MarketSentimentDailyJobService marketSentimentService,
            @org.springframework.lang.Nullable com.zoutrankil.data.service.RegimeFeaturesMonitorDailyJobService regimeMonitorService) {
        this.marketBreadthDailyService = marketBreadthDailyService;
        this.retailSentimentDailyService = retailSentimentDailyService;
        this.etfMarketOverviewDailyCacheService = etfMarketOverviewDailyCacheService;
        this.equityStyleMonthlyService = equityStyleMonthlyService;
        this.macroCoreMonthlyService = macroCoreMonthlyService;
        this.marketSentimentService = marketSentimentService;
        this.regimeMonitorService = regimeMonitorService;
    }

    public MaterializationCommands(@org.springframework.lang.Nullable com.zoutrankil.data.derived.application.MarketBreadthDailyV1JobService marketBreadthDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.RetailSentimentDailyV1JobService retailSentimentDailyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.EtfMarketOverviewDailyCacheJobService etfMarketOverviewDailyCacheService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.EquityStyleMonthlyJobService equityStyleMonthlyService,
            @org.springframework.lang.Nullable com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService macroCoreMonthlyService) {
        this(marketBreadthDailyService, retailSentimentDailyService, etfMarketOverviewDailyCacheService, equityStyleMonthlyService, macroCoreMonthlyService, null, null);
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "plan-regime-features-monitor-daily-job", "run-regime-features-monitor-daily-job" -> {
                if (regimeMonitorService == null || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --from, --to and --logical-date required; optional --mode MATERIALIZE");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : com.zoutrankil.data.domain.SyncJobDefinition.Mode.MATERIALIZE;
                var plan = regimeMonitorService.plan(java.time.LocalDate.parse(options.get("--from")),
                        java.time.LocalDate.parse(options.get("--to")), java.time.LocalDate.parse(options.get("--logical-date")), mode);
                if (command.equals("plan-regime-features-monitor-daily-job"))
                    CliOutput.printJson(java.util.Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false, "plan", plan), CliOutput.Profile.JOB_DEFINITION, true);
                else {
                    var materialization = regimeMonitorService.run(plan);
                    CliOutput.printJson(materialization, CliOutput.Profile.JOB_DEFINITION, true);
                    var result = materialization.result();
                    if (result.errorCode() != null || !java.util.Set.of(
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(result.state()))
                        throw new IncompleteCommandException("regime_features_monitor_daily incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "finish-regime-monitor-publication" -> {
                if (regimeMonitorService == null || !options.keySet().equals(java.util.Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Exact --run and explicit --writer-stopped true required");
                var snapshot = regimeMonitorService.finishInterrupted(options.get("--run"), true);
                CliOutput.printJson(java.util.Map.of("status", "VERIFIED", "dataVerified", true,
                                "targetId", snapshot.targetId(), "rows", snapshot.rows().size(), "fingerprint", snapshot.fingerprint()), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "plan-market-sentiment-daily-job", "run-market-sentiment-daily-job" -> {
                if (marketSentimentService == null || !options.keySet().containsAll(java.util.Set.of("--from", "--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --from, --to and --logical-date required; optional --mode MATERIALIZE");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.data.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : com.zoutrankil.data.domain.SyncJobDefinition.Mode.MATERIALIZE;
                var plan = marketSentimentService.plan(java.time.LocalDate.parse(options.get("--from")),
                        java.time.LocalDate.parse(options.get("--to")), java.time.LocalDate.parse(options.get("--logical-date")), mode);
                if (command.equals("plan-market-sentiment-daily-job"))
                    CliOutput.printJson(java.util.Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false, "plan", plan), CliOutput.Profile.JOB_DEFINITION, true);
                else {
                    var materialization = marketSentimentService.run(plan);
                    CliOutput.printJson(materialization, CliOutput.Profile.JOB_DEFINITION, true);
                    var result = materialization.result();
                    if (result.errorCode() != null || !java.util.Set.of(
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY).contains(result.state()))
                        throw new IncompleteCommandException("market_sentiment_daily incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "finish-market-sentiment-publication" -> {
                if (marketSentimentService == null || !options.keySet().equals(java.util.Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Exact --run and explicit --writer-stopped true required");
                var snapshot = marketSentimentService.finishInterrupted(options.get("--run"), true);
                CliOutput.printJson(java.util.Map.of("status", "VERIFIED", "dataVerified", true,
                                "targetId", snapshot.targetId(), "rows", snapshot.rows().size(), "fingerprint", snapshot.fingerprint()), CliOutput.Profile.JOB_DEFINITION, true);
            }
            case "install-macro-core-monthly-isolated", "plan-macro-core-monthly-job", "run-macro-core-monthly-job",
                 "macro-core-monthly-job-status", "cancel-macro-core-monthly-run", "resume-macro-core-monthly-run",
                 "reconcile-macro-core-monthly-run" -> MacroCoreMonthlyCommands.execute(command,options,macroCoreMonthlyService);
            case "install-equity-style-monthly-isolated", "plan-equity-style-monthly-job", "run-equity-style-monthly-job",
                 "equity-style-monthly-job-status", "cancel-equity-style-monthly-run", "resume-equity-style-monthly-run",
                 "reconcile-equity-style-monthly-run" -> EquityStyleMonthlyCommands.execute(command,options,equityStyleMonthlyService);
            case "plan-etf-market-overview-cache-job", "run-etf-market-overview-cache-job",
                 "etf-market-overview-cache-job-status", "cancel-etf-market-overview-cache-run",
                 "resume-etf-market-overview-cache-run", "reconcile-etf-market-overview-cache-run" ->
                    EtfMarketOverviewDailyCacheCommands.execute(command, options, etfMarketOverviewDailyCacheService);
            case "install-retail-sentiment-daily-isolated", "repair-retail-sentiment-daily-isolated",
                 "plan-retail-sentiment-daily-job", "run-retail-sentiment-daily-job",
                 "retail-sentiment-daily-job-status", "cancel-retail-sentiment-daily-run",
                 "resume-retail-sentiment-daily-run", "reconcile-retail-sentiment-daily-run" ->
                    RetailSentimentDailyV1Commands.execute(command, options, retailSentimentDailyService);
            case "install-market-breadth-daily-isolated", "repair-market-breadth-daily-isolated",
                 "plan-market-breadth-daily-job", "run-market-breadth-daily-job",
                 "market-breadth-daily-job-status", "cancel-market-breadth-daily-run",
                 "resume-market-breadth-daily-run", "reconcile-market-breadth-daily-run" ->
                    MarketBreadthDailyV1Commands.execute(command, options, marketBreadthDailyService);
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
