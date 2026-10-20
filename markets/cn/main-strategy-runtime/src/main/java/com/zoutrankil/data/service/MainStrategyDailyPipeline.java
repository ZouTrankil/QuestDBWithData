package com.zoutrankil.data.service;

import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.domain.BusinessState;
import com.zoutrankil.batch.domain.CompletionEvidence;
import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.job.MainStrategyDailyWork;
import com.zoutrankil.batch.job.StageExecutor;
import com.zoutrankil.data.derived.application.MarketSentimentDailyJobService;
import com.zoutrankil.data.derived.application.RegimeFeaturesMonitorDailyJobService;
import com.zoutrankil.data.derived.storage.MarketSentimentDailyWritePort;
import com.zoutrankil.data.derived.storage.RegimeFeaturesMonitorDailyWritePort;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.etf.application.EtfAdjJobService;
import com.zoutrankil.data.etf.application.EtfDailyJobService;
import com.zoutrankil.data.etf.application.EtfFactorJobService;
import com.zoutrankil.data.etf.application.EtfPortfolioJobService;
import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import com.zoutrankil.data.flow.application.MoneyflowJobService;
import com.zoutrankil.data.l2.application.FormalL2DailyFeaturesIngestion;
import com.zoutrankil.data.margin.application.MarginDetailJobService;
import com.zoutrankil.data.repository.BacktestDailyMaterializationReadGuard;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import com.zoutrankil.data.stock.application.DailyJobService;
import com.zoutrankil.data.stock.application.StockFactorJobService;
import com.zoutrankil.data.stock.application.StockLimitJobService;
import com.zoutrankil.data.stock.application.StockStDailyJobService;
import com.zoutrankil.data.stock.application.StockSuspendJobService;
import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import com.zoutrankil.data.strategy.publication.ScoreArtifactPublisher.Publication;
import com.zoutrankil.data.strategy.store.StoredOfficialPlan;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/** 复用已有 Java 数据执行组件的持久化主策略编排；不另建来源客户端或写入器。 */
@Service
@Lazy
public final class MainStrategyDailyPipeline implements MainStrategyDailyWork {
    private static final long MAX_WINDOW_ROWS = 1_000_000;
    // 这里只列每个开市日都应有数据的来源；停牌、ST、持仓披露不能按日历猜缺口。
    private static final Map<String, String> DAILY_SOURCES =
            Map.of(
                    "daily",
                    "trade_date",
                    "daily_basic",
                    "trade_date",
                    "stk_factor",
                    "trade_date",
                    "stk_limit",
                    "trade_date",
                    "moneyflow",
                    "trade_date",
                    "moneyflow_hsgt",
                    "trade_date",
                    "etf_daily",
                    "timestamp",
                    "etf_adj",
                    "timestamp",
                    "etf_factor",
                    "trade_date");
    private static final List<String> REGIME_REQUIRED =
            List.of(
                    "all_a_ret_mtd",
                    "small_large_ret_mtd",
                    "growth_value_ret_mtd",
                    "avg_up_down_ratio_5d",
                    "avg_turnover_rate_5d",
                    "northbound_net_buy_mtd",
                    "all_a_pe_ttm_percentile_latest",
                    "all_a_pb_percentile_latest",
                    "erp_latest");
    private final JdbcTemplate jdbc;
    private final SyncJobRegistry registry;
    private final Path ledger, root;
    private final ObjectMapper json = JobDefinitionJson.mapper();
    private final List<Owner> owners;
    private final MarketSentimentDailyJobService market;
    private final RegimeFeaturesMonitorDailyJobService regime;
    private final List<String> targetEtfs;
    private final FormalL2DailyFeaturesIngestion l2;
    private final MainStrategyDataService strategyData;
    private volatile String activeStage = "idle";
    private volatile LocalDate activeDate;
    private volatile String lastOutcome = "never-run";

    @FunctionalInterface
    private interface Call<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface Planner {
        Planned get(LocalDate from, LocalDate to, LocalDate logical) throws Exception;
    }

    private record Planned(
            SyncJobDefinition.FrozenRequest request, Call<SyncJobRunner.Result> run) {}

    private record Owner(
            String table,
            String date,
            List<String> keys,
            boolean optional,
            boolean sparse,
            Supplier<String> binding,
            Planner planner) {}

    public record Window(
            String table,
            String date,
            List<String> keys,
            LocalDate from,
            LocalDate to,
            long rows,
            String sha256,
            String schema) {}

    public record PipelinePlan(
            String instance,
            String input,
            LocalDate from,
            LocalDate to,
            LocalDate sourceLogicalDate,
            List<LocalDate> tradeDates) {}

    public record SourceReceipt(
            String instance,
            String dataset,
            String definitionHash,
            String runId,
            String frozenJson,
            SyncRunState state,
            LocalDate from,
            LocalDate to,
            Window window,
            String limitation,
            Instant finished) {}

    private record NativePlan(SyncJobDefinition.FrozenRequest frozen, Call<NativeOutcome> run) {}

    private record NativeOutcome(
            SyncJobRunner.Result result,
            String sourceFingerprint,
            String fullTargetFingerprint,
            String windowFingerprint,
            String evidence,
            boolean bootstrap) {}

    public record NativeReceipt(
            String instance,
            String stage,
            String definitionHash,
            LocalDate from,
            LocalDate to,
            String frozenJson,
            SyncJobRunner.Result result,
            String sourceFingerprint,
            String fullTargetFingerprint,
            String windowFingerprint,
            String evidence,
            String evidenceSha256,
            String sourceEvidence,
            String sourceEvidenceSha256,
            boolean bootstrap,
            Map<String, Map<String, Object>> sourceClocks,
            Instant completed) {}

    /** 仅追加恢复关联；原始未决回执的字节保持不变。 */
    public record NativeRecovery(
            String instance,
            String input,
            String stage,
            String runId,
            String originalReceipt,
            String originalSha256,
            String recoveredReceipt,
            String recoveredSha256,
            String frozenSha256,
            String sourceSha256,
            String publicationSha256,
            Instant reconciled) {}

    private static final class Gate extends Exception {
        final BusinessState state;

        Gate(BusinessState state, String reason) {
            super(reason);
            this.state = state;
        }
    }

