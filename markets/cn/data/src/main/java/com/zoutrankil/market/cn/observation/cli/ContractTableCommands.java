package com.zoutrankil.market.cn.observation.cli;

import com.zoutrankil.data.cli.CliCommandFamily;
import com.zoutrankil.data.cli.CliOutput;
import com.zoutrankil.data.cli.IncompleteCommandException;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner.Result;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 有限单范围标识入口；来源检查、真实写入和原运行恢复各用明确命令。 */
public final class ContractTableCommands implements CliCommandFamily {
    private final List<ContractTableSyncOwner> owners;

    public ContractTableCommands(List<ContractTableSyncOwner> owners) {
        this.owners = List.copyOf(owners);
    }

    public Set<String> commands() {
        return Set.of(
                "plan-contract-table",
                "check-contract-table-source",
                "run-contract-table",
                "resume-contract-table");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command) || !options.containsKey("--dataset"))
            throw new IllegalArgumentException("需要明确契约表数据集");
        var owner =
                owners.stream()
                        .filter(o -> o.datasetId().equals(options.get("--dataset")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未知契约表数据集"));
        Object result;
        if (command.equals("resume-contract-table")) {
            if (!options.keySet().equals(Set.of("--dataset", "--run"))
                    || options.get("--run").isBlank())
                throw new IllegalArgumentException("恢复只接受数据集和原运行编号");
            result = owner.resume(options.get("--run"));
        } else {
            if (!options.keySet()
                            .containsAll(
                                    Set.of(
                                            "--dataset",
                                            "--code",
                                            "--from",
                                            "--to",
                                            "--logical-date"))
                    || !Set.of("--dataset", "--code", "--from", "--to", "--logical-date", "--mode")
                            .containsAll(options.keySet()))
                throw new IllegalArgumentException("需明确数据集、范围标识、起止日及业务日");
            var plan =
                    owner.plan(
                            options.get("--code"),
                            LocalDate.parse(options.get("--from")),
                            LocalDate.parse(options.get("--to")),
                            LocalDate.parse(options.get("--logical-date")),
                            Mode.valueOf(
                                    options.getOrDefault("--mode", "INCREMENTAL")
                                            .toUpperCase(Locale.ROOT)));
            result =
                    switch (command) {
                        case "plan-contract-table" -> plan;
                        case "check-contract-table-source" -> owner.checkSource(plan);
                        default -> owner.run(plan);
                    };
        }
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result instanceof Result run
                && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state()))
            throw new IncompleteCommandException("契约表运行未完成，保留原运行：" + run.runId());
    }
}
