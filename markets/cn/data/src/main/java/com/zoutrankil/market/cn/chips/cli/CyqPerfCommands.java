package com.zoutrankil.market.cn.chips.cli;

import com.zoutrankil.data.cli.CliCommandFamily;
import com.zoutrankil.data.cli.CliOutput;
import com.zoutrankil.data.cli.IncompleteCommandException;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner.Result;
import com.zoutrankil.market.cn.chips.application.CyqPerfSyncOwner;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 明确区分计划、来源只读检查、正式写入和原运行恢复，不附带调度。 */
public final class CyqPerfCommands implements CliCommandFamily {
    private final CyqPerfSyncOwner owner;

    public CyqPerfCommands(CyqPerfSyncOwner owner) {
        this.owner = owner;
    }

    public Set<String> commands() {
        return Set.of("plan-cyq-perf", "check-cyq-perf-source", "run-cyq-perf", "resume-cyq-perf");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)) throw new IllegalArgumentException("未知筹码命令");
        Object result;
        if (command.equals("resume-cyq-perf")) {
            if (!options.keySet().equals(Set.of("--run")) || options.get("--run").isBlank())
                throw new IllegalArgumentException("筹码恢复只接受原 --run");
            result = owner.resume(options.get("--run"));
        } else {
            if (!options.keySet().containsAll(Set.of("--codes", "--date", "--logical-date"))
                    || !Set.of("--codes", "--date", "--logical-date", "--mode")
                            .containsAll(options.keySet()))
                throw new IllegalArgumentException("筹码命令需 --codes --date --logical-date，可选 --mode");
            var request =
                    owner.plan(
                            Arrays.asList(options.get("--codes").split(",", -1)),
                            LocalDate.parse(options.get("--date")),
                            LocalDate.parse(options.get("--logical-date")),
                            Mode.valueOf(
                                    options.getOrDefault("--mode", "INCREMENTAL")
                                            .toUpperCase(Locale.ROOT)));
            result =
                    switch (command) {
                        case "plan-cyq-perf" -> request;
                        case "check-cyq-perf-source" -> owner.checkSource(request);
                        default -> owner.run(request);
                    };
        }
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result instanceof Result run
                && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state()))
            throw new IncompleteCommandException("筹码同步未完成，保留原运行：" + run.runId());
    }
}