    public MainStrategyDailyPipeline(
            JdbcTemplate jdbc,
            @Lazy SyncJobRegistry registry,
            DailyJobService daily,
            DailyBasicJobService basic,
            StockFactorJobService factor,
            StockLimitJobService limit,
            StockSuspendJobService suspend,
            StockStDailyJobService st,
            MoneyflowJobService flow,
            MoneyflowHsgtJobService hsgt,
            MarginDetailJobService margin,
            EtfDailyJobService etf,
            EtfAdjJobService adj,
            EtfFactorJobService etfFactor,
            EtfPortfolioJobService portfolio,
            CnBondYieldCurveJobService bonds,
            MarketSentimentDailyJobService market,
            RegimeFeaturesMonitorDailyJobService regime,
            FormalL2DailyFeaturesIngestion l2,
            MainStrategyDataService strategyData,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            @Value("${app.sync.batch.artifact-root:var/main-strategy-batch}") String root,
            @Value("${app.sync.batch.target-etfs:}") String targetEtfs) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(120);
        this.jdbc.setFetchSize(1024);
        this.registry = registry;
        this.ledger = Path.of(ledger).toAbsolutePath().normalize();
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.market = market;
        this.regime = regime;
        this.l2 = l2;
        this.strategyData = strategyData;
        this.targetEtfs =
                Arrays.stream(targetEtfs.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .sorted()
                        .toList();
        if (this.targetEtfs.size() > 100
                || this.targetEtfs.stream().anyMatch(s -> !s.matches("[0-9]{6}\\.(SH|SZ|BJ)")))
            throw new IllegalArgumentException("Bounded canonical target ETF codes required");
        owners =
                List.of(
                        new Owner(
                                "daily",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                daily::tableName,
                                (a, b, l) -> {
                                    var p = daily.plan(a, b, l, Mode.BACKFILL);
                                    return new Planned(p.request(), () -> daily.run(p));
                                }),
                        new Owner(
                                "daily_basic",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                basic::tableName,
                                (a, b, l) -> {
                                    var p = basic.plan(a, b, l, Mode.BACKFILL);
                                    return new Planned(p.request(), () -> basic.run(p));
                                }),
                        new Owner(
                                "stk_factor",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                factor::tableName,
                                (a, b, l) -> {
                                    var p = factor.plan(Mode.BACKFILL, a, b, l, null);
                                    return new Planned(p, () -> factor.run(p));
                                }),
                        new Owner(
                                "stk_suspend",
                                "timestamp",
                                List.of("timestamp", "ts_code"),
                                false,
                                true,
                                suspend::tableName,
                                (a, b, l) -> {
                                    var p = suspend.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p, () -> suspend.run(p));
                                }),
                        new Owner(
                                "stk_st_daily",
                                "timestamp",
                                List.of("timestamp", "ts_code"),
                                false,
                                true,
                                st::tableName,
                                (a, b, l) -> {
                                    var p = st.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> st.run(p));
                                }),
                        // 回测 MV 监听 stk_limit；价格、停牌和 ST 来源先准备，再提交涨跌停价以触发关联刷新。
                        new Owner(
                                "stk_limit",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                limit::tableName,
                                (a, b, l) -> {
                                    var p = limit.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> limit.run(p));
                                }),
                        new Owner(
                                "moneyflow",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                flow::tableName,
                                (a, b, l) -> {
                                    var p = flow.planDetailed(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> flow.run(p));
                                }),
                        new Owner(
                                "moneyflow_hsgt",
                                "trade_date",
                                List.of("trade_date"),
                                false,
                                false,
                                hsgt::tableName,
                                (a, b, l) -> {
                                    var p = hsgt.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> hsgt.run(p));
                                }),
                        new Owner(
                                "margin_detail",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                true,
                                false,
                                margin::tableName,
                                (a, b, l) -> {
                                    var p = margin.planDetailed(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> margin.run(p));
                                }),
                        new Owner(
                                "etf_daily",
                                "timestamp",
                                List.of("timestamp", "ts_code"),
                                false,
                                false,
                                etf::tableName,
                                (a, b, l) -> {
                                    var p = etf.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> etf.run(p));
                                }),
                        new Owner(
                                "etf_adj",
                                "timestamp",
                                List.of("timestamp", "ts_code"),
                                false,
                                false,
                                adj::tableName,
                                (a, b, l) -> {
                                    var p = adj.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> adj.run(p));
                                }),
                        new Owner(
                                "etf_factor",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                false,
                                false,
                                etfFactor::tableName,
                                (a, b, l) -> {
                                    var p = etfFactor.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> etfFactor.run(p));
                                }),
                        new Owner(
                                "etf_portfolio",
                                "ann_date",
                                List.of("ann_date", "ts_code", "end_date", "symbol"),
                                false,
                                true,
                                portfolio::tableName,
                                (a, b, l) -> {
                                    var p = portfolio.plan(Mode.BACKFILL, a, b, l);
                                    return new Planned(p.request(), () -> portfolio.run(p));
                                }),
                        new Owner(
                                "cn_bond_yield_curve",
                                "trade_date",
                                List.of("trade_date", "curve_code", "tenor"),
                                false,
                                false,
                                bonds::tableName,
                                (a, b, l) -> {
                                    var p = bonds.plan(a, b, l);
                                    return new Planned(p.request(), () -> bonds.run(p));
                                }));
    }

    /** 按上海时间、收盘截止条件和真实 SSE 日历，选择最近可处理的已完成交易日。 */
    @Override
    public LocalDate resolveTarget(Clock clock, LocalTime cutoff) throws Exception {
        var marketTime = CnMarketTime.using(clock, MarketId.CN.defaultZone());
        var now = marketTime.inBusinessZone(marketTime.now());
        var ceiling = DailySyncEndDate.resolve(null, now);
        if (now.toLocalTime().isBefore(cutoff) && ceiling.equals(now.toLocalDate()))
            ceiling = ceiling.minusDays(1);
        var dates = calendar(ceiling.minusDays(31), ceiling);
        if (dates.isEmpty())
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "No open SSE session in verified 32-day calendar");
        return dates.getLast();
    }

    @Override
    public String inputFingerprint(LocalDate target) throws Exception {
        var bindings = new TreeMap<String, Object>();
        for (var o : owners) {
            var binding = new TreeMap<String, Object>();
            binding.put("table", o.binding().get());
            binding.put(
                    "definition",
                    json.readTree(SyncRequestIdentity.definitionJson(definition(o.table()))));
            bindings.put(o.table(), binding);
        }
        bindings.put(
                "market",
                json.readTree(
                        SyncRequestIdentity.definitionJson(definition("market_sentiment_daily"))));
        bindings.put(
                "regime",
                json.readTree(
                        SyncRequestIdentity.definitionJson(
                                definition("regime_features_monitor_daily"))));
        bindings.put("backtestMvSql", BacktestDailyMaterializedViewPort.CREATE_SQL);
        return RunRequest.hash(
                "java.main_strategy_daily.v3",
                target.toString(),
                json.writeValueAsString(bindings),
                "range=3;catchup<=31;sourceSlices<=3;SSE-calendar;cutoff>=20:30;optionalMarginWholeMarket;nativeL2;questdbBacktestMv;stkLimitLast;pairedJavaScores;canonicalOfficialPlan",
                ledger.toString(),
                targetEtfs.toString(),
                strategyData.definitionFingerprint());
    }

    private SyncJobDefinition definition(String dataset) {
        return registry.definitions().stream()
                .filter(j -> j.datasetId().equals(dataset))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Registered Java owner absent: " + dataset));
    }

    private String definitionHash(String dataset) {
        return RunRequest.hash(SyncRequestIdentity.definitionJson(definition(dataset)));
    }

    @Override
    public Map<String, Object> status() {
        var m = new LinkedHashMap<String, Object>();
        m.put("producer", "java.main_strategy_daily");
        m.put("stage", activeStage);
        m.put("target", activeDate);
        m.put("lastOutcome", lastOutcome);
        m.put("artifactRoot", root.toString());
        m.put("targetEtfs", targetEtfs);
        return m;
    }

    /**
     * 按固定依赖顺序执行来源、衍生指标、回测输入、L2、评分和正式方案验收。
     * 复用旧阶段前重新验证其证据；配置指纹变化或来源未就绪时，不继续推进下游。
     */
    @Override
    public void execute(RunRequest request, StageSink sink) throws Exception {
        if (!JOB.equals(request.job())
                || !DEFINITION_VERSION.equals(request.definitionVersion())
                || !request.rangeStart().equals(request.logicalDate())
                || !request.rangeEnd().equals(request.logicalDate()))
            throw new IllegalArgumentException(
                    "Main strategy accepts the current definition and one completed trading date");
        activeDate = request.logicalDate();
        var completed = new LinkedHashMap<String, CompletionEvidence>();
        for (String stage : STAGES) {
            activeStage = stage;
            CompletionEvidence evidence;
            try {
                if (!request.inputFingerprint().equals(inputFingerprint(request.logicalDate())))
                    throw new Gate(
                            BusinessState.BLOCKED,
                            "Frozen pipeline definitions/configuration changed; explicit revision required");
                var old = sink.existing(stage);
                if (old.isPresent()) {
                    validateStage(request, stage, old.get());
                    evidence = old.get();
                } else {
                    var plan = plan(request);
                    evidence =
                            switch (stage) {
                                case "Sources" -> sources(request, plan);
                                case "MarketSentimentDaily" -> market(request, plan);
                                case "RegimeFeaturesMonitorDaily" -> regime(request, plan);
                                case "BacktestDaily" -> backtest(request, plan);
                                case "L2DailyFeatures" -> l2(request, plan);
                                case "ScoreInputs" -> scores(request, plan);
                                case "OfficialPlanData" -> officialPlan(request, plan);
                                case "MainStrategyAcceptance" ->
                                        acceptance(
                                                request, plan, completed.get("OfficialPlanData"));
                                default -> throw new IllegalStateException("Unknown stage");
                            };
                }
                if (stage.equals("MainStrategyAcceptance"))
                    requireAcceptancePlanStage(
                            request, evidence, completed.get("OfficialPlanData"));
            } catch (Gate gate) {
                lastOutcome = gate.state.name() + ":" + gate.getMessage();
                sink.complete(stage, new StageExecutor.Result(gate.state, null, gate.getMessage()));
                return;
            } catch (Exception error) {
                lastOutcome = "IN_DOUBT:" + error.getClass().getSimpleName();
                sink.complete(
                        stage,
                        new StageExecutor.Result(
                                BusinessState.IN_DOUBT,
                                null,
                                "Owner outcome requires reconciliation: "
                                        + Objects.toString(
                                                error.getMessage(),
                                                error.getClass().getSimpleName())));
                return;
            }
            sink.complete(stage, new StageExecutor.Result(evidence.state(), evidence, null));
            completed.put(stage, evidence);
        }
        activeStage = "idle";
        lastOutcome = "VERIFIED";
    }

    @Override
    public void validateCompleted(RunRequest request, Map<String, CompletionEvidence> stages)
            throws Exception {
        if (!DEFINITION_VERSION.equals(request.definitionVersion()))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Old main-strategy definitions require an explicit revision");
        if (!inputFingerprint(request.logicalDate()).equals(request.inputFingerprint()))
            throw new Gate(BusinessState.BLOCKED, "Pipeline definition/configuration changed");
        for (String stage : STAGES) {
            var e = stages.get(stage);
            if (e == null)
                throw new Gate(
                        BusinessState.BLOCKED, "Completed stage certificate missing: " + stage);
            validateStage(request, stage, e);
        }
        requireAcceptancePlanStage(
                request, stages.get("MainStrategyAcceptance"), stages.get("OfficialPlanData"));
        verifyConsumer(request.logicalDate());
    }

    @Override
    public CompletionEvidence reconcilePublication(
            RunRequest request,
            String stage,
            String expectedRun,
            boolean writerStopped,
            Map<String, CompletionEvidence> upstream)
            throws Exception {
        if (!writerStopped)
            throw new IllegalArgumentException("Explicit stopped-writer proof required");
        String dataset = nativeDataset(stage);
        var range = plan(request);
        if (!inputFingerprint(request.logicalDate()).equals(request.inputFingerprint()))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Frozen pipeline configuration changed; revision required");
        int position = STAGES.indexOf(stage);
        for (String prior : STAGES.subList(0, position)) {
            var certificate = upstream.get(prior);
            if (certificate == null)
                throw new Gate(
                        BusinessState.BLOCKED, "Ready upstream certificate required: " + prior);
            validateStage(request, prior, certificate);
        }
        Path directory = instance(request).resolve("native").resolve(stage);
        var files = nativeFiles(directory);
        var originals = new ArrayList<Path>();
        for (Path file : files)
            if (file.getFileName().toString().startsWith("receipt-")) {
                var receipt = json.readValue(Files.readAllBytes(file), NativeReceipt.class);
                if (receipt.result().runId().equals(expectedRun)
                        && receipt.result().state() == SyncRunState.IN_DOUBT) originals.add(file);
            }
        if (originals.size() != 1)
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Exactly one original IN_DOUBT receipt for this run is required");
        Path originalFile = originals.getFirst();
        var old = json.readValue(Files.readAllBytes(originalFile), NativeReceipt.class);
        requireNativeBinding(request, range, stage, dataset, old);
        var authority = SyncRunLedger.openReadOnly(ledger);
        var run = authority.getRun(expectedRun);
        var state = authority.get(expectedRun).state();
        if (!run.frozenJson().equals(old.frozenJson())
                || !run.jobId().equals(definition(dataset).jobId())
                || run.jobVersion() != definition(dataset).version()
                || state != SyncRunState.IN_DOUBT && state != SyncRunState.VERIFIED)
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Original owner/frozen run is not eligible for reconciliation");
        requireNativeSource(old);
        requireNativeClocks(old);
        // 先前的 CLI 完成结果，仅在同一个不可变运行已经 VERIFIED 后才能被接受。
        if (state == SyncRunState.IN_DOUBT)
            switch (stage) {
                case "MarketSentimentDaily" -> market.finishInterrupted(expectedRun, true);
                case "RegimeFeaturesMonitorDaily" -> regime.finishInterrupted(expectedRun, true);
                default -> throw new IllegalArgumentException("Native stage required");
            }
        if (SyncRunLedger.openReadOnly(ledger).get(expectedRun).state() != SyncRunState.VERIFIED)
            throw new Gate(BusinessState.IN_DOUBT, "Original owner did not finish VERIFIED");
        Path source = Path.of(old.sourceEvidence()).toAbsolutePath().normalize(),
                publication = source.resolveSibling("publication.json");
        if (!Files.isRegularFile(publication))
            throw new Gate(BusinessState.IN_DOUBT, "Recovered owner publication evidence absent");
        var result =
                new SyncJobRunner.Result(
                        expectedRun,
                        SyncRunState.VERIFIED,
                        old.result().sourceRows(),
                        old.result().verifiedRows(),
                        null,
                        old.result().reusedRows());
        String full =
                stage.equals("MarketSentimentDaily")
                        ? new MarketSentimentDailyWritePort(jdbc, dataset)
                                .formalSnapshot()
                                .fingerprint()
                        : stage.equals("RegimeFeaturesMonitorDaily")
                                ? new RegimeFeaturesMonitorDailyWritePort(jdbc, dataset)
                                        .formalSnapshot()
                                        .fingerprint()
                                : null;
        String window = old.windowFingerprint();
        var recovered =
                new NativeReceipt(
                        old.instance(),
                        old.stage(),
                        old.definitionHash(),
                        old.from(),
                        old.to(),
                        old.frozenJson(),
                        result,
                        old.sourceFingerprint(),
                        full,
                        window,
                        publication.toString(),
                        fileHash(publication),
                        source.toString(),
                        fileHash(source),
                        old.bootstrap(),
                        old.sourceClocks(),
                        Instant.now());
        validateNativeReceipt(request, range, stage, dataset, recovered);
        var existing = new ArrayList<Path>();
        for (Path file : files)
            if (file.getFileName().toString().startsWith("receipt-")) {
                var candidate = json.readValue(Files.readAllBytes(file), NativeReceipt.class);
                if (candidate.result().runId().equals(expectedRun)
                        && candidate.result().state() == SyncRunState.VERIFIED) {
                    validateNativeReceipt(request, range, stage, dataset, candidate);
                    requireRecoveryPair(old, candidate);
                    existing.add(file);
                }
            }
        if (existing.size() > 1)
            throw new Gate(BusinessState.BLOCKED, "Ambiguous recovered original owner receipts");
        Path recoveredFile;
        if (existing.isEmpty()) {
            recoveredFile = directory.resolve("receipt-recovered-" + UUID.randomUUID() + ".json");
            write(recoveredFile, recovered);
        } else {
            recoveredFile = existing.getFirst();
            recovered = json.readValue(Files.readAllBytes(recoveredFile), NativeReceipt.class);
        }
        Path link = recoveryPath(originalFile);
        var recovery =
                new NativeRecovery(
                        request.instanceId(),
                        request.inputFingerprint(),
                        stage,
                        expectedRun,
                        originalFile.toString(),
                        fileHash(originalFile),
                        recoveredFile.toString(),
                        fileHash(recoveredFile),
                        RunRequest.hash(old.frozenJson()),
                        recovered.sourceEvidenceSha256(),
                        recovered.evidenceSha256(),
                        Instant.now());
        if (!Files.isRegularFile(link)) write(link, recovery);
        validateRecovery(request, range, stage, dataset, originalFile, old);
        activeDate = request.logicalDate();
        activeStage = stage;
        lastOutcome = "Explicit original-run publication reconciliation VERIFIED";
        return switch (stage) {
            case "MarketSentimentDaily" -> market(request, range);
            case "RegimeFeaturesMonitorDaily" -> regime(request, range);
            default -> throw new IllegalArgumentException("Native stage required");
        };
    }

    private static String nativeDataset(String stage) {
        return switch (stage) {
            case "MarketSentimentDaily" -> "market_sentiment_daily";
            case "RegimeFeaturesMonitorDaily" -> "regime_features_monitor_daily";
            default ->
                    throw new IllegalArgumentException(
                            "Reconciliation accepts a native publication stage only");
        };
    }

    private List<Path> nativeFiles(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return List.of();
        try (var list = Files.list(directory)) {
            var files =
                    list.filter(
                                    p ->
                                            Files.isRegularFile(p)
                                                    && p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
            if (files.size() > 66)
                throw new Gate(BusinessState.BLOCKED, "Native attempt audit budget exceeded");
            return files;
        }
    }

    private Path recoveryPath(Path original) throws Exception {
        return original.resolveSibling("recovery-" + fileHash(original) + ".json");
    }

    private void requireNativeBinding(
            RunRequest request,
            PipelinePlan range,
            String stage,
            String dataset,
            NativeReceipt saved)
            throws Exception {
        if (!saved.instance().equals(request.instanceId())
                || !saved.stage().equals(stage)
                || !saved.definitionHash().equals(definitionHash(dataset))
                || !saved.from().equals(range.from())
                || !saved.to().equals(range.to()))
            throw new Gate(BusinessState.BLOCKED, "Native receipt frozen definition/range changed");
    }

    private void requireNativeClocks(NativeReceipt saved) throws Exception {
        for (var source : saved.sourceClocks().entrySet())
            if (!json.writeValueAsString(source.getValue())
                    .equals(json.writeValueAsString(clock(source.getKey()))))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Native source frontier changed: " + source.getKey());
    }

    private void requireNativeSource(NativeReceipt saved) throws Exception {
        if (saved.sourceEvidence() == null || saved.sourceEvidenceSha256() == null)
            throw new Gate(BusinessState.BLOCKED, "Original source evidence missing");
        Path source = Path.of(saved.sourceEvidence()).toAbsolutePath().normalize(),
                owned =
                        ledger.getParent()
                                .resolve("sync-evidence")
                                .resolve(saved.result().runId())
                                .toAbsolutePath()
                                .normalize();
        if (!source.startsWith(owned)
                || !source.getFileName().toString().equals("source.json")
                || !Files.isRegularFile(source)
                || !fileHash(source).equals(saved.sourceEvidenceSha256()))
            throw new Gate(BusinessState.BLOCKED, "Original owned source evidence missing/changed");
    }

    private void requireRecoveryPair(NativeReceipt old, NativeReceipt recovered) throws Exception {
        if (old.result().state() != SyncRunState.IN_DOUBT
                || !old.instance().equals(recovered.instance())
                || !old.stage().equals(recovered.stage())
                || !old.definitionHash().equals(recovered.definitionHash())
                || !old.from().equals(recovered.from())
                || !old.to().equals(recovered.to())
                || !old.frozenJson().equals(recovered.frozenJson())
                || !old.result().runId().equals(recovered.result().runId())
                || old.result().sourceRows() != recovered.result().sourceRows()
                || old.result().verifiedRows() != recovered.result().verifiedRows()
                || old.result().reusedRows() != recovered.result().reusedRows()
                || !Objects.equals(old.sourceFingerprint(), recovered.sourceFingerprint())
                || !Objects.equals(old.windowFingerprint(), recovered.windowFingerprint())
                || !Objects.equals(old.sourceEvidence(), recovered.sourceEvidence())
                || !Objects.equals(old.sourceEvidenceSha256(), recovered.sourceEvidenceSha256())
                || old.bootstrap() != recovered.bootstrap()
                || !json.writeValueAsString(old.sourceClocks())
                        .equals(json.writeValueAsString(recovered.sourceClocks()))
                || recovered.result().state() != SyncRunState.VERIFIED)
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Recovered receipt does not bind its original uncertain owner");
    }

    private NativeReceipt validateRecovery(
            RunRequest request,
            PipelinePlan range,
            String stage,
            String dataset,
            Path original,
            NativeReceipt old)
            throws Exception {
        Path link = recoveryPath(original);
        if (!Files.isRegularFile(link))
            throw new Gate(
                    BusinessState.IN_DOUBT,
                    "Native outcome uncertain; explicit original-run reconciliation required: "
                            + old.result().runId());
        var recovery = json.readValue(Files.readAllBytes(link), NativeRecovery.class);
        Path recoveredFile = Path.of(recovery.recoveredReceipt()).toAbsolutePath().normalize();
        if (!recovery.instance().equals(request.instanceId())
                || !recovery.input().equals(request.inputFingerprint())
                || !recovery.stage().equals(stage)
                || !recovery.runId().equals(old.result().runId())
                || !Path.of(recovery.originalReceipt()).equals(original)
                || !recovery.originalSha256().equals(fileHash(original))
                || !recoveredFile.getParent().equals(original.getParent())
                || !recoveredFile.getFileName().toString().startsWith("receipt-")
                || !Files.isRegularFile(recoveredFile)
                || !recovery.recoveredSha256().equals(fileHash(recoveredFile))
                || !recovery.frozenSha256().equals(RunRequest.hash(old.frozenJson())))
            throw new Gate(BusinessState.BLOCKED, "Immutable native recovery link changed");
        var recovered = json.readValue(Files.readAllBytes(recoveredFile), NativeReceipt.class);
        requireRecoveryPair(old, recovered);
        validateNativeReceipt(request, range, stage, dataset, recovered);
        if (!Objects.equals(recovery.sourceSha256(), recovered.sourceEvidenceSha256())
                || !Objects.equals(recovery.publicationSha256(), recovered.evidenceSha256()))
            throw new Gate(BusinessState.BLOCKED, "Recovered source/publication SHA changed");
        return recovered;
    }

    private PipelinePlan plan(RunRequest r) throws Exception {
        Path path = instance(r).resolve("plan.json");
        if (Files.isRegularFile(path)) {
            var p = json.readValue(Files.readAllBytes(path), PipelinePlan.class);
            if (!p.instance().equals(r.instanceId())
                    || !p.input().equals(r.inputFingerprint())
                    || !p.to().equals(r.logicalDate())
                    || p.sourceLogicalDate() == null
                    || ChronoUnit.DAYS.between(p.from(), p.to()) > 30)
                throw new Gate(BusinessState.BLOCKED, "Frozen bounded pipeline plan changed");
            if (!p.tradeDates().equals(calendar(p.from(), p.to())))
                throw new Gate(BusinessState.BLOCKED, "Frozen SSE calendar changed");
            return p;
        }
        var target = r.logicalDate();
        LocalDate from = target.minusDays(2);
        var horizon = target.minusDays(30);
        var expected = calendar(horizon, target);
        // 最大日期只用于发现无基线/超限，不能证明中间日期完整。逐源查最近 31 天的缺日，
        // 再交给原 owner 重新采集、完整键值回读和 WAL 核验；这里不签发完成凭证。
        for (var source : new TreeMap<>(DAILY_SOURCES).entrySet()) {
            String table = source.getKey(), column = source.getValue();
            var max =
                    jdbc.queryForObject(
                            "SELECT cast(max(\""
                                    + column
                                    + "\") AS LONG) FROM \""
                                    + table
                                    + "\" WHERE \""
                                    + column
                                    + "\"<cast(? AS TIMESTAMP)",
                            Long.class,
                            micros(target.plusDays(1)));
            if (max == null)
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Source requires explicit historical bootstrap: " + table);
            // 日期字段可能为微秒或纳秒；grid 已按表结构处理精度，最大日期也遵守同一契约。
            boolean nanos =
                    columns(table).stream()
                            .anyMatch(
                                    c ->
                                            column.equals(c.get("column"))
                                                    && "TIMESTAMP_NS".equals(c.get("type")));
            var latest = nanos ? dateNanos(max) : date(max);
            if (latest.plusDays(1).isBefore(horizon))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Source catchup exceeds 31 natural days; explicit bounded backfill required: "
                                + table);
            var present = grid(table, column, horizon, target);
            for (var day : expected) if (!present.contains(day) && day.isBefore(from)) from = day;
        }
        var dates = calendar(from, target);
        if (!dates.contains(target))
            throw new Gate(BusinessState.BLOCKED, "Target is not a verified open SSE date");
        // 新计划绑定请求日期；已有计划继续使用原先保存的来源日期，不在重试时重取今天。
        var p =
                new PipelinePlan(
                        r.instanceId(), r.inputFingerprint(), from, target, r.logicalDate(), dates);
        write(path, p);
        return p;
    }

    private CompletionEvidence sources(RunRequest r, PipelinePlan p) throws Exception {
        var receipts = new ArrayList<Path>();
        var limitations = new ArrayList<String>();
        for (var owner : owners) {
            if (!owner.table().equals(owner.binding().get()))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Main strategy requires formal binding: " + owner.table());
            for (LocalDate from = p.from(); !from.isAfter(p.to()); from = from.plusDays(3)) {
                LocalDate to = from.plusDays(2).isAfter(p.to()) ? p.to() : from.plusDays(2);
                if (!owner.table().equals("etf_portfolio") && calendar(from, to).isEmpty())
                    continue;
                Path receipt = source(r, owner, from, to);
                var savedReceipt = completedReceipt(r, owner, receipt.getParent());
                if (savedReceipt.isPresent()) {
                    var saved =
                            json.readValue(
                                    Files.readAllBytes(savedReceipt.get()), SourceReceipt.class);
                    receipts.add(savedReceipt.get());
                    if (saved.limitation() != null) limitations.add(saved.limitation());
                    continue;
                }
                Path started = receipt.resolveSibling("started.json");
                if (Files.isRegularFile(started) && !Files.isRegularFile(receipt))
                    throw new Gate(
                            BusinessState.IN_DOUBT,
                            "Source started without durable result: "
                                    + owner.table()
                                    + " "
                                    + from
                                    + ".."
                                    + to);
                Planned planned;
                try {
                    planned = owner.planner().get(from, to, p.sourceLogicalDate());
                } catch (Exception unavailable) {
                    var gate = planningGate(owner.table(), unavailable);
                    if (gate.state == BusinessState.IN_DOUBT) throw gate;
                    if (owner.optional()) {
                        limitations.add(
                                "margin_detail unavailable: "
                                        + Objects.toString(
                                                unavailable.getMessage(), "planning failed"));
                        continue;
                    }
                    throw gate;
                }
                if (!planned.request().from().equals(from) || !planned.request().to().equals(to)) {
                    if (owner.optional()) {
                        limitations.add(
                                "margin_detail source ceiling excludes requested window "
                                        + from
                                        + ".."
                                        + to);
                        continue;
                    }
                    throw new Gate(
                            BusinessState.WAITING_SOURCE,
                            "Owner ceiling did not cover frozen source window: " + owner.table());
                }
                // 只对已明确失败的尝试发起重试，并创建新的不可变意图和结果对。
                if (Files.isRegularFile(receipt)) {
                    String suffix = UUID.randomUUID().toString();
                    receipt = receipt.resolveSibling("receipt-" + suffix + ".json");
                    started = receipt.resolveSibling("started-" + suffix + ".json");
                }
                write(
                        started,
                        Map.of(
                                "instance",
                                r.instanceId(),
                                "dataset",
                                owner.table(),
                                "frozen",
                                SyncRequestIdentity.snapshotJson(planned.request()),
                                "started",
                                Instant.now()));
                var result = planned.run().get();
                requireLedgerResult(result, planned.request());
                boolean ready =
                        result.state() == SyncRunState.VERIFIED
                                || result.state() == SyncRunState.VERIFIED_EMPTY;
                String limitation = owner.optional() ? marginLimitation(from, to) : null;
                var window = snapshot(owner.table(), owner.date(), owner.keys(), from, to);
                var saved =
                        new SourceReceipt(
                                r.instanceId(),
                                owner.table(),
                                definitionHash(owner.table()),
                                result.runId(),
                                SyncRequestIdentity.snapshotJson(planned.request()),
                                result.state(),
                                from,
                                to,
                                window,
                                limitation,
                                Instant.now());
                write(receipt, saved);
                if (!ready) {
                    if (result.state() == SyncRunState.IN_DOUBT || !result.state().terminal())
                        throw new Gate(
                                BusinessState.IN_DOUBT,
                                "Source outcome uncertain: " + result.runId());
                    if (owner.optional()) {
                        limitations.add(
                                "margin_detail " + result.state() + ": " + result.errorCode());
                        continue;
                    }
                    throw new Gate(
                            BusinessState.WAITING_SOURCE,
                            "Source not verified: "
                                    + owner.table()
                                    + " "
                                    + result.state()
                                    + " "
                                    + result.errorCode());
                }
                validateReceipt(r, owner, saved);
                receipts.add(receipt);
                if (limitation != null) limitations.add(limitation);
            }
        }
        verifySourceCoverage(p);
        var windows = new ArrayList<Window>();
        for (var o : owners) windows.add(snapshot(o.table(), o.date(), o.keys(), p.from(), p.to()));
        windows.add(
                snapshot(
                        "exchange_calendar",
                        "cal_date",
                        List.of("cal_date", "exchange"),
                        p.from(),
                        p.to()));
        return certificate(
                r,
                "Sources",
                p,
                windows,
                receipts,
                null,
                Map.of(
                        "limitations",
                        limitations,
                        "coverage",
                        "Critical Java sources verified; margin may be unavailable for whole-market enhancement, never claimed complete."));
    }

    private CompletionEvidence market(RunRequest r, PipelinePlan p) throws Exception {
        var saved =
                nativeRun(
                        r,
                        p,
                        "MarketSentimentDaily",
                        "market_sentiment_daily",
                        () -> {
                            var plan = market.plan(p.from(), p.to(), p.to(), Mode.MATERIALIZE);
                            return new NativePlan(
                                    plan.request(),
                                    () -> {
                                        var result = market.run(plan);
                                        return new NativeOutcome(
                                                result.result(),
                                                result.sourceFingerprint(),
                                                result.fullTargetFingerprint(),
                                                null,
                                                result.evidence(),
                                                false);
                                    });
                        });
        new MarketSentimentDailyWritePort(jdbc, "market_sentiment_daily").formalSnapshot();
        finiteRow(
                "market_sentiment_daily",
                p.to(),
                List.of(
                        "sentiment_score",
                        "sentiment_score_core",
                        "heat_score",
                        "breadth_score",
                        "limit_score",
                        "profit_effect_score",
                        "moneyflow_score"));
        return certificate(
                r,
                "MarketSentimentDaily",
                p,
                List.of(
                        snapshot(
                                "market_sentiment_daily",
                                "trade_date",
                                List.of("trade_date"),
                                p.from(),
                                p.to())),
                List.of(Path.of(saved.evidence()), Path.of(saved.sourceEvidence())),
                saved.result().runId(),
                Map.of(
                        "sourceFingerprint",
                        saved.sourceFingerprint(),
                        "fullTargetFingerprint",
                        saved.fullTargetFingerprint()));
    }

    private CompletionEvidence regime(RunRequest r, PipelinePlan p) throws Exception {
        var saved =
                nativeRun(
                        r,
                        p,
                        "RegimeFeaturesMonitorDaily",
                        "regime_features_monitor_daily",
                        () -> {
                            var plan = regime.plan(p.from(), p.to(), p.to(), Mode.MATERIALIZE);
                            return new NativePlan(
                                    plan.request(),
                                    () -> {
                                        var result = regime.run(plan);
                                        return new NativeOutcome(
                                                result.result(),
                                                result.sourceFingerprint(),
                                                result.fullTargetFingerprint(),
                                                null,
                                                result.evidence(),
                                                false);
                                    });
                        });
        new RegimeFeaturesMonitorDailyWritePort(jdbc, "regime_features_monitor_daily")
                .formalSnapshot();
        finiteRow("regime_features_monitor_daily", p.to(), REGIME_REQUIRED);
        return certificate(
                r,
                "RegimeFeaturesMonitorDaily",
                p,
                List.of(
                        snapshot(
                                "regime_features_monitor_daily",
                                "trade_date",
                                List.of("trade_date"),
                                p.from(),
                                p.to())),
                List.of(Path.of(saved.evidence()), Path.of(saved.sourceEvidence())),
                saved.result().runId(),
                Map.of(
                        "sourceFingerprint",
                        saved.sourceFingerprint(),
                        "fullTargetFingerprint",
                        saved.fullTargetFingerprint()));
    }

    private CompletionEvidence backtest(RunRequest r, PipelinePlan p) throws Exception {
        final BacktestDailyMaterializedViewPort.State state;
        try {
            state =
                    new BacktestDailyMaterializedViewPort(jdbc)
                            .awaitCurrent(Duration.ofSeconds(30));
        } catch (BacktestDailyMaterializationReadGuard.NotReadyException waiting) {
            throw new Gate(BusinessState.WAITING_SOURCE, waiting.getMessage());
        }
        return certificate(
                r,
                "BacktestDaily",
                p,
                List.of(
                        snapshot(
                                "mv_backtest_daily",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                p.from(),
                                p.to())),
                List.of(),
                null,
                Map.of(
                        "backtest",
                        state,
                        "producer",
                        "questdb.native_materialized_view",
                        "refresh",
                        "immediate",
                        "base",
                        "stk_limit"));
    }

    private NativeReceipt nativeRun(
            RunRequest request,
            PipelinePlan range,
            String stage,
            String dataset,
            Call<NativePlan> planner)
            throws Exception {
        Path directory = instance(request).resolve("native").resolve(stage);
        List<Path> files = nativeFiles(directory);
        if (files.size() > 66)
            throw new Gate(BusinessState.BLOCKED, "Native attempt audit budget exceeded");
        var verified = new ArrayList<NativeReceipt>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.startsWith("started-")) {
                if (!Files.isRegularFile(
                        file.resolveSibling(name.replaceFirst("started-", "receipt-"))))
                    throw new Gate(
                            BusinessState.IN_DOUBT,
                            "Native intent has no durable outcome: " + file);
            } else if (name.startsWith("receipt-")) {
                var saved = json.readValue(Files.readAllBytes(file), NativeReceipt.class);
                if (!saved.result().state().terminal()) {
                    validateRecovery(request, range, stage, dataset, file, saved);
                    continue;
                }
                if (saved.result().state() == SyncRunState.VERIFIED
                        || saved.result().state() == SyncRunState.VERIFIED_EMPTY) {
                    validateNativeReceipt(request, range, stage, dataset, saved);
                    verified.add(saved);
                }
            }
        }
        if (verified.size() > 1)
            throw new Gate(BusinessState.BLOCKED, "Ambiguous verified native outcomes");
        if (!verified.isEmpty()) return verified.getFirst();
        NativePlan prepared;
        try {
            prepared = planner.get();
        } catch (Exception error) {
            throw planningGate(stage, error);
        }
        var clocks = new TreeMap<String, Map<String, Object>>();
        for (var o : owners) clocks.put(o.table(), clock(o.table()));
        clocks.put("exchange_calendar", clock("exchange_calendar"));
        String id = UUID.randomUUID().toString();
        write(
                directory.resolve("started-" + id + ".json"),
                Map.of(
                        "instance",
                        request.instanceId(),
                        "stage",
                        stage,
                        "frozen",
                        SyncRequestIdentity.snapshotJson(prepared.frozen()),
                        "sourceClocks",
                        clocks,
                        "started",
                        Instant.now()));
        var outcome = prepared.run().get();
        requireLedgerResult(outcome.result(), prepared.frozen());
        Path supplied =
                outcome.evidence() == null
                        ? null
                        : Path.of(outcome.evidence()).toAbsolutePath().normalize();
        Path source = supplied == null ? null : supplied.resolveSibling("source.json");
        Path publication = supplied == null ? null : supplied.resolveSibling("publication.json");
        String sourceSha = source == null || !Files.isRegularFile(source) ? null : fileHash(source);
        String publicationSha =
                publication == null || !Files.isRegularFile(publication)
                        ? null
                        : fileHash(publication);
        var saved =
                new NativeReceipt(
                        request.instanceId(),
                        stage,
                        definitionHash(dataset),
                        range.from(),
                        range.to(),
                        SyncRequestIdentity.snapshotJson(prepared.frozen()),
                        outcome.result(),
                        outcome.sourceFingerprint(),
                        outcome.fullTargetFingerprint(),
                        outcome.windowFingerprint(),
                        publication == null ? null : publication.toString(),
                        publicationSha,
                        source == null ? null : source.toString(),
                        sourceSha,
                        outcome.bootstrap(),
                        clocks,
                        Instant.now());
        write(directory.resolve("receipt-" + id + ".json"), saved);
        requireReady(outcome.result(), prepared.frozen());
        validateNativeReceipt(request, range, stage, dataset, saved);
        return saved;
    }

    private void validateNativeReceipt(
            RunRequest request,
            PipelinePlan range,
            String stage,
            String dataset,
            NativeReceipt saved)
            throws Exception {
        requireNativeBinding(request, range, stage, dataset, saved);
        var authority = SyncRunLedger.openReadOnly(ledger);
        var run = authority.getRun(saved.result().runId());
        if (authority.get(run.id()).state() != saved.result().state()
                || !run.frozenJson().equals(saved.frozenJson())
                || run.jobVersion() != definition(dataset).version()
                || !run.jobId().equals(definition(dataset).jobId()))
            throw new Gate(BusinessState.BLOCKED, "Native ledger receipt changed");
        if (saved.evidence() == null
                || saved.evidenceSha256() == null
                || !Files.isRegularFile(Path.of(saved.evidence()))
                || !saved.evidenceSha256().equals(fileHash(Path.of(saved.evidence()))))
            throw new Gate(BusinessState.BLOCKED, "Native publication evidence missing/changed");
        if (saved.sourceEvidence() == null
                || saved.sourceEvidenceSha256() == null
                || !Files.isRegularFile(Path.of(saved.sourceEvidence()))
                || !saved.sourceEvidenceSha256().equals(fileHash(Path.of(saved.sourceEvidence()))))
            throw new Gate(BusinessState.BLOCKED, "Native source evidence missing/changed");
        requireNativeSource(saved);
        requireNativeClocks(saved);
        var source = json.readTree(Files.readAllBytes(Path.of(saved.sourceEvidence())));
        var publication = json.readTree(Files.readAllBytes(Path.of(saved.evidence())));
        var entry = publicationEntry(dataset, saved.result().runId());
        var scope = json.readTree(entry.intent().scope());
        var frozen = json.readTree(saved.frozenJson());
        if (entry.state() != ReferencePublicationJournal.State.VERIFIED
                || !entry.intent().target().equals(dataset)
                || !scope.path("sourceEvidence").asText().equals(saved.sourceEvidence())
                || !scope.path("sourceEvidenceSha256").asText().equals(saved.sourceEvidenceSha256())
                || !scope.path("from").asText().equals(frozen.path("from").asText())
                || !scope.path("to").asText().equals(frozen.path("to").asText())
                || !entry.intent().afterFingerprint().equals(saved.fullTargetFingerprint()))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Original native publication journal/source/frozen scope differs");
        if ((!publication.path("published").asBoolean()
                || !publication.path("sourceEvidence").asText().equals(saved.sourceEvidence())))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Native publication does not bind the owned source file");
        if (publication.has("sourceEvidenceSha256")
                        && !publication
                                .path("sourceEvidenceSha256")
                                .asText()
                                .equals(saved.sourceEvidenceSha256())
                || publication.has("runId")
                        && !publication.path("runId").asText().equals(saved.result().runId()))
            throw new Gate(
                    BusinessState.BLOCKED, "Native publication source SHA/original run differs");
        if ((!source.path("sourceFingerprint").asText().equals(saved.sourceFingerprint())
                || !publication.path("sourceFingerprint").asText().equals(saved.sourceFingerprint())
                || !publication
                        .path("fullTargetFingerprint")
                        .asText()
                        .equals(saved.fullTargetFingerprint())))
            throw new Gate(
                    BusinessState.BLOCKED, "Native source/publication value fingerprints differ");
        if (dataset.equals("market_sentiment_daily")
                && !saved.fullTargetFingerprint()
                        .equals(
                                new MarketSentimentDailyWritePort(jdbc, dataset)
                                        .formalSnapshot()
                                        .fingerprint()))
            throw new Gate(BusinessState.BLOCKED, "Native sentiment published SHA changed");
        if (dataset.equals("regime_features_monitor_daily")
                && !saved.fullTargetFingerprint()
                        .equals(
                                new RegimeFeaturesMonitorDailyWritePort(jdbc, dataset)
                                        .formalSnapshot()
                                        .fingerprint()))
            throw new Gate(BusinessState.BLOCKED, "Native regime published SHA changed");
    }

    private ReferencePublicationJournal.Entry publicationEntry(String dataset, String run)
            throws Exception {
        // 普通日志构造器可能迁移元数据；已完成结果的核查必须保持只读。
        try (var db =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + ledger.toUri().toASCIIString() + "?mode=ro");
                var settings = db.createStatement()) {
            settings.execute("PRAGMA query_only=ON");
            settings.execute("PRAGMA busy_timeout=5000");
            try (var query =
                    db.prepareStatement(
                            "SELECT intent_json,state,revision FROM reference_publications WHERE dataset=? AND run_id=? LIMIT 2")) {
                query.setString(1, dataset);
                query.setString(2, run);
                try (var rows = query.executeQuery()) {
                    if (!rows.next())
                        throw new Gate(
                                BusinessState.BLOCKED, "Original publication journal missing");
                    var result =
                            new ReferencePublicationJournal.Entry(
                                    json.readValue(
                                            rows.getString(1),
                                            ReferencePublicationJournal.Intent.class),
                                    ReferencePublicationJournal.State.valueOf(rows.getString(2)),
                                    rows.getLong(3));
                    if (rows.next())
                        throw new Gate(
                                BusinessState.BLOCKED, "Ambiguous original publication journal");
                    return result;
                }
            }
        }
    }

    private CompletionEvidence acceptance(
            RunRequest r, PipelinePlan p, CompletionEvidence officialStage) throws Exception {
        var officialDetails =
                readStageEvidence(r, "OfficialPlanData", officialStage).path("details");
        var plan = strategyData.requirePlan(p.to());
        var authority = officialPlanAuthority(plan);
        requireSamePlanAuthority(officialDetails, json.valueToTree(authority));
        var details = new LinkedHashMap<String, Object>(verifyConsumer(p.to(), plan));
        var windows = new ArrayList<Window>();
        for (var o : owners) windows.add(snapshot(o.table(), o.date(), o.keys(), p.from(), p.to()));
        windows.add(
                snapshot(
                        "exchange_calendar",
                        "cal_date",
                        List.of("cal_date", "exchange"),
                        p.from(),
                        p.to()));
        windows.add(
                snapshot(
                        "market_sentiment_daily",
                        "trade_date",
                        List.of("trade_date"),
                        p.from(),
                        p.to()));
        windows.add(
                snapshot(
                        "regime_features_monitor_daily",
                        "trade_date",
                        List.of("trade_date"),
                        p.from(),
                        p.to()));
        windows.add(
                snapshot(
                        "mv_backtest_daily",
                        "trade_date",
                        List.of("trade_date", "ts_code"),
                        p.from(),
                        p.to()));
        windows.add(snapshot("l2_daily_features", "ts", List.of("ts", "symbol"), p.to(), p.to()));
        var scores = strategyData.scores(p.to());
        requireScoreSources(scores);
        requireSamePlanAuthority(
                officialDetails,
                json.valueToTree(officialPlanAuthority(strategyData.requirePlan(p.to()))));
        details.putAll(authority);
        details.put("officialPlanStage", officialStage);
        return certificate(
                r,
                "MainStrategyAcceptance",
                p,
                windows,
                strategyData.scoreEvidence(scores),
                null,
                details);
    }

    private CompletionEvidence l2(RunRequest r, PipelinePlan p) throws Exception {
        var proofs = new ArrayList<FormalL2DailyFeaturesIngestion.Evidence>();
        for (var day : p.tradeDates()) {
            try {
                proofs.add(l2.ingestAndValidate(day, () -> Thread.currentThread().isInterrupted()));
            } catch (Exception unavailable) {
                throw planningGate("L2DailyFeatures", unavailable);
            }
        }
        return certificate(
                r,
                "L2DailyFeatures",
                p,
                List.of(
                        snapshot(
                                "l2_daily_features",
                                "ts",
                                List.of("ts", "symbol"),
                                p.from(),
                                p.to())),
                List.of(),
                null,
                Map.of("producer", "java.native_l2_parquet", "dates", proofs));
    }

    private CompletionEvidence scores(RunRequest r, PipelinePlan p) throws Exception {
        Publication publication;
        try {
            publication = strategyData.prepareScores(p.to(), "java_scores_" + r.instanceId());
        } catch (Exception unavailable) {
            throw planningGate("ScoreInputs", unavailable);
        }
        return certificate(
                r,
                "ScoreInputs",
                p,
                List.of(
                        snapshot(
                                "mv_backtest_daily",
                                "trade_date",
                                List.of("trade_date", "ts_code"),
                                p.from(),
                                p.to())),
                strategyData.scoreEvidence(publication),
                null,
                Map.of("publication", publication));
    }

    private CompletionEvidence officialPlan(RunRequest r, PipelinePlan p) throws Exception {
        StoredOfficialPlan plan;
        try {
            plan = strategyData.requirePlan(p.to());
        } catch (Exception unavailable) {
            throw planningGate("OfficialPlanData", unavailable);
        }
        return certificate(
                r,
                "OfficialPlanData",
                p,
                List.of(
                        snapshot(
                                "etf_daily",
                                "timestamp",
                                List.of("timestamp", "ts_code"),
                                p.to(),
                                p.to())),
                strategyData.planEvidence(plan),
                null,
                officialPlanAuthority(plan));
    }

    private void requireLedgerResult(
            SyncJobRunner.Result result, SyncJobDefinition.FrozenRequest request) throws Exception {
        var authority = SyncRunLedger.openReadOnly(ledger);
        var run = authority.getRun(result.runId());
        var entry = authority.get(result.runId());
        if (entry.state() != result.state()
                || !run.frozenJson().equals(SyncRequestIdentity.snapshotJson(request))
                || !run.jobId().equals(request.definition().jobId())
                || run.jobVersion() != request.definition().version())
            throw new Gate(
                    BusinessState.IN_DOUBT,
                    "Owner result disagrees with durable frozen sync ledger");
    }

    private void requireReady(SyncJobRunner.Result result, SyncJobDefinition.FrozenRequest request)
            throws Exception {
        requireLedgerResult(result, request);
        if (result.state() != SyncRunState.VERIFIED
                && result.state() != SyncRunState.VERIFIED_EMPTY)
            throw new Gate(
                    result.state() == SyncRunState.IN_DOUBT || !result.state().terminal()
                            ? BusinessState.IN_DOUBT
                            : BusinessState.WAITING_SOURCE,
                    "Native owner not verified: "
                            + result.runId()
                            + " "
                            + result.state()
                            + " "
                            + result.errorCode());
    }

    private void validateReceipt(RunRequest r, Owner owner, SourceReceipt saved) throws Exception {
        if (!saved.instance().equals(r.instanceId())
                || !saved.dataset().equals(owner.table())
                || !saved.definitionHash().equals(definitionHash(owner.table()))
                || saved.state() != SyncRunState.VERIFIED
                        && saved.state() != SyncRunState.VERIFIED_EMPTY)
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Source receipt binding changed; explicit revision required");
        var authority = SyncRunLedger.openReadOnly(ledger);
        var run = authority.getRun(saved.runId());
        var entry = authority.get(saved.runId());
        var frozen = json.readTree(saved.frozenJson());
        if (entry.state() != saved.state()
                || !run.frozenJson().equals(saved.frozenJson())
                || run.jobVersion() != definition(owner.table()).version()
                || !run.jobId().equals(definition(owner.table()).jobId())
                || !frozen.path("from").asText().equals(saved.from().toString())
                || !frozen.path("to").asText().equals(saved.to().toString()))
            throw new Gate(
                    BusinessState.BLOCKED, "Frozen source authority changed: " + saved.runId());
        if (!saved.window()
                .equals(
                        snapshot(
                                owner.table(),
                                owner.date(),
                                owner.keys(),
                                saved.from(),
                                saved.to())))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Completed source values changed: "
                            + owner.table()
                            + "; explicit revision required");
        if (!owner.sparse() && !owner.optional() && saved.window().rows() == 0)
            throw new Gate(
                    BusinessState.WAITING_SOURCE, "Dense source verified empty: " + owner.table());
    }

    private CompletionEvidence certificate(
            RunRequest r,
            String stage,
            PipelinePlan p,
            List<Window> windows,
            List<Path> artifacts,
            String runId,
            Map<String, ?> details)
            throws Exception {
        var refs = new TreeMap<String, String>();
        for (Path path : artifacts) {
            Path owned = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(owned))
                throw new Gate(BusinessState.IN_DOUBT, "Owner evidence absent: " + owned);
            refs.put(owned.toString(), fileHash(owned));
        }
        var clocks = new TreeMap<String, Object>();
        for (var o : owners) clocks.put(o.table(), clock(o.table()));
        clocks.put("exchange_calendar", clock("exchange_calendar"));
        var content = new LinkedHashMap<String, Object>();
        content.put("producer", "java.main_strategy_daily");
        content.put("instance", r.instanceId());
        content.put("stage", stage);
        content.put("input", r.inputFingerprint());
        content.put("plan", p);
        content.put("windows", windows);
        content.put("sourceClocks", clocks);
        content.put("artifacts", refs);
        content.put("runId", runId);
        content.put("details", details);
        content.put("completed", Instant.now());
        Path artifact = instance(r).resolve(stage + "-" + UUID.randomUUID() + ".json");
        write(artifact, content);
        long rows = windows.stream().mapToLong(Window::rows).sum();
        if (rows == 0)
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "Main-strategy stage lacks physical rows: " + stage);
        return new CompletionEvidence(
                1,
                "java.main_strategy_daily",
                r.instanceId(),
                stage,
                runId == null ? r.instanceId() : runId,
                r.logicalDate(),
                r.rangeStart(),
                r.rangeEnd(),
                r.rangeStart(),
                r.rangeEnd(),
                r.definitionVersion(),
                "stage-sha256:" + fileHash(artifact),
                r.inputFingerprint(),
                rows,
                rows,
                true,
                true,
                true,
                true,
                true,
                false,
                Instant.now(),
                BusinessState.VERIFIED,
                artifact.toString(),
                null);
    }

    private JsonNode readStageEvidence(RunRequest r, String stage, CompletionEvidence certificate)
            throws Exception {
        if (certificate == null || !certificate.state().ready() || !certificate.matches(r, stage))
            throw new Gate(
                    BusinessState.BLOCKED, "Stage certificate does not match frozen instance");
        Path file = Path.of(certificate.artifact()).toAbsolutePath().normalize();
        if (!file.startsWith(instance(r)) || !Files.isRegularFile(file))
            throw new Gate(BusinessState.BLOCKED, "Owned stage evidence missing");
        if (!certificate.sourceVersion().equals("stage-sha256:" + fileHash(file)))
            throw new Gate(BusinessState.BLOCKED, "Immutable stage artifact SHA changed");
        var e = json.readTree(Files.readAllBytes(file));
        if (!e.path("instance").asText().equals(r.instanceId())
                || !e.path("stage").asText().equals(stage)
                || !e.path("input").asText().equals(r.inputFingerprint()))
            throw new Gate(BusinessState.BLOCKED, "Stage evidence identity changed");
        return e;
    }

    private void validateStage(RunRequest r, String stage, CompletionEvidence certificate)
            throws Exception {
        var e = readStageEvidence(r, stage, certificate);
        for (var w : e.path("windows")) {
            var old = json.treeToValue(w, Window.class);
            if (!old.equals(snapshot(old.table(), old.date(), old.keys(), old.from(), old.to())))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Live data changed after completion: " + old.table());
        }
        var fields = e.path("artifacts").fields();
        while (fields.hasNext()) {
            var ref = fields.next();
            Path artifact = Path.of(ref.getKey()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(artifact)
                    || !ref.getValue().asText().equals(fileHash(artifact)))
                throw new Gate(BusinessState.BLOCKED, "Owner artifact changed: " + artifact);
        }
        if (stage.equals("Sources")) {
            var p = json.treeToValue(e.path("plan"), PipelinePlan.class);
            for (var ref : e.path("artifacts").properties()) {
                Path path = Path.of(ref.getKey());
                var receipt = json.readValue(Files.readAllBytes(path), SourceReceipt.class);
                var o =
                        owners.stream()
                                .filter(v -> v.table().equals(receipt.dataset()))
                                .findFirst()
                                .orElseThrow();
                validateReceipt(r, o, receipt);
            }
            verifySourceCoverage(p);
        }
        var sourceClocks = e.path("sourceClocks").fields();
        while (sourceClocks.hasNext()) {
            var expected = sourceClocks.next();
            if (!expected.getValue()
                    .toString()
                    .equals(json.writeValueAsString(clock(expected.getKey()))))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Source physical identity/frontier changed: " + expected.getKey());
        }
        JsonNode runNode = e.path("runId");
        String run = runNode.isTextual() ? runNode.asText() : "";
        if (!run.isBlank()) {
            var authority = SyncRunLedger.openReadOnly(ledger);
            if (authority.get(run).state() != SyncRunState.VERIFIED)
                throw new Gate(BusinessState.BLOCKED, "Native owner no longer VERIFIED: " + run);
        }
        if (stage.equals("MarketSentimentDaily")
                && !new MarketSentimentDailyWritePort(jdbc, "market_sentiment_daily")
                        .formalSnapshot()
                        .fingerprint()
                        .equals(e.path("details").path("fullTargetFingerprint").asText()))
            throw new Gate(BusinessState.BLOCKED, "Published full sentiment SHA changed");
        if (stage.equals("RegimeFeaturesMonitorDaily")
                && !new RegimeFeaturesMonitorDailyWritePort(jdbc, "regime_features_monitor_daily")
                        .formalSnapshot()
                        .fingerprint()
                        .equals(e.path("details").path("fullTargetFingerprint").asText()))
            throw new Gate(BusinessState.BLOCKED, "Published full regime SHA changed");
        if (stage.equals("BacktestDaily") || stage.equals("MainStrategyAcceptance"))
            new BacktestDailyMaterializedViewPort(jdbc).sourceState();
        if (stage.equals("BacktestDaily")
                && !json.writeValueAsString(
                                new BacktestDailyMaterializedViewPort(jdbc).sourceState())
                        .equals(e.path("details").path("backtest").toString()))
            throw new Gate(BusinessState.BLOCKED, "Native backtest MV or raw sources changed");
        if (stage.equals("L2DailyFeatures")) {
            for (var proof : e.path("details").path("dates")) {
                var actual = l2.validate(LocalDate.parse(proof.path("day").asText()));
                if (!actual.sourceFingerprint().equals(proof.path("sourceFingerprint").asText())
                        || !actual.readbackDigest().equals(proof.path("readbackDigest").asText())
                        || !actual.targetIdentity().equals(proof.path("targetIdentity").asText()))
                    throw new Gate(
                            BusinessState.BLOCKED,
                            "Native L2 source/physical values changed after completion");
            }
        }
        if (stage.equals("ScoreInputs")) {
            var current = strategyData.scores(r.logicalDate());
            if (!current.producerRunId()
                    .equals(e.path("details").path("publication").path("producerRunId").asText()))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Java score alias advanced to a different frozen run");
            requireScoreSources(current);
        }
        if (stage.equals("OfficialPlanData") || stage.equals("MainStrategyAcceptance")) {
            requireSamePlanAuthority(
                    e.path("details"),
                    json.valueToTree(
                            officialPlanAuthority(strategyData.requirePlan(r.logicalDate()))));
            if (stage.equals("MainStrategyAcceptance")) {
                requireScoreSources(strategyData.scores(r.logicalDate()));
                var official = recordedOfficialPlanStage(e.path("details"));
                requireSamePlanAuthority(
                        e.path("details"),
                        readStageEvidence(r, "OfficialPlanData", official).path("details"));
            }
        }
    }

    private static Map<String, String> officialPlanAuthority(StoredOfficialPlan plan) {
        return Map.of(
                "planId",
                plan.planId(),
                "contentSha256",
                plan.contentSha256(),
                "scoreRun",
                plan.provenance().orElseThrow().scoreRunId());
    }

    private void requireScoreSources(Publication scores) throws Exception {
        try {
            strategyData.validateScoreSources(scores);
        } catch (IllegalStateException changed) {
            throw new Gate(BusinessState.BLOCKED, changed.getMessage());
        }
    }

    static void requireSamePlanAuthority(JsonNode expected, JsonNode actual) throws Exception {
        for (String field : List.of("planId", "contentSha256", "scoreRun")) {
            var saved = expected.path(field);
            var current = actual.path(field);
            if (!saved.isTextual()
                    || saved.asText().isBlank()
                    || !current.isTextual()
                    || !saved.asText().equals(current.asText()))
                throw new Gate(
                        BusinessState.BLOCKED,
                        "Canonical official plan authority changed or missing: " + field);
        }
    }

    static void requireSamePlanStage(CompletionEvidence recorded, CompletionEvidence current)
            throws Exception {
        if (recorded == null || current == null || !recorded.equals(current))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Acceptance is not bound to this run's OfficialPlanData certificate");
    }

    private void requireAcceptancePlanStage(
            RunRequest r, CompletionEvidence acceptance, CompletionEvidence official)
            throws Exception {
        var details = readStageEvidence(r, "MainStrategyAcceptance", acceptance).path("details");
        var recorded = recordedOfficialPlanStage(details);
        requireSamePlanStage(recorded, official);
        requireSamePlanAuthority(
                details, readStageEvidence(r, "OfficialPlanData", official).path("details"));
    }

    private CompletionEvidence recordedOfficialPlanStage(JsonNode details) throws Exception {
        var recorded = details.path("officialPlanStage");
        if (!recorded.isObject())
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Acceptance lacks the frozen OfficialPlanData certificate");
        return json.treeToValue(recorded, CompletionEvidence.class);
    }

    private List<LocalDate> calendar(LocalDate from, LocalDate to) throws Exception {
        long count = ChronoUnit.DAYS.between(from, to) + 1;
        if (count < 1 || count > 366)
            throw new Gate(BusinessState.BLOCKED, "Finite complete calendar range required");
        var seen = new TreeMap<LocalDate, Integer>();
        jdbc.query(
                "SELECT cast(cal_date AS LONG) AS d,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=cast(? AS TIMESTAMP) AND cal_date<cast(? AS TIMESTAMP) ORDER BY cal_date LIMIT 367",
                (RowCallbackHandler)
                        rs -> {
                            Object raw = rs.getObject(1), open = rs.getObject(2);
                            if (!(raw instanceof Number n)
                                    || !(open instanceof Number flag)
                                    || flag.intValue() < 0
                                    || flag.intValue() > 1
                                    || seen.put(date(n.longValue()), flag.intValue()) != null)
                                throw new IllegalStateException(
                                        "Typed unique SSE calendar required");
                        },
                micros(from),
                micros(to.plusDays(1)));
        if (seen.size() != count || !seen.firstKey().equals(from) || !seen.lastKey().equals(to))
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "SSE natural-day calendar coverage incomplete " + from + ".." + to);
        return seen.entrySet().stream()
                .filter(e -> e.getValue() == 1)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void verifySourceCoverage(PipelinePlan p) throws Exception {
        for (String table :
                List.of(
                        "daily",
                        "daily_basic",
                        "stk_factor",
                        "stk_limit",
                        "moneyflow",
                        "moneyflow_hsgt",
                        "etf_daily",
                        "etf_adj",
                        "etf_factor")) {
            var owner =
                    owners.stream().filter(o -> o.table().equals(table)).findFirst().orElseThrow();
            var grid = grid(table, owner.date(), p.from(), p.to());
            if (!grid.equals(new TreeSet<>(p.tradeDates())))
                throw new Gate(
                        BusinessState.WAITING_SOURCE, "Source open-day grid incomplete: " + table);
        }
        for (var d : p.tradeDates()) {
            requireContains("daily_basic", "trade_date", "stk_factor", "trade_date", d);
            requireContains("stk_limit", "trade_date", "stk_factor", "trade_date", d);
            finiteRow(
                    "cn_bond_yield_curve",
                    d,
                    List.of("yield_value"),
                    "curve_code='gov' AND tenor='10Y'");
        }
    }

    private Map<String, Object> verifyConsumer(LocalDate target) throws Exception {
        return verifyConsumer(target, strategyData.requirePlan(target));
    }

    private Map<String, Object> verifyConsumer(LocalDate target, StoredOfficialPlan plan)
            throws Exception {
        if (!calendar(target, target).equals(List.of(target)))
            throw new Gate(BusinessState.BLOCKED, "Consumer target is not an open SSE day");
        finiteRow(
                "market_sentiment_daily",
                target,
                List.of(
                        "sentiment_score",
                        "sentiment_score_core",
                        "heat_score",
                        "breadth_score",
                        "limit_score",
                        "profit_effect_score",
                        "moneyflow_score"));
        finiteRow("regime_features_monitor_daily", target, REGIME_REQUIRED);
        var port = new BacktestDailyMaterializedViewPort(jdbc);
        var state = port.sourceState();
        requireContains("mv_backtest_daily", "trade_date", "stk_factor", "trade_date", target);
        for (String field :
                List.of(
                        "open",
                        "high",
                        "low",
                        "close",
                        "vol",
                        "amount",
                        "adj_factor",
                        "up_limit",
                        "down_limit"))
            finiteColumn("mv_backtest_daily", "trade_date", target, field);
        var etfCounts =
                jdbc.queryForList(
                        "SELECT count() AS n FROM etf_daily WHERE timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP)",
                        micros(target),
                        micros(target.plusDays(1)));
        long current = ((Number) etfCounts.getFirst().get("n")).longValue();
        var history =
                jdbc.queryForList(
                        "SELECT timestamp,count() AS n FROM etf_daily WHERE timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP) GROUP BY timestamp ORDER BY timestamp LIMIT 41",
                        micros(target.minusDays(40)),
                        micros(target));
        var ns = history.stream().map(v -> ((Number) v.get("n")).longValue()).sorted().toList();
        double baseline =
                ns.isEmpty()
                        ? current
                        : ns.size() % 2 == 1
                                ? ns.get(ns.size() / 2)
                                : (ns.get(ns.size() / 2 - 1) + ns.get(ns.size() / 2)) / 2.0;
        if (current <= 0 || current < baseline * 0.95)
            throw new Gate(BusinessState.WAITING_SOURCE, "ETF daily pool below 95% recent median");
        var authoritativeTargets = plan.plan().targetSymbols();
        if (!targetEtfs.isEmpty()
                && !new TreeSet<>(targetEtfs).equals(new TreeSet<>(authoritativeTargets)))
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Configured targets differ from the canonical official plan");
        var disclosure = new ArrayList<Map<String, Object>>();
        for (String code : authoritativeTargets) {
            long n =
                    jdbc.queryForObject(
                            "SELECT count() FROM etf_daily WHERE ts_code=? AND timestamp>=cast(? AS TIMESTAMP) AND timestamp<cast(? AS TIMESTAMP)",
                            Long.class,
                            code,
                            micros(target),
                            micros(target.plusDays(1)));
            if (n != 1)
                throw new Gate(
                        BusinessState.WAITING_SOURCE,
                        "Held ETF daily missing or duplicate: " + code);
            var latest =
                    jdbc.queryForObject(
                            "SELECT cast(max(ann_date) AS LONG) FROM etf_portfolio WHERE ts_code=? AND ann_date<cast(? AS TIMESTAMP)",
                            Long.class,
                            code,
                            micros(target.plusDays(1)));
            if (latest == null)
                throw new Gate(
                        BusinessState.WAITING_SOURCE,
                        "Held ETF has no point-in-time disclosure: " + code);
            disclosure.add(
                    Map.of(
                            "code",
                            code,
                            "latestDisclosure",
                            date(latest),
                            "ageDays",
                            ChronoUnit.DAYS.between(date(latest), target),
                            "quality",
                            "Presence verified; weights completeness and stale disclosure remain separate limitations."));
        }
        var l2 = snapshot("l2_daily_features", "ts", List.of("ts", "symbol"), target, target);
        if (l2.rows() == 0)
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "Level2 not loaded for target; archive cleaning/import is a separate owner");
        var l2Proof = this.l2.validate(target);
        return Map.of(
                "target",
                target,
                "backtest",
                state,
                "etfRows",
                current,
                "etfBaseline",
                baseline,
                "l2Rows",
                l2.rows(),
                "l2",
                l2Proof,
                "disclosures",
                disclosure,
                "marginLimitation",
                Objects.toString(marginLimitation(target, target), "none"),
                "consumer",
                "Main daily fields and the backtest result table physically verified; optional disclosure/margin limitations are explicit.");
    }

    private static String basic(LocalDate d) {
        return d.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private void requireContains(
            String actual, String actualDate, String expected, String expectedDate, LocalDate d)
            throws Exception {
        var missing =
                jdbc.queryForList(
                        "SELECT e.ts_code FROM \""
                                + expected
                                + "\" e LEFT JOIN \""
                                + actual
                                + "\" a ON e.ts_code=a.ts_code AND e.\""
                                + expectedDate
                                + "\"=a.\""
                                + actualDate
                                + "\" WHERE e.\""
                                + expectedDate
                                + "\">=cast(? AS TIMESTAMP) AND e.\""
                                + expectedDate
                                + "\"<cast(? AS TIMESTAMP) AND a.ts_code IS NULL LIMIT 101",
                        micros(d),
                        micros(d.plusDays(1)));
        if (!missing.isEmpty())
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    actual + " lacks factor business keys on " + d + ": " + missing);
    }

    private void finiteRow(String table, LocalDate d, List<String> fields) throws Exception {
        finiteRow(table, d, fields, "1=1");
    }

    private void finiteRow(String table, LocalDate d, List<String> fields, String extra)
            throws Exception {
        var values =
                jdbc.queryForList(
                        "SELECT "
                                + String.join(",", fields)
                                + " FROM \""
                                + table
                                + "\" WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) AND "
                                + extra
                                + " LIMIT 2",
                        micros(d),
                        micros(d.plusDays(1)));
        if (values.size() != 1)
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "Unique required daily row missing: " + table + " " + d);
        for (var field : fields) {
            Object value = values.getFirst().get(field);
            if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
                throw new Gate(
                        BusinessState.WAITING_SOURCE,
                        "Required finite field unavailable: " + table + "." + field + " " + d);
        }
    }

    private void finiteColumn(String table, String column, LocalDate d, String field)
            throws Exception {
        final boolean[] bad = {false};
        final long[] count = {0};
        jdbc.query(
                "SELECT \""
                        + field
                        + "\" FROM \""
                        + table
                        + "\" WHERE \""
                        + column
                        + "\">=cast(? AS TIMESTAMP) AND \""
                        + column
                        + "\"<cast(? AS TIMESTAMP) LIMIT 10001",
                (RowCallbackHandler)
                        rs -> {
                            if (++count[0] > 10000)
                                throw new IllegalStateException(
                                        "Complete one-day stock budget exceeded");
                            Object v = rs.getObject(1);
                            if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()))
                                bad[0] = true;
                        },
                micros(d),
                micros(d.plusDays(1)));
        if (bad[0])
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "Backtest critical values null/nonfinite: " + field);
    }

    private Set<LocalDate> grid(String table, String column, LocalDate from, LocalDate to) {
        var type =
                columns(table).stream()
                        .filter(c -> column.equals(c.get("column")))
                        .map(c -> Objects.toString(c.get("type")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Business date column absent: "
                                                        + table
                                                        + "."
                                                        + column));
        if (!Set.of("TIMESTAMP", "TIMESTAMP_NS").contains(type))
            throw new IllegalStateException(
                    "Typed business timestamp required: " + table + "." + column);
        boolean nanos = "TIMESTAMP_NS".equals(type);
        var out = new TreeSet<LocalDate>();
        jdbc.query(
                "SELECT DISTINCT cast(\""
                        + column
                        + "\" AS LONG) AS d FROM \""
                        + table
                        + "\" WHERE \""
                        + column
                        + "\">=cast(? AS TIMESTAMP) AND \""
                        + column
                        + "\"<cast(? AS TIMESTAMP) ORDER BY d LIMIT 367",
                (RowCallbackHandler)
                        rs -> {
                            Object raw = rs.getObject(1);
                            if (!(raw instanceof Number n))
                                throw new IllegalStateException(
                                        "Business date is null or untyped: " + table);
                            out.add(nanos ? dateNanos(n.longValue()) : date(n.longValue()));
                        },
                micros(from),
                micros(to.plusDays(1)));
        return out;
    }

    private String marginLimitation(LocalDate from, LocalDate to) throws Exception {
        var history =
                jdbc.queryForList(
                        "SELECT cast(trade_date AS LONG) AS d,right(ts_code,2) AS exchange,count() AS n FROM margin_detail WHERE trade_date>=cast(? AS TIMESTAMP) AND trade_date<cast(? AS TIMESTAMP) GROUP BY trade_date,exchange ORDER BY trade_date LIMIT 201",
                        micros(from.minusDays(40)),
                        micros(to.plusDays(1)));
        var counts = new TreeMap<LocalDate, Map<String, Long>>();
        for (var row : history) {
            LocalDate d = date(((Number) row.get("d")).longValue());
            counts.computeIfAbsent(d, k -> new TreeMap<>())
                    .put(
                            Objects.toString(row.get("exchange")),
                            ((Number) row.get("n")).longValue());
        }
        var issues = new ArrayList<String>();
        for (var day : calendar(from, to)) {
            var observed = counts.getOrDefault(day, Map.of());
            var recent = new ArrayList<>(counts.headMap(day, false).values());
            if (recent.size() > 20)
                recent = new ArrayList<>(recent.subList(recent.size() - 20, recent.size()));
            var freq = new HashMap<String, Integer>();
            recent.forEach(m -> m.keySet().forEach(x -> freq.merge(x, 1, Integer::sum)));
            var expected =
                    freq.entrySet().stream()
                            .filter(e -> e.getValue() >= 3)
                            .map(Map.Entry::getKey)
                            .sorted()
                            .toList();
            var missing = expected.stream().filter(x -> !observed.containsKey(x)).toList();
            if (observed.isEmpty())
                issues.add(day + " margin unavailable; counts={}; expected=" + expected);
            else if (!missing.isEmpty())
                issues.add(
                        day
                                + " missing whole exchanges "
                                + missing
                                + "; counts="
                                + observed
                                + "; expected="
                                + expected);
        }
        return issues.isEmpty() ? null : String.join("; ", issues);
    }

    private List<Map<String, Object>> columns(String table) {
        DatasetDefinition.identifier(table);
        var cols =
                jdbc.queryForList(
                        "SELECT \"column\",\"type\" FROM table_columns('" + table + "') LIMIT 257");
        if (cols.isEmpty() || cols.size() > 256)
            throw new IllegalStateException("Bounded existing schema required: " + table);
        return cols;
    }

    private Map<String, Object> clock(String table) throws Gate {
        DatasetDefinition.identifier(table);
        var rows =
                jdbc.queryForList(
                        "SELECT t.id,t.directoryName,t.walEnabled,w.writerTxn,w.sequencerTxn,w.suspended,w.bufferedTxnSize FROM tables() t JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name=?",
                        table);
        if (rows.size() != 1)
            throw new Gate(BusinessState.WAITING_SOURCE, "Exact WAL table absent: " + table);
        var r = rows.getFirst();
        if (!Boolean.TRUE.equals(r.get("walEnabled"))
                || !Boolean.FALSE.equals(r.get("suspended"))
                || !(r.get("writerTxn") instanceof Number writer)
                || !(r.get("sequencerTxn") instanceof Number sequence)
                || writer.longValue() != sequence.longValue()
                || !(r.get("bufferedTxnSize") instanceof Number buffered)
                || buffered.longValue() != 0)
            throw new Gate(
                    BusinessState.WAITING_SOURCE, "Source WAL not physically settled: " + table);
        return new TreeMap<>(r);
    }

    private Gate planningGate(String stage, Exception error) {
        String message = Objects.toString(error.getMessage(), "");
        String low = message.toLowerCase(Locale.ROOT);
        boolean uncertain =
                low.contains("in_doubt")
                        || low.contains("pending publication")
                        || low.contains("uncertain")
                        || low.contains("journal");
        return new Gate(
                uncertain ? BusinessState.IN_DOUBT : BusinessState.WAITING_SOURCE,
                stage + " preflight: " + message);
    }

    private Window snapshot(
            String table, String date, List<String> keys, LocalDate from, LocalDate to)
            throws Exception {
        DatasetDefinition.identifier(table);
        DatasetDefinition.identifier(date);
        keys.forEach(DatasetDefinition::identifier);
        if (ChronoUnit.DAYS.between(from, to) < 0 || ChronoUnit.DAYS.between(from, to) > 30)
            throw new Gate(BusinessState.BLOCKED, "Snapshot must cover at most 31 natural days");
        var before = clock(table);
        var cols = columns(table);
        var names = cols.stream().map(c -> Objects.toString(c.get("column"))).toList();
        if (!names.contains(date) || !names.containsAll(keys))
            throw new Gate(BusinessState.BLOCKED, "Source schema/business key mismatch: " + table);
        var projection = new ArrayList<String>();
        for (var c : cols) {
            String name = Objects.toString(c.get("column"));
            DatasetDefinition.identifier(name);
            projection.add(
                    (Set.of("TIMESTAMP", "TIMESTAMP_NS").contains(Objects.toString(c.get("type")))
                                    ? "cast(\"" + name + "\" AS LONG)"
                                    : "\"" + name + "\"")
                            + " AS \""
                            + name
                            + "\"");
        }
        var hash = MessageDigest.getInstance("SHA-256");
        String schema = json.writeValueAsString(cols);
        hash.update(schema.getBytes(StandardCharsets.UTF_8));
        long[] n = {0};
        List<String>[] prior = new List[] {null};
        jdbc.query(
                "SELECT "
                        + String.join(",", projection)
                        + " FROM \""
                        + table
                        + "\" WHERE \""
                        + date
                        + "\">=cast(? AS TIMESTAMP) AND \""
                        + date
                        + "\"<cast(? AS TIMESTAMP) ORDER BY "
                        + keys.stream()
                                .map(k -> "\"" + k + "\"")
                                .reduce((a, b) -> a + "," + b)
                                .orElseThrow()
                        + " LIMIT "
                        + (MAX_WINDOW_ROWS + 1),
                (RowCallbackHandler)
                        rs -> {
                            if (++n[0] > MAX_WINDOW_ROWS)
                                throw new IllegalStateException(
                                        "Complete source window exceeds finite row budget");
                            var key = new ArrayList<String>();
                            for (String k : keys) {
                                Object v = rs.getObject(k);
                                if (v == null)
                                    throw new IllegalStateException(
                                            "Null business key: " + table + "." + k);
                                key.add(v.toString());
                            }
                            if (key.equals(prior[0]))
                                throw new IllegalStateException(
                                        "Duplicate business key: " + table + " " + key);
                            prior[0] = key;
                            for (int i = 1; i <= cols.size(); i++) {
                                Object v = rs.getObject(i);
                                if (v == null) {
                                    hash.update((byte) 0);
                                    continue;
                                }
                                hash.update((byte) 1);
                                if (v instanceof Double d) {
                                    if (Double.isInfinite(d))
                                        throw new IllegalStateException("Infinite source value");
                                    hash.update(
                                            ByteBuffer.allocate(8)
                                                    .putLong(Double.doubleToRawLongBits(d))
                                                    .array());
                                } else if (v instanceof Float f) {
                                    if (Float.isInfinite(f))
                                        throw new IllegalStateException("Infinite source value");
                                    hash.update(
                                            ByteBuffer.allocate(4)
                                                    .putInt(Float.floatToRawIntBits(f))
                                                    .array());
                                } else {
                                    byte[] b = v.toString().getBytes(StandardCharsets.UTF_8);
                                    hash.update(ByteBuffer.allocate(4).putInt(b.length).array());
                                    hash.update(b);
                                }
                            }
                        },
                micros(from),
                micros(to.plusDays(1)));
        if (!before.equals(clock(table)))
            throw new Gate(
                    BusinessState.WAITING_SOURCE,
                    "Source identity/frontier changed during complete read: " + table);
        return new Window(
                table, date, keys, from, to, n[0], HexFormat.of().formatHex(hash.digest()), schema);
    }

    private Path instance(RunRequest r) {
        if (!r.instanceId().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Exact instance identity required");
        return root.resolve(r.instanceId());
    }

    private Path source(RunRequest r, Owner owner, LocalDate from, LocalDate to) {
        return instance(r)
                .resolve("sources")
                .resolve(owner.table())
                .resolve(from + "_" + to)
                .resolve("receipt.json");
    }

    private Optional<Path> completedReceipt(RunRequest request, Owner owner, Path directory)
            throws Exception {
        if (!Files.isDirectory(directory)) return Optional.empty();
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files =
                    stream.filter(
                                    p ->
                                            Files.isRegularFile(p)
                                                    && p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
        }
        if (files.size() > 66)
            throw new Gate(BusinessState.BLOCKED, "Source attempt audit budget exceeded");
        var completed = new ArrayList<Path>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.startsWith("started")) {
                Path outcome = file.resolveSibling(name.replaceFirst("started", "receipt"));
                if (!Files.isRegularFile(outcome))
                    throw new Gate(
                            BusinessState.IN_DOUBT,
                            "Source intent has no durable outcome: " + file);
            } else if (name.startsWith("receipt")) {
                var saved = json.readValue(Files.readAllBytes(file), SourceReceipt.class);
                if (saved.state() == SyncRunState.IN_DOUBT || !saved.state().terminal())
                    throw new Gate(
                            BusinessState.IN_DOUBT, "Uncertain source outcome: " + saved.runId());
                if (saved.state() == SyncRunState.VERIFIED
                        || saved.state() == SyncRunState.VERIFIED_EMPTY) {
                    validateReceipt(request, owner, saved);
                    completed.add(file);
                }
            }
        }
        if (completed.size() > 1)
            throw new Gate(
                    BusinessState.BLOCKED,
                    "Ambiguous verified source attempts; explicit reconciliation required");
        return completed.stream().findFirst();
    }

    private void write(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try (var file =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) file.write(b);
            file.force(true);
        }
    }

    private static String fileHash(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            for (int n; (n = input.read(b)) >= 0; ) hash.update(b, 0, n);
            return HexFormat.of().formatHex(hash.digest());
        }
    }

    private static long micros(LocalDate d) {
        return Math.multiplyExact(d.toEpochDay(), 86_400_000_000L);
    }

    private static LocalDate date(long micros) {
        if (Math.floorMod(micros, 86_400_000_000L) != 0)
            throw new IllegalStateException("Business day must be UTC midnight");
        return LocalDate.ofEpochDay(Math.floorDiv(micros, 86_400_000_000L));
    }

    private static LocalDate dateNanos(long nanos) {
        if (Math.floorMod(nanos, 86_400_000_000_000L) != 0)
            throw new IllegalStateException("Nanosecond business day must be UTC midnight");
        return LocalDate.ofEpochDay(Math.floorDiv(nanos, 86_400_000_000_000L));
    }
}
