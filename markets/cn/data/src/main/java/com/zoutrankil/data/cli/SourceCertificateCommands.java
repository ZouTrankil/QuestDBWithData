package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.VerifiedSourceCertificates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnNotWebApplication
public final class SourceCertificateCommands implements CliCommandFamily {
    private final Path ledger;

    public SourceCertificateCommands(
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.ledger = Path.of(ledger);
    }

    public Set<String> commands() {
        return Set.of("export-source-certificate");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command) || !options.keySet().equals(Set.of("--run", "--output")))
            throw new IllegalArgumentException("需 --run RUN_ID --output NEW_JSON");
        var output =
                VerifiedSourceCertificates.publish(
                        ledger, options.get("--run"), Path.of(options.get("--output")));
        CliOutput.printJson(
                Map.of("certificate", output.toString()), CliOutput.Profile.JOB_DEFINITION, true);
    }
}
