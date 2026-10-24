package com.zoutrankil.market.cn.observation.cli;

import com.zoutrankil.data.cli.CliCommandFamily;
import com.zoutrankil.data.cli.CliOutput;
import com.zoutrankil.data.cli.IncompleteCommandException;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner;
import com.zoutrankil.market.cn.observation.application.SnapshotEventSyncOwner;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** v2 命令显式选择范围替换；旧表切换必须明确声明，不猜当前版本。 */
public final class SnapshotEventCommands implements CliCommandFamily {
    private final List<SnapshotEventSyncOwner> owners;

    public SnapshotEventCommands(List<SnapshotEventSyncOwner> owners) {
        this.owners = List.copyOf(owners);
    }

    public Set<String> commands() {
        return Set.of(
                "plan-event-snapshot-v2",
                "check-event-snapshot-source-v2",
                "run-event-snapshot-v2",
                "reconcile-event-snapshot-v2",
                "resume-event-snapshot-v2");
    }

    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)) throw new IllegalArgumentException("未知事件快照命令");
        var owner =
                owners.stream()
                        .filter(o -> o.datasetId().equals(options.get("--dataset")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("需要明确回购或大宗数据集"));
        Object result;
        if (command.equals("resume-event-snapshot-v2")) {
            if (!options.keySet().equals(Set.of("--dataset", "--run")))
                throw new IllegalArgumentException("恢复只接受数据集及原运行编号");
            result = owner.resume(options.get("--run"));
        } else if (command.equals("reconcile-event-snapshot-v2")) {
            if (!options.keySet().equals(Set.of("--dataset", "--run", "--writer-stopped"))
                    || !"true".equals(options.get("--writer-stopped")))
                throw new IllegalArgumentException("恢复需明确原运行及原writer已停止");
            var snapshot = owner.reconcile(options.get("--run"), true);
            result =
                    Map.of(
                            "identity",
                            snapshot.identity(),
                            "rows",
                            snapshot.rows().size(),
                            "fingerprint",
                            snapshot.fingerprint());
        } else {
            if (!options.keySet()
                            .equals(
                                    Set.of(
                                            "--dataset",
                                            "--date",
                                            "--logical-date",
                                            "--migrate-legacy"))
                    || !Set.of("true", "false").contains(options.get("--migrate-legacy")))
                throw new IllegalArgumentException("需明确数据集、来源日、业务日和是否迁移旧表");
            var plan =
                    owner.plan(
                            LocalDate.parse(options.get("--date")),
                            LocalDate.parse(options.get("--logical-date")),
                            Boolean.parseBoolean(options.get("--migrate-legacy")));
            result =
                    switch (command) {
                        case "plan-event-snapshot-v2" -> plan;
                        case "check-event-snapshot-source-v2" -> owner.checkSource(plan);
                        default -> owner.run(plan);
                    };
        }
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result instanceof SyncJobRunner.Result run
                && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY)
                        .contains(run.state()))
            throw new IncompleteCommandException("快照未完成，保留原运行：" + run.runId());
    }
}
