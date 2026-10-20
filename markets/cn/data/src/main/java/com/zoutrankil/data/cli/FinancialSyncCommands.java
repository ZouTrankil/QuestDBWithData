package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.financial.application.FinancialSyncOwner;
import com.zoutrankil.data.service.SyncJobRunner.Result;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnNotWebApplication
public final class FinancialSyncCommands implements CliCommandFamily {
    private final List<FinancialSyncOwner> owners;

    public FinancialSyncCommands(List<FinancialSyncOwner> owners) {
        this.owners = List.copyOf(owners);
    }

    public Set<String> commands() {
        return Set.of("plan-financial-sync", "run-financial-sync", "resume-financial-sync");
    }

    public void execute(String command, Map<String, String> o) throws Exception {
        if (!commands().contains(command) || !o.containsKey("--dataset"))
            throw new IllegalArgumentException("需 --dataset");
        var owner =
                owners.stream()
                        .filter(x -> x.datasetId().equals(o.get("--dataset")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("未知财务源"));
        Object result;
        if (command.equals("resume-financial-sync")) {
            if (!o.keySet().equals(Set.of("--dataset", "--run")))
                throw new IllegalArgumentException("恢复需 --dataset --run");
            result = owner.resume(o.get("--run"));
        } else {
            if (!o.keySet()
                            .containsAll(
                                    Set.of("--dataset", "--codes", "--periods", "--logical-date"))
                    || !Set.of("--dataset", "--codes", "--periods", "--logical-date", "--mode")
                            .containsAll(o.keySet()))
                throw new IllegalArgumentException(
                        "需 --dataset --codes --periods --logical-date，可选 --mode");
            var plan =
                    owner.plan(
                            List.of(o.get("--codes").split(",", -1)),
                            Arrays.stream(o.get("--periods").split(",", -1))
                                    .map(LocalDate::parse)
                                    .toList(),
                            LocalDate.parse(o.get("--logical-date")),
                            Mode.valueOf(
                                    o.getOrDefault("--mode", "INCREMENTAL")
                                            .toUpperCase(Locale.ROOT)));
            result = command.equals("plan-financial-sync") ? plan : owner.run(plan);
        }
        CliOutput.printJson(result, CliOutput.Profile.JOB_DEFINITION, true);
        if (result instanceof Result r
                && !Set.of(SyncRunState.VERIFIED, SyncRunState.VERIFIED_EMPTY).contains(r.state()))
            throw new IncompleteCommandException("财务同步未完成：" + r.state());
    }
}
