package com.zoutrankil.data.strategy.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Finite native data commands, without starting the scheduler or trading engine. */
public final class MainStrategyDataCli {
    private MainStrategyDataCli() {}
    public static void main(String[] args)throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if(args.length==0)throw new IllegalArgumentException("Command required: status|produce-scores|scores|import-plan|inspect-plans");
        var options=new LinkedHashMap<String,String>();
        Set<String> allowed=Set.of("target","source","source-sha256","score-run","research-root","recipe","recipe-pins","artifact-root","plan-db","start","row-budget");
        for(int i=1;i<args.length;i+=2){if(!args[i].startsWith("--")||i+1>=args.length||!allowed.contains(args[i].substring(2))||options.put(args[i].substring(2),args[i+1])!=null)throw new IllegalArgumentException("Unknown, duplicate or incomplete data command option");}
        String host=env("APP_QUESTDB_HOST","127.0.0.1"),port=env("APP_QUESTDB_PG_PORT","8812");
        if(!host.matches("[A-Za-z0-9.-]+")||!port.matches("[0-9]{1,5}"))throw new IllegalArgumentException("Canonical QuestDB endpoint required");
        var source=new DriverManagerDataSource("jdbc:postgresql://"+host+":"+port+"/qdb?sslmode=disable",env("APP_QUESTDB_USERNAME","admin"),env("APP_QUESTDB_PASSWORD","quest"));
        String artifacts="F:/repo/engine/back-monitor/artifacts/";
        var service=new MainStrategyDataService(new JdbcTemplate(source),options.getOrDefault("research-root",artifacts+"quant_platform"),options.getOrDefault("recipe",artifacts+"factor_research/l2/etf_only_top2_promoted_recipe/promoted_recipe.json"),options.getOrDefault("recipe-pins",artifacts+"factor_research/l2/etf_only_top2_promoted_recipe/accepted-sha256.json"),options.getOrDefault("artifact-root","F:/repo/engine/back-monitor"),options.getOrDefault("plan-db",artifacts+"factor_research/l2/etf_only_fusion_main_daily/etf_only_paper/main_strategy_plans.sqlite3"),options.getOrDefault("start","2025-07-01"),Long.parseLong(options.getOrDefault("row-budget","5000000")));
        Object result=switch(args[0]) {
            case "status"->service.status();
            case "produce-scores"->{LocalDate target=LocalDate.parse(required(options,"target"));yield service.prepareScores(target,"java_scores_"+target+"_"+UUID.randomUUID());}
            case "scores"->service.scores(LocalDate.parse(required(options,"target")));
            case "import-plan"->service.importPlan(Path.of(required(options,"source")),required(options,"source-sha256"),options.get("score-run"));
            case "inspect-plans"->service.inspectPlans();
            default->throw new IllegalArgumentException("Unknown main strategy data command: "+args[0]);
        };
        System.out.println(new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private static String required(Map<String,String> options,String name){String value=options.get(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Required --"+name);return value;}
    private static String env(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value;}
}
