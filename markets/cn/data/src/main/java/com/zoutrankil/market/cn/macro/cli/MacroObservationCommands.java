package com.zoutrankil.market.cn.macro.cli;

import com.zoutrankil.data.cli.CliCommandFamily;
import com.zoutrankil.data.cli.CliOutput;
import com.zoutrankil.data.cli.IncompleteCommandException;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner.Result;
import com.zoutrankil.market.cn.macro.application.MacroObservationSyncOwner;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 有限 CLI 只传明确统计期，不启动日更或批处理调度。 */
public final class MacroObservationCommands implements CliCommandFamily {
    private final List<MacroObservationSyncOwner> owners;

    public MacroObservationCommands(List<MacroObservationSyncOwner> owners) {
        this.owners = List.copyOf(owners);
    }

    public Set<String> commands() {
        return Set.of(
                "plan-macro-observations", "run-macro-observations", "resume-macro-observations");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command) || !options.containsKey("--dataset"))
            throw new IllegalArgumentException("需要明确宏观数据集");
        var owner =
                owners.stream()
                        .filter(item -> item.datasetId().equals(options.get("--dataset")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未知宏观数据集"));
        Object result;
        if (command.equals("resume-macro-observations")) {
            if (!options.keySet().equals(Set.of("--dataset", "--run")))
                throw new IllegalArgumentException("恢复只接受 --dataset 和原 --run");
            result = owner.resume(options.get("--run"));
        } else {
            if (!options.keySet()
                            .containsAll(Set.of("--dataset", "--from", "--to", "--logical-date"))
                    || !Set.of("--dataset", "--from", "--to", "--logical-date", "--mode")
                            .containsAll(options.keySet()))
                throw new IllegalArgumentException(
                        "需 --dataset --from --to --logical-date，可选 --mode");
            var request =
                    owner.plan(
                            LocalDate.parse(options.get("--from")),
                            LocalDate.parse(options.get("--to")),
                            LocalDate.parse(options.get("--logical-date")),
                            Mode.valueOf(
                                    options.getOrDefault("--mode", "INCREMENTAL")
                                            .toUpperCase(Locale.ROOT)));
            result = command.equals("plan-macro-observations") ? request : owner.run(request);
        }
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result instanceof Result run
                && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state()))
            throw new IncompleteCommandException("宏观采集未完成，保留原运行：" + run.runId());
    }
}
