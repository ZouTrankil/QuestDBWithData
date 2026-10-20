package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.EtfFactorPanelComputation;
import com.zoutrankil.data.factor.application.EtfFactorPipelineService;
import com.zoutrankil.data.factor.application.FactorObservationPublicationService;
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
import java.util.Map;
import java.util.Set;

/** CLI presentation for the native ETF pipeline and explicit recovery. */
@Component
@ConditionalOnNotWebApplication
public final class EtfFactorPipelineCommands implements CliCommandFamily {
    private final EtfFactorPipelineService service;

    public EtfFactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this(jdbc, questdb, ledgerPath, CnMarketTime.system());
    }

    @Autowired
    public EtfFactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath,
            MarketTime marketTime) {
        service =
                new EtfFactorPipelineService(
                        new EtfFactorPanelComputation(jdbc),
                        new FactorObservationPublicationService(jdbc, questdb, Path.of(ledgerPath)),
                        marketTime);
    }

    @Override
    public Set<String> commands() {
        return Set.of("run-etf-factor-pipeline", "resume-etf-factor-pipeline");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        EtfFactorPipelineService.Result result;
        if (command.equals("resume-etf-factor-pipeline")) {
            if (!options.keySet().equals(Set.of("--run")))
                throw new IllegalArgumentException("Expected --run DIRECTORY");
            result = service.resume(Path.of(options.get("--run")));
        } else {
            if (!command.equals("run-etf-factor-pipeline")
                    || !options.keySet().equals(Set.of("--date", "--history-from", "--root")))
                throw new IllegalArgumentException(
                        "Expected --date DATE --history-from DATE --root DIRECTORY");
            result =
                    service.run(
                            new EtfFactorPipelineService.Request(
                                    LocalDate.parse(options.get("--date")),
                                    LocalDate.parse(options.get("--history-from")),
                                    Path.of(options.get("--root"))));
        }
        CliOutput.printJson(result.state(), CliOutput.Profile.PLAIN, false);
        if (!result.observationsVerified())
            throw new IncompleteCommandException(
                    "ETF publication stopped: "
                            + result.state().get("phase")
                            + "; receipt="
                            + result.receipt());
        throw new IncompleteCommandException(
                "ETF publication state="
                        + result.state().get("phase")
                        + "; source completeness remains partial; receipt="
                        + result.receipt());
    }
}
