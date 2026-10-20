package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.StockLimitRunRecovery;
import com.zoutrankil.data.stock.port.StockLimitTarget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnNotWebApplication
public final class StockLimitRecoveryCommands implements CliCommandFamily {
    private final StockLimitTarget target;
    private final Path ledger;

    public StockLimitRecoveryCommands(
            StockLimitTarget target,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledger) {
        this.target = target;
        this.ledger = Path.of(ledger);
    }

    public Set<String> commands() {
        return Set.of("reconcile-stk-limit-submitted-page");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().equals(Set.of("--run", "--writer-log")))
            throw new IllegalArgumentException("Expected --run RUN_ID --writer-log ORIGINAL_LOG");
        CliOutput.printJson(
                StockLimitRunRecovery.finish(
                        ledger, options.get("--run"), Path.of(options.get("--writer-log")), target),
                CliOutput.Profile.JOB_DEFINITION,
                true);
    }
}
