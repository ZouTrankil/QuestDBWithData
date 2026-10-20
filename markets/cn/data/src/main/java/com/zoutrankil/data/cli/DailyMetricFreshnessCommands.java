package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.application.DailyMetricFreshness;
import com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness;
import com.zoutrankil.data.factor.storage.QuestDbDailyMetricFreshness.Request;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline policy inspection; input rows do not certify current physical database visibility. */
@Component
@ConditionalOnNotWebApplication
public final class DailyMetricFreshnessCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;

    public DailyMetricFreshnessCommands(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Input(
            List<FactorDailyMetricRow> rows,
            List<DailyMetricFreshness.Key> expected,
            LocalDate asOf,
            LocalDate windowEnd,
            LocalDate calendarAsOf,
            List<LocalDate> openDates) {}

    @Override
    public Set<String> commands() {
        return Set.of(
                "inspect-factor-daily-metric-freshness",
                "inspect-formal-factor-daily-metric-freshness");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command) || !options.keySet().equals(Set.of("--input")))
            throw new IllegalArgumentException(
                    "Expected --input FILE with rows, expected identities and calendar");
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        Path.of(options.get("--input")),
                        32 * 1024 * 1024,
                        () ->
                                new IllegalArgumentException(
                                        "Metric freshness input exceeds 32 MiB"));
        if (command.equals("inspect-formal-factor-daily-metric-freshness")) {
            var request = JobDefinitionJson.mapper().readValue(bytes, Request.class);
            var result = new QuestDbDailyMetricFreshness(jdbc).inspect(request);
            CliOutput.printJson(
                    Map.of(
                            "schema",
                            "java.formal_factor_daily_metric_freshness.v1",
                            "inputSha256",
                            FileEvidenceStore.sha256(bytes),
                            "databaseRowsWritten",
                            0,
                            "factorReady",
                            false,
                            "inspection",
                            result),
                    CliOutput.Profile.JOB_DEFINITION,
                    true);
            return;
        }
        var input = JobDefinitionJson.mapper().readValue(bytes, Input.class);
        var result =
                DailyMetricFreshness.assess(
                        input.rows(),
                        input.expected(),
                        input.asOf(),
                        input.windowEnd(),
                        input.calendarAsOf(),
                        input.openDates());
        CliOutput.printJson(
                Map.of(
                        "schema",
                        "java.factor_daily_metric_freshness.v1",
                        "inputSha256",
                        FileEvidenceStore.sha256(bytes),
                        "physicalVisibilityVerified",
                        false,
                        "factorReady",
                        false,
                        "assessment",
                        result),
                CliOutput.Profile.JOB_DEFINITION,
                true);
    }
}
