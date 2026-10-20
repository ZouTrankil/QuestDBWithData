package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.MarketId;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;
import com.zoutrankil.data.factor.storage.DailyMetricInputGuard;
import com.zoutrankil.data.factor.storage.QuestDbDailyEvaluationExposures;
import com.zoutrankil.data.factor.storage.QuestDbDailyEvaluationInputs;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Read-only database computation. Output is partial until source and matrix certification succeed. */
public final class DailyMetricComputation {
    private final MarketTime marketTime;
    private final JdbcTemplate jdbc;

    public DailyMetricComputation(JdbcTemplate jdbc) {
        this(jdbc, CnMarketTime.system());
    }

    /** 生产装配和边界测试可以明确传入 A 股时钟。 */
    public DailyMetricComputation(JdbcTemplate jdbc, MarketTime marketTime) {
        this.jdbc = jdbc;
        this.marketTime = Objects.requireNonNull(marketTime).requireMarket(MarketId.CN);
    }

    public record Request(
            LocalDate signalDate,
            LocalDate asOf,
            List<Path> observationEvidence,
            Path poolEvidence,
            String labelDefinition,
            Path output) {
        public Request {
            Objects.requireNonNull(signalDate);
            Objects.requireNonNull(asOf);
            observationEvidence = List.copyOf(observationEvidence);
            Objects.requireNonNull(poolEvidence);
            Objects.requireNonNull(labelDefinition);
            Objects.requireNonNull(output);
        }
    }

    public record Result(Path output, int rows, int missingKeys, boolean sourceComplete) {}

    public Result compute(Request request) throws Exception {
        Objects.requireNonNull(request);
        LocalDate date = request.signalDate(), asOf = request.asOf();
        if (asOf.isAfter(marketTime.today()))
            throw new IllegalArgumentException("Future evaluation date");
        String label = request.labelDefinition();
        if (!Set.of(DailyFactorLabels.LEGACY, DailyFactorLabels.SUSPENSION_LAST).contains(label))
            throw new IllegalArgumentException("Unknown daily label");
        Path output = request.output().toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("New output path required");
        var paths = request.observationEvidence();
        if (paths.isEmpty() || paths.size() > 32)
            throw new IllegalArgumentException("Expected 1..32 observation parts");
        var observations = new ArrayList<GlobalFactorObservation>();
        var hashes = new TreeMap<String, String>();
        for (Path value : paths) {
            var path = value.toAbsolutePath().normalize();
            var frozen = FactorObservationEvidence.read(path);
            if (hashes.putIfAbsent(path.toString(), frozen.fileSha256()) != null)
                throw new IllegalArgumentException("Repeated observation part");
            observations.addAll(frozen.rows());
            if (observations.size() > 2_000_000)
                throw new IllegalArgumentException("Observation row budget exceeded");
        }
        Path pools = request.poolEvidence().toAbsolutePath().normalize();
        var catalog = FactorDefinitionCatalog.load();
        var inputReader = new QuestDbDailyEvaluationInputs(jdbc);
        var exposureReader = new QuestDbDailyEvaluationExposures(jdbc);
        var inputs = inputReader.read(date, asOf);
        var exposures = exposureReader.read(date);
        var result =
                DailyMetricPipeline.compute(
                        pools,
                        date,
                        inputs.dates().maturity(),
                        label,
                        inputs.prices(),
                        inputs.suspensions(),
                        observations,
                        catalog,
                        exposures.values(),
                        "factor_preprocess_v1",
                        null);
        inputReader.requireUnchanged(inputs);
        exposureReader.requireUnchanged(exposures);
        for (var entry : hashes.entrySet())
            if (!FactorObservationEvidence.read(Path.of(entry.getKey()))
                    .fileSha256()
                    .equals(entry.getValue()))
                throw new IllegalStateException("Observation evidence changed during evaluation");
        if (!FactorPoolEvidence.read(pools).sha256().equals(result.plan().poolEvidenceSha256()))
            throw new IllegalStateException("Pool evidence changed during evaluation");
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "java.factor_daily_metric_computation.v1");
        report.put("signalDate", date);
        report.put("maturityDate", inputs.dates().maturity());
        report.put("sourceComplete", false);
        report.put("factorReady", false);
        report.put("observationEvidence", hashes);
        report.put("poolEvidenceSha256", result.plan().poolEvidenceSha256());
        report.put("priceSourceState", inputs.sourceState());
        report.put("exposureSourceState", exposures.sourceState());
        report.put("catalogSha256", catalog.sourceConfigSha256());
        report.put("calculationCodeSha256", DailyMetricInputGuard.code());
        report.put("poolEvidencePath", pools.toString());
        report.put("rows", result.rows());
        report.put("expected", result.plan().expected());
        report.put("missingKeys", result.missingKeys());
        report.put("unavailablePools", result.plan().unavailablePools());
        report.put("publicationReport", result.publicationReport(date));
        byte[] bytes = JobDefinitionJson.mapper().writeValueAsBytes(report);
        if (bytes.length > 32 * 1024 * 1024)
            throw new IllegalArgumentException("Metric output exceeds byte budget");
        Files.createDirectories(output.getParent());
        FileEvidenceStore.writeNewDurable(output, bytes);
        return new Result(output, result.rows().size(), result.missingKeys().size(), false);
    }
}
