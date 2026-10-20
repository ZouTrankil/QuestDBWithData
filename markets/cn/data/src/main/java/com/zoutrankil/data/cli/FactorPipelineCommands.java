package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.FactorObservationPublicationService;
import com.zoutrankil.data.factor.application.FactorPanelComputation;
import com.zoutrankil.data.factor.application.FactorPipelineService;
import com.zoutrankil.market.cn.time.CnMarketTime;

import io.questdb.client.QuestDB;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CLI presentation for the finite native factor pipeline. */
@Component
@ConditionalOnNotWebApplication
public final class FactorPipelineCommands implements CliCommandFamily {
    private final FactorPipelineService service;

    public FactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jdbc, questdb, ledgerPath, CnMarketTime.system());
    }

    @Autowired
    public FactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            MarketTime marketTime) {
        service =
                new FactorPipelineService(
                        new FactorPanelComputation(jdbc, marketTime),
                        new FactorObservationPublicationService(jdbc, questdb, Path.of(ledgerPath)),
                        marketTime);
    }

    /** 兼容已有的显式计算和发布适配器入口。 */
    public FactorPipelineCommands(
            FactorPanelCommands panels, FactorObservationCommands publication) {
        service =
                new FactorPipelineService(
                        new FactorPipelineService.Panel() {
                            public void compute(Map<String, String> options) throws Exception {
                                panels.execute("compute-factor-panel", options);
                            }

                            public void requireSourceStateUnchanged(
                                    List<Map<String, Object>> sources) {
                                panels.requireSourceStateUnchanged(sources);
                            }
                        },
                        publication::publishEvidence);
    }

    @Override
    public Set<String> commands() {
        return Set.of("run-factor-pipeline");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().equals(Set.of("--date", "--factors", "--pools", "--root")))
            throw new IllegalArgumentException(
                    "Expected --date DATE --factors ID,ID --pools ID,ID --root DIRECTORY");
        var result =
                service.run(
                        new FactorPipelineService.Request(
                                LocalDate.parse(options.get("--date")),
                                options.get("--factors"),
                                options.get("--pools"),
                                Path.of(options.get("--root"))));
        CliOutput.printJson(result.state(), CliOutput.Profile.JOB_DEFINITION, true);
        throw new IncompleteCommandException(
                "Factor bundle rows verified; source completeness remains partial: "
                        + result.receipt());
    }
}
