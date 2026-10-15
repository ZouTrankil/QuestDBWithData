package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.DailyJobService;
import com.zoutrankil.data.stock.application.DailyBasicJobService;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

/** Fresh source fetch and exact physical readback; no database publication. */
@Component
@ConditionalOnNotWebApplication
public final class DailySourceRevalidationCommands implements CliCommandFamily {
    private final DailyJobService daily;
    private final DailyBasicJobService basic;
    private final com.zoutrankil.data.etf.application.EtfDailyJobService etfDaily;
    private final com.zoutrankil.data.etf.application.EtfFactorJobService etfFactor;
    private final com.zoutrankil.data.stock.application.StockLimitJobService limits;
    public DailySourceRevalidationCommands(DailyJobService daily,DailyBasicJobService basic,
            com.zoutrankil.data.etf.application.EtfDailyJobService etfDaily,
            com.zoutrankil.data.etf.application.EtfFactorJobService etfFactor,
            com.zoutrankil.data.stock.application.StockLimitJobService limits) {
        this.daily=daily;this.basic=basic;this.etfDaily=etfDaily;this.etfFactor=etfFactor;this.limits=limits;
    }
    @Override public Set<String> commands(){return Set.of("revalidate-daily-source","revalidate-factor-source");}
    @Override public void execute(String command,Map<String,String> options)throws Exception {
        if(!commands().contains(command) || !options.keySet().equals(Set.of("--dataset","--run")))
            throw new IllegalArgumentException("Expected --dataset DATASET --run RUN_ID");
        if(command.equals("revalidate-daily-source") && !Set.of("daily","daily_basic").contains(options.get("--dataset")))
            throw new IllegalArgumentException("Expected daily or daily_basic");
        String receipt=switch(options.get("--dataset")) {
            case "daily" -> daily.revalidate(options.get("--run"));
            case "daily_basic" -> basic.revalidate(options.get("--run"));
            case "etf_daily" -> etfDaily.revalidate(options.get("--run"));
            case "etf_factor" -> etfFactor.revalidate(options.get("--run"));
            case "stk_limit" -> limits.revalidate(options.get("--run"));
            default -> throw new IllegalArgumentException("Unsupported source revalidation dataset");
        };
        CliOutput.printJson(Map.of("dataset",options.get("--dataset"),"priorRunId",options.get("--run"),
                "receipt",receipt,"passed",true,"databaseRowsWritten",0),CliOutput.Profile.JOB_DEFINITION,true);
    }
}
