package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.MainStrategyReferencePreparation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/** 有限收尾入口只核对已有原运行，不启动日批或重发数据。 */
@Component
@ConditionalOnNotWebApplication
public final class MainStrategyReferenceCommands implements CliCommandFamily {
    private final ObjectProvider<MainStrategyReferencePreparation> preparation;

    public MainStrategyReferenceCommands(
            ObjectProvider<MainStrategyReferencePreparation> preparation) {
        this.preparation = preparation;
    }

    @Override
    public Set<String> commands() {
        return Set.of("reconcile-main-strategy-reference");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command)
                || !options.keySet().equals(Set.of("--date", "--table", "--writer-stopped"))
                || !"true".equals(options.get("--writer-stopped")))
            throw new IllegalArgumentException("需要原观察日、资料表和明确停止writer的证据");
        CliOutput.printJson(
                preparation
                        .getObject()
                        .reconcile(
                                LocalDate.parse(options.get("--date")),
                                options.get("--table"),
                                true),
                CliOutput.Profile.JOB_DEFINITION,
                true);
    }
}
