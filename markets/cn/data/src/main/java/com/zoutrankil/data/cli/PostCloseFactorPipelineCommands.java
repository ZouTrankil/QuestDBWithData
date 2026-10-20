package com.zoutrankil.data.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.EtfFactorPanelComputation;
import com.zoutrankil.data.factor.application.EtfFactorPipelineService;
import com.zoutrankil.data.factor.application.FactorObservationPublicationService;
import com.zoutrankil.data.factor.application.FactorPanelComputation;
import com.zoutrankil.data.factor.application.FactorPipelineService;
import com.zoutrankil.data.factor.application.PostCloseFactorCertification;
import com.zoutrankil.data.factor.application.PostCloseFactorPipelineService;
import com.zoutrankil.data.factor.engine.EtfHistoryWindow;
import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
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

@Component
@ConditionalOnNotWebApplication
public final class PostCloseFactorPipelineCommands implements CliCommandFamily {
    private final PostCloseFactorPipelineService service;
    private final PostCloseFactorCertification certification;

    public PostCloseFactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this(jdbc, questdb, ledger, CnMarketTime.system());
    }

    @Autowired
    public PostCloseFactorPipelineCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger,
            MarketTime marketTime) {
        var publication = new FactorObservationPublicationService(jdbc, questdb, Path.of(ledger));
        service =
                new PostCloseFactorPipelineService(
                        new FactorPipelineService(
                                new FactorPanelComputation(jdbc, marketTime),
                                publication,
                                marketTime),
                        new EtfFactorPipelineService(
                                new EtfFactorPanelComputation(jdbc), publication, marketTime),
                        new QuestDbGlobalObservations(jdbc));
        certification =
                new PostCloseFactorCertification(
                        new QuestDbGlobalObservations(jdbc),
                        (stock, etf) -> {
                            new FactorPanelComputation(jdbc, marketTime)
                                    .requireSourceStateUnchanged(
                                            JobDefinitionJson.mapper()
                                                    .convertValue(
                                                            stock.required("sourceState"),
                                                            new TypeReference<
                                                                    List<
                                                                            Map<
                                                                                    String,
                                                                                    Object>>>() {}));
                            new EtfFactorPanelComputation(jdbc).requireSourceStateUnchanged(etf);
                        });
    }

    @Override
    public Set<String> commands() {
        return Set.of("run-post-close-factor-pipeline", "certify-post-close-factor-pipeline");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (command.equals("certify-post-close-factor-pipeline")) {
            if (!options.keySet()
                    .equals(
                            Set.of(
                                    "--receipt",
                                    "--source-approval",
                                    "--source-approval-sha256",
                                    "--output")))
                throw new IllegalArgumentException(
                        "认证需 --receipt --source-approval --source-approval-sha256 --output");
            var path =
                    certification.certify(
                            Path.of(options.get("--receipt")),
                            Path.of(options.get("--source-approval")),
                            options.get("--source-approval-sha256"),
                            Path.of(options.get("--output")));
            CliOutput.printJson(
                    Map.of("certificate", path.toString(), "factorReady", true),
                    CliOutput.Profile.JOB_DEFINITION,
                    true);
            return;
        }
        if (!commands().contains(command)
                || !options.keySet().containsAll(Set.of("--date", "--factors", "--pools", "--root"))
                || !Set.of(
                                "--date",
                                "--etf-history-from",
                                "--factors",
                                "--pools",
                                "--root",
                                "--source-approval",
                                "--source-approval-sha256")
                        .containsAll(options.keySet())
                || options.containsKey("--source-approval")
                        != options.containsKey("--source-approval-sha256"))
            throw new IllegalArgumentException(
                    "Expected --date DATE [--etf-history-from DATE] --factors ID,ID --pools ID,ID --root DIRECTORY");
        var date = LocalDate.parse(options.get("--date"));
        var history =
                options.containsKey("--etf-history-from")
                        ? LocalDate.parse(options.get("--etf-history-from"))
                        : EtfHistoryWindow.defaultStart(date);
        var result =
                service.run(
                        new PostCloseFactorPipelineService.Request(
                                LocalDate.parse(options.get("--date")),
                                history,
                                options.get("--factors"),
                                options.get("--pools"),
                                Path.of(options.get("--root"))));
        CliOutput.printJson(result.state(), CliOutput.Profile.JOB_DEFINITION, true);
        if (options.containsKey("--source-approval")) {
            var path =
                    certification.certify(
                            result.receipt(),
                            Path.of(options.get("--source-approval")),
                            options.get("--source-approval-sha256"),
                            result.receipt().getParent().resolve("factor-ready.json"));
            CliOutput.printJson(
                    Map.of("certificate", path.toString(), "factorReady", true),
                    CliOutput.Profile.JOB_DEFINITION,
                    true);
            return;
        }
        throw new IncompleteCommandException(
                "Stock and ETF physically verified; source completeness remains partial: "
                        + result.receipt());
    }
}
