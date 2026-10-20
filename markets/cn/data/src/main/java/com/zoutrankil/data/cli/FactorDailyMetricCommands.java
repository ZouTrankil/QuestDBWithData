package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.DailyMetricComputation;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** CLI presentation for the shared typed daily metric computation. */
@Component
@ConditionalOnNotWebApplication
public final class FactorDailyMetricCommands implements CliCommandFamily {
    private final DailyMetricComputation computation;

    public FactorDailyMetricCommands(JdbcTemplate jdbc) {
        this(jdbc, CnMarketTime.system());
    }

    @Autowired
    public FactorDailyMetricCommands(JdbcTemplate jdbc, MarketTime marketTime) {
        computation = new DailyMetricComputation(jdbc, marketTime);
    }

    @Override
    public Set<String> commands() {
        return Set.of("compute-factor-daily-metrics");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet()
                        .equals(
                                Set.of(
                                        "--date",
                                        "--as-of",
                                        "--observations",
                                        "--pools",
                                        "--label",
                                        "--output")))
            throw new IllegalArgumentException(
                    "Expected --date DATE --as-of DATE --observations PATH,PATH --pools PATH --label DEFINITION --output NEW_PATH");
        var paths = new ArrayList<Path>();
        for (String path : options.get("--observations").split(",", -1)) {
            if (path.isBlank()) throw new IllegalArgumentException("Blank observation path");
            paths.add(Path.of(path));
        }
        var result =
                computation.compute(
                        new DailyMetricComputation.Request(
                                LocalDate.parse(options.get("--date")),
                                LocalDate.parse(options.get("--as-of")),
                                paths,
                                Path.of(options.get("--pools")),
                                options.get("--label"),
                                Path.of(options.get("--output"))));
        CliOutput.printJson(
                Map.of(
                        "output",
                        result.output().toString(),
                        "rows",
                        result.rows(),
                        "missingKeys",
                        result.missingKeys(),
                        "sourceComplete",
                        result.sourceComplete()),
                CliOutput.Profile.PLAIN,
                false);
    }
}
