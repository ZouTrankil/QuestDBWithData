package com.zoutrankil.market.cn.observation.cli;

import com.zoutrankil.data.cli.CliCommandFamily;
import com.zoutrankil.data.cli.CliOutput;
import com.zoutrankil.market.cn.observation.storage.ContractKeyRepairPlan;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

/** 修复计划只读实库，不访问来源，也不创建正式同步记录。 */
public final class ContractKeyRepairCommands implements CliCommandFamily {
    private final ContractKeyRepairPlan planner;

    public ContractKeyRepairCommands(JdbcTemplate jdbc) {
        planner = new ContractKeyRepairPlan(jdbc);
    }

    public Set<String> commands() {
        return Set.of("plan-contract-table-key-repair");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command) || !options.keySet().equals(Set.of("--dataset")))
            throw new IllegalArgumentException("旧键计划只接受明确数据集");
        CliOutput.printJson(
                planner.inspect(options.get("--dataset")), CliOutput.Profile.JOB_DEFINITION, true);
    }
}
