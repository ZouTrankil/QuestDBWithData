package com.zoutrankil.data.strategy.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.application.MainStrategyDataService;
import com.zoutrankil.data.bootstrap.ProjectConfiguration;
import com.zoutrankil.data.config.QuestDbProperties;
import org.springframework.boot.context.properties.bind.Binder;
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
        var config=ProjectConfiguration.load("data");
        var db=Binder.get(config).bind("app.questdb",QuestDbProperties.class).get();
        if(!db.getHost().matches("[A-Za-z0-9.-]+")||db.getPgPort()<1||db.getPgPort()>65535)throw new IllegalArgumentException("Canonical QuestDB endpoint required");
        var source=new DriverManagerDataSource("jdbc:postgresql://"+db.getHost()+":"+db.getPgPort()+"/"+db.getDatabase()+"?sslmode=disable",db.getUsername(),db.getPassword());
        var service=new MainStrategyDataService(new JdbcTemplate(source),
                options.getOrDefault("research-root",config.getRequiredProperty("app.sync.batch.research-root")),
                options.getOrDefault("recipe",config.getRequiredProperty("app.sync.batch.promoted-recipe")),
                options.getOrDefault("recipe-pins",config.getRequiredProperty("app.sync.batch.promoted-recipe-pins")),
                options.getOrDefault("artifact-root",config.getRequiredProperty("app.sync.batch.promoted-artifact-root")),
                options.getOrDefault("plan-db",config.getRequiredProperty("app.sync.batch.official-plan-db")),
                options.getOrDefault("start",config.getRequiredProperty("app.sync.batch.score-start-date")),
                Long.parseLong(options.getOrDefault("row-budget",config.getRequiredProperty("app.sync.batch.score-source-row-budget"))));
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
}
