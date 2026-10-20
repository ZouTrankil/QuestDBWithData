package com.zoutrankil.data.cli;

import com.zoutrankil.data.factor.application.FactorObservationPublicationService;
import com.zoutrankil.data.factor.application.L2ObservationPublicationService;

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

/** CLI presentation for native L2 bundle inspection and incremental publication. */
@Component
@ConditionalOnNotWebApplication
public final class L2ObservationBundleCommands implements CliCommandFamily {
    private final L2ObservationPublicationService service;

    @Autowired
    public L2ObservationBundleCommands(
            JdbcTemplate jdbc,
            @Lazy QuestDB questdb,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        var path = Path.of(ledger);
        service =
                new L2ObservationPublicationService(
                        new FactorObservationPublicationService(jdbc, questdb, path), path);
    }

    public L2ObservationBundleCommands(FactorObservationCommands publication, String ledger) {
        service =
                new L2ObservationPublicationService(publication::publishEvidence, Path.of(ledger));
    }

    @Override
    public Set<String> commands() {
        return Set.of("run-l2-observation-bundle", "inspect-l2-observation-bundle");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (command.equals("inspect-l2-observation-bundle")) {
            if (!options.keySet().equals(Set.of("--bundle")))
                throw new IllegalArgumentException("Expected --bundle DIRECTORY");
            CliOutput.printJson(
                    L2ObservationPublicationService.inspect(Path.of(options.get("--bundle"))),
                    CliOutput.Profile.JOB_DEFINITION,
                    true);
            return;
        }
        if (!command.equals("run-l2-observation-bundle")
                || !options.keySet().equals(Set.of("--bundle", "--logical-date", "--root")))
            throw new IllegalArgumentException(
                    "Expected --bundle DIRECTORY --logical-date DATE --root DIRECTORY");
        var result =
                service.run(
                        new L2ObservationPublicationService.Request(
                                Path.of(options.get("--bundle")),
                                LocalDate.parse(options.get("--logical-date")),
                                Path.of(options.get("--root"))));
        CliOutput.printJson(result.state(), CliOutput.Profile.JOB_DEFINITION, true);
        throw new IncompleteCommandException(
                "L2 bundle rows verified; source completeness remains partial: "
                        + result.receipt());
    }
}
