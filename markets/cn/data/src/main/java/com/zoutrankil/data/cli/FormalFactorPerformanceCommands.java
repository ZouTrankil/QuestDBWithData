package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.engine.FactorPerformanceWindows;
import com.zoutrankil.data.factor.storage.QuestDbDailyMetrics;
import com.zoutrankil.data.repository.FileEvidenceStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnNotWebApplication
public final class FormalFactorPerformanceCommands implements CliCommandFamily {
    private final QuestDbDailyMetrics reader;

    public FormalFactorPerformanceCommands(JdbcTemplate jdbc) {
        reader = new QuestDbDailyMetrics(jdbc);
    }

    @Override
    public Set<String> commands() {
        return Set.of("compute-formal-factor-performance-windows");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().containsAll(Set.of("--from", "--as-of", "--label", "--output"))
                || !Set.of("--from", "--as-of", "--label", "--output", "--factors", "--rows-output")
                        .containsAll(options.keySet()))
            throw new IllegalArgumentException(
                    "Expected --from DATE --as-of DATE --label DEFINITION --output NEW_PATH [--factors ID,ID] [--rows-output NEW_PATH]");
        var asOf = LocalDate.parse(options.get("--as-of"));
        var history =
                reader.readRange(
                        LocalDate.parse(options.get("--from")),
                        asOf,
                        1_000_000,
                        options.get("--label"));
        Set<String> requested =
                options.containsKey("--factors")
                        ? Set.of(options.get("--factors").split(",", -1))
                        : Set.of();
        if (requested.contains(""))
            throw new IllegalArgumentException("Nonblank requested factors required");
        var rows =
                history.rows().stream()
                        .filter(
                                row ->
                                        (requested.isEmpty() || requested.contains(row.factorId()))
                                                && options.get("--label")
                                                        .equals(row.labelDefinition())
                                                && Integer.valueOf(1).equals(row.horizon())
                                                && "base_ic".equals(row.evaluationProfile()))
                        .toList();
        var catalog = FactorDefinitionCatalog.load();
        var directions = new HashMap<FactorPerformanceWindows.Definition, String>();
        for (var definition : catalog.definitions())
            directions.put(
                    new FactorPerformanceWindows.Definition(
                            definition.factorId(), definition.version()),
                    definition.direction());
        var windows = FactorPerformanceWindows.compute(rows, asOf, directions);
        var json = JobDefinitionJson.mapper();
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "java.formal_factor_performance_windows.v1");
        result.put("from", history.from());
        result.put("asOf", asOf);
        result.put("sourceState", history.sourceState());
        result.put("sourceRows", history.rows().size());
        result.put("selectedRows", rows.size());
        byte[] frozenRows = json.writeValueAsBytes(rows);
        result.put("inputSha256", FileEvidenceStore.sha256(frozenRows));
        result.put("catalogSha256", catalog.sourceConfigSha256());
        result.put("requestedFactors", requested);
        result.put("excludedRows", history.rows().size() - rows.size());
        if (options.containsKey("--rows-output")) {
            Path frozenPath = Path.of(options.get("--rows-output")).toAbsolutePath().normalize();
            FileEvidenceStore.writeNewDurable(frozenPath, frozenRows);
            result.put("frozenRows", frozenPath.toString());
        }
        result.put("windows", windows);
        result.put("sourceComplete", false);
        result.put("pageSnapshotRefreshed", false);
        var path = Path.of(options.get("--output")).toAbsolutePath().normalize();
        FileEvidenceStore.writeNewDurable(path, json.writeValueAsBytes(result));
        CliOutput.printJson(
                Map.of(
                        "output",
                        path.toString(),
                        "sourceRows",
                        history.rows().size(),
                        "selectedRows",
                        rows.size(),
                        "windowRows",
                        windows.size(),
                        "completeWindows",
                        windows.stream()
                                .filter(FactorPerformanceWindows.Window::completeWindow)
                                .count()),
                CliOutput.Profile.PLAIN,
                false);
    }
}
