package com.zoutrankil.data.cli;

import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 只读检查已配置的正式产物，不启动 Web 调度器。 */
@Component
@ConditionalOnNotWebApplication
public final class MainStrategyDataInspectionCommands implements CliCommandFamily {
    private final ObjectProvider<MainStrategyDataService> data;
    private final ObjectProvider<com.zoutrankil.batch.job.MainStrategyDailyWork> pipeline;

    public MainStrategyDataInspectionCommands(ObjectProvider<MainStrategyDataService> data,
            ObjectProvider<com.zoutrankil.batch.job.MainStrategyDailyWork> pipeline) {
        this.data=data;this.pipeline=pipeline;
    }

    @Override public Set<String> commands() { return Set.of("inspect-main-strategy-data"); }

    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(!commands().contains(command)||!options.isEmpty())
            throw new IllegalArgumentException("Expected inspect-main-strategy-data without options");
        CliOutput.printJson(Map.of("readOnly",true,"inspectionOnly",true,"runtimeScope","current-cli-process",
                "nativePipeline",pipeline.getObject().status(),"data",data.getObject().status()),
                CliOutput.Profile.JOB_DEFINITION,true);
    }
}
