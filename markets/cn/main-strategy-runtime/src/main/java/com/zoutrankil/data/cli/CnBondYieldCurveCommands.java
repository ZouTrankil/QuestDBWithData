package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.CnBondYieldCurveJobService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 显式调用有界的正式执行入口，与收盘后流程使用同一服务。 */
@Component
@ConditionalOnNotWebApplication
public final class CnBondYieldCurveCommands implements CliCommandFamily {
    private final CnBondYieldCurveJobService service;

    public CnBondYieldCurveCommands(CnBondYieldCurveJobService service) {
        this.service = service;
    }

    public Set<String> commands() {
        return Set.of("plan-cn-bond-yield-curve-job", "run-cn-bond-yield-curve-job");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().equals(Set.of("--from", "--to", "--logical-date")))
            throw new IllegalArgumentException(
                    "Explicit --from DATE --to DATE --logical-date DATE required");
        var plan =
                service.plan(
                        LocalDate.parse(options.get("--from")),
                        LocalDate.parse(options.get("--to")),
                        LocalDate.parse(options.get("--logical-date")));
        if (command.startsWith("plan-")) {
            CliOutput.printJson(plan, CliOutput.Profile.JOB_DEFINITION, true);
            return;
        }
        var result = service.run(plan);
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result.errorCode() != null
                || !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(result.state()))
            throw new IncompleteCommandException(
                    "ChinaBond source not verified: " + result.state() + "; run=" + result.runId());
    }
}
