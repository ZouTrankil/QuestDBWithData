package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.service.StockBasicSyncService;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.SyncJobRegistry;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import com.zoutrankil.questdbwithdata.domain.ReadGroupRequest;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.domain.StockBasicDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class CommandLineRunner implements ApplicationRunner {
    private final StockBasicSyncService syncService;
    private final DatasetRegistry datasetRegistry;
    private final SyncJobRegistry jobRegistry;
    private final com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService;
    private final com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService;
    private final ReadGroupReader readGroupReader;
    private final com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService;
    private final com.zoutrankil.questdbwithdata.service.StockBasicScheduleService scheduleService;
    private final com.zoutrankil.questdbwithdata.service.ExchangeCalendarJobService calendarService;
    private final com.zoutrankil.questdbwithdata.service.StockDetailInfoJobService stockDetailService;
    private final com.zoutrankil.questdbwithdata.service.IndexCatalogJobService indexCatalogService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.ThsIndexJobService thsIndexService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.IndexMembershipJobService indexMembershipService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.ThsMemberJobService thsMemberService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.DailyJobService dailyService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.DailyBasicJobService dailyBasicService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.StockFactorJobService stockFactorService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.StockLimitJobService stockLimitService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zoutrankil.questdbwithdata.service.EtfDailyJobService etfDailyService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.EtfAdjJobService etfAdjService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.EtfShareJobService etfShareService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.EtfFactorJobService etfFactorService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.IndexDailyMarketJobService indexDailyMarketService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.IndexDailyBasicJobService indexDailyBasicService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.IndexWeightJobService indexWeightService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.IndexMonthlyJobService indexMonthlyService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.DcIndexJobService dcIndexService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zoutrankil.questdbwithdata.service.MoneyflowDcJobService moneyflowDcService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zoutrankil.questdbwithdata.service.MoneyflowThsJobService moneyflowThsService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zoutrankil.questdbwithdata.service.MoneyflowJobService moneyflowService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.EtfPortfolioJobService etfPortfolioService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zoutrankil.questdbwithdata.service.EtfBasicJobService etfBasicService;
    @Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.StockStDailyJobService stockStDailyService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zoutrankil.questdbwithdata.service.StockSuspendJobService stockSuspendService;

    @Autowired
    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             @org.springframework.context.annotation.Lazy SyncJobRegistry jobRegistry,
                             com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService,
                             com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService,
                             @org.springframework.context.annotation.Lazy
                             com.zoutrankil.questdbwithdata.service.StockBasicScheduleService scheduleService,
                             com.zoutrankil.questdbwithdata.service.ExchangeCalendarJobService calendarService,
                             com.zoutrankil.questdbwithdata.service.StockDetailInfoJobService stockDetailService,
                             com.zoutrankil.questdbwithdata.service.IndexCatalogJobService indexCatalogService) {
        this.syncService = syncService;
        this.datasetRegistry = datasetRegistry;
        this.jobRegistry = jobRegistry;
        this.jobService = jobService;
        this.groupService = groupService;
        this.readGroupReader = readGroupReader;
        this.writeGroupService = writeGroupService;
        this.scheduleService = scheduleService;
        this.calendarService = calendarService;
        this.stockDetailService = stockDetailService;
        this.indexCatalogService = indexCatalogService;
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasets, SyncJobRegistry jobs,
            com.zoutrankil.questdbwithdata.service.StockBasicJobService job,
            com.zoutrankil.questdbwithdata.service.StockBasicGroupService group, ReadGroupReader read,
            com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService write,
            com.zoutrankil.questdbwithdata.service.StockBasicScheduleService schedule,
            com.zoutrankil.questdbwithdata.service.ExchangeCalendarJobService calendar,
            com.zoutrankil.questdbwithdata.service.StockDetailInfoJobService detail) {
        this(syncService,datasets,jobs,job,group,read,write,schedule,calendar,detail,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService,
                             com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService,
                             com.zoutrankil.questdbwithdata.service.StockBasicScheduleService scheduleService,
                             com.zoutrankil.questdbwithdata.service.ExchangeCalendarJobService calendarService) {
        this(syncService,datasetRegistry,jobRegistry,jobService,groupService,readGroupReader,writeGroupService,
                scheduleService,calendarService,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasets, SyncJobRegistry jobs,
            com.zoutrankil.questdbwithdata.service.StockBasicJobService job,
            com.zoutrankil.questdbwithdata.service.StockBasicGroupService group,ReadGroupReader read,
            com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService write,
            com.zoutrankil.questdbwithdata.service.StockBasicScheduleService schedule) {
        this(syncService,datasets,jobs,job,group,read,write,schedule,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.questdbwithdata.service.StockBasicJobService jobService,
                             com.zoutrankil.questdbwithdata.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.questdbwithdata.service.StockBasicWriteGroupService writeGroupService) {
        this(syncService,datasetRegistry,jobRegistry,jobService,groupService,readGroupReader,writeGroupService,null);
    }

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {
        String[] args = applicationArguments.getSourceArgs();
        if (args.length == 0) {
            throw new IllegalArgumentException(usage());
        }

        String command = args[0];
        Map<String, String> options = parseOptions(args);
        switch (command) {
            case "plan-ths-member-job", "run-ths-member-job" -> {
                if (thsMemberService == null || !options.keySet().equals(java.util.Set.of("--board-code", "--logical-date")))
                    throw new IllegalArgumentException("Exact THS board and logical date required");
                var request = thsMemberService.plan(options.get("--board-code"),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (command.equals("plan-ths-member-job"))
                    System.out.println(json.writeValueAsString(Map.of("status", "PLANNED", "executed", false,
                            "dataVerified", false, "targetId", thsMemberService.targetId(),
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(request)))));
                else {
                    var result = thsMemberService.run(request);
                    System.out.println(json.writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("THS member sync incomplete: " + result.state());
                }
            }
            case "finish-ths-member-publication" -> {
                if (thsMemberService == null || !options.keySet().equals(java.util.Set.of("--run", "--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit THS member run and stopped writer proof required");
                var result = thsMemberService.finishInterrupted(options.get("--run"), true);
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writeValueAsString(result));
            }
            case "discover-index-member-catalog", "plan-index-member-job", "run-index-member-job", "finish-index-member-child", "finish-index-member-prepared" ->
                    IndexMembershipCommands.execute(command,options,indexMembershipService);
            case "plan-ths-index-job", "run-ths-index-job" -> {
                if(thsIndexService==null || !options.keySet().contains("--logical-date")
                        || !java.util.Set.of("--logical-date","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit THS logical-date and registered owner required");
                boolean plan=command.equals("plan-ths-index-job");
                if(plan && options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume is an execution option");
                var request=thsIndexService.plan(java.time.LocalDate.parse(options.get("--logical-date")));
                var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if(plan) System.out.println(json.writeValueAsString(Map.of("status","PLANNED","executed",false,
                        "dataVerified",false,"request",json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(request)))));
                else {
                    var result=options.containsKey("--resume-from")
                            ? thsIndexService.resume(request,options.get("--resume-from")) : thsIndexService.run(request);
                    System.out.println(json.writeValueAsString(result));
                    if(result.errorCode()!=null || result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                        throw new IncompleteCommandException("THS sync incomplete: "+result.state());
                }
            }
            case "finish-ths-index-publication" -> {
                if(thsIndexService==null || !options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit run and writer-stopped true required");
                var result=thsIndexService.finishInterrupted(options.get("--run"),true);
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writeValueAsString(result));
            }
            case "plan-index-catalog-job", "run-index-catalog-job" -> {
                if(!options.keySet().containsAll(java.util.Set.of("--file","--logical-date"))
                        || !java.util.Set.of("--file","--logical-date","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit catalog file and logical-date required");
                boolean plan=command.equals("plan-index-catalog-job");
                if(plan && options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume is an execution option");
                var request=indexCatalogService.plan(Path.of(options.get("--file")),java.time.LocalDate.parse(options.get("--logical-date")));
                var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if(plan) System.out.println(json.writeValueAsString(Map.of("status","PLANNED","executed",false,
                        "dataVerified",false,"request",json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(request)))));
                else {
                    var result=options.containsKey("--resume-from")
                            ? indexCatalogService.resume(request,options.get("--resume-from")) : indexCatalogService.run(request);
                    System.out.println(json.writeValueAsString(result));
                    if(result.errorCode()!=null || result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Catalog sync incomplete: "+result.state());
                }
            }
            case "finish-index-catalog-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit run and writer-stopped true required");
                var result=indexCatalogService.finishInterrupted(options.get("--run"),true);
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writeValueAsString(result));
            }
            case "run-exchange-calendar", "plan-exchange-calendar" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--exchanges","--from","--to","--logical-date"))
                        || !java.util.Set.of("--exchanges","--from","--to","--logical-date","--mode","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit exchanges, bootstrap from, to and logical-date required");
                var exchanges=java.util.Arrays.asList(options.get("--exchanges").split(",",-1));
                var from=java.time.LocalDate.parse(options.get("--from"));
                var to=java.time.LocalDate.parse(options.get("--to"));
                var date=java.time.LocalDate.parse(options.get("--logical-date"));
                var mode=options.containsKey("--mode")?com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode
                        .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)):null;
                if(command.equals("plan-exchange-calendar")) {
                    if(options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume requires exact frozen run inputs");
                    var plan=calendarService.plan(exchanges,from,to,date,mode);
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writeValueAsString(Map.of(
                            "status","PLANNED","executed",false,"dataVerified",false,"targetId",plan.targetId(),
                            "checkpointCandidates",plan.checkpointCandidates(),"checkedTargetRows",plan.checkedTargetRows(),
                            "request",new ObjectMapper().readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    com.zoutrankil.questdbwithdata.service.SyncJobRunner.Result result;
                    if(options.containsKey("--resume-from")) {
                        var frozen=com.zoutrankil.questdbwithdata.service.ExchangeCalendarSyncAdapter.definition(true)
                                .freeze(mode,Map.of("exchanges",exchanges),from,to,date);
                        result=calendarService.execute(new com.zoutrankil.questdbwithdata.service.ExchangeCalendarJobService.Plan(
                                frozen,calendarService.targetId(),Map.of(),0),options.get("--resume-from"));
                    } else result=calendarService.run(exchanges,from,to,date,mode);
                    System.out.println(new ObjectMapper().writeValueAsString(result));
                    if(result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Calendar sync incomplete: "+result.state());
                }
            }
            case "run-sync-group" -> {
                var planningOptions=new java.util.LinkedHashMap<>(options);
                String prior=planningOptions.remove("--resume-from");
                var groups=new com.zoutrankil.questdbwithdata.service.SyncGroupRegistry(groupService.definitions(),jobRegistry);
                var plan=com.zoutrankil.questdbwithdata.service.SyncGroupPlanning.prepare(groups,jobRegistry,planningOptions);
                var result=groupService.runPlan(plan,prior);
                System.out.println(new ObjectMapper().writeValueAsString(result));
                if(result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                    throw new IncompleteCommandException("Sync group incomplete: "+result.state());
            }
            case "plan-sync-group", "validate-sync-group" -> {
                var groups=new com.zoutrankil.questdbwithdata.service.SyncGroupRegistry(
                        groupService.definitions(),jobRegistry);
                var plan=com.zoutrankil.questdbwithdata.service.SyncGroupPlanning.prepare(groups,jobRegistry,options);
                var frozen=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                var mapper=new ObjectMapper();
                for (var request:plan.requests()) frozen.add(mapper.readTree(
                        com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(request)));
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "status",command.equals("plan-sync-group")?"PLANNED":"VALIDATED",
                        "executed",false,"dataVerified",false,"group",plan.definition(),
                        "logicalDate",plan.logicalDate().toString(),"requests",frozen)));
            }
            case "plan-sync-job", "validate-sync-job" -> {
                var request = com.zoutrankil.questdbwithdata.service.SyncJobPlanning.prepare(jobRegistry, options);
                String frozen = com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(request);
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "status", command.equals("plan-sync-job") ? "PLANNED" : "VALIDATED",
                        "executed", false, "dataVerified", false, "request", new ObjectMapper().readTree(frozen))));
            }
            case "plan-daily-job", "run-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dailyService==null || !command.equals("run-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dailyService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dailyService == null || !options.keySet().containsAll(java.util.Set.of("--from", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --from and --logical-date required; optional --to, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-daily-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = dailyService.plan(java.time.LocalDate.parse(options.get("--from")),
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        java.time.LocalDate.parse(options.get("--logical-date")), mode);
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? dailyService.resume(plan, options.get("--resume-from")) : dailyService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-daily-basic-job", "run-daily-basic-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dailyBasicService==null || !command.equals("run-daily-basic-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dailyBasicService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("daily-basic resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dailyBasicService == null || !options.keySet().containsAll(java.util.Set.of("--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; --from is required for bootstrap/backfill, optional --to, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-daily-basic-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var end = options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : logicalDate;
                var plan = dailyBasicService.plan(options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        end, logicalDate, mode);
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? dailyBasicService.resume(plan, options.get("--resume-from")) : dailyBasicService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("daily_basic sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-factor-job", "run-stk-factor-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockFactorService==null || !command.equals("run-stk-factor-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=stockFactorService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("stk-factor resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockFactorService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--ts-code", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from, --mode, --ts-code, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-factor-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = stockFactorService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")), options.get("--ts-code"));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockFactorService.resume(plan.request(), options.get("--resume-from"))
                            : stockFactorService.run(plan.request());
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_factor sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-limit-job", "run-stk-limit-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockLimitService==null || !command.equals("run-stk-limit-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=stockLimitService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("stk-limit resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockLimitService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-limit-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = stockLimitService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockLimitService.resume(plan, options.get("--resume-from")) : stockLimitService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_limit sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-daily-job", "run-etf-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfDailyService==null || !command.equals("run-etf-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfDailyService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfDailyService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-daily-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfDailyService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? etfDailyService.resume(plan, options.get("--resume-from")) : etfDailyService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-adj-job", "run-etf-adj-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfAdjService==null || !command.equals("run-etf-adj-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfAdjService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-adj resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfAdjService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-adj-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfAdjService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? etfAdjService.resume(plan, options.get("--resume-from")) : etfAdjService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_adj sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-share-job", "run-etf-share-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfShareService==null || !command.equals("run-etf-share-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfShareService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-share resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfShareService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-share-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfShareService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = etfShareService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_share sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-factor-job", "run-etf-factor-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfFactorService==null || !command.equals("run-etf-factor-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfFactorService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-factor resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfFactorService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-factor-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfFactorService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = etfFactorService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_factor sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-dc-job", "run-moneyflow-dc-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowDcService==null || !command.equals("run-moneyflow-dc-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowDcService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow-dc resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowDcService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-dc-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowDcService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = moneyflowDcService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow_dc sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-ths-job", "run-moneyflow-ths-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowThsService==null || !command.equals("run-moneyflow-ths-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowThsService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow-ths resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowThsService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-ths-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowThsService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = moneyflowThsService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow_ths sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-moneyflow-job", "run-moneyflow-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(moneyflowService==null || !command.equals("run-moneyflow-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=moneyflowService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("moneyflow resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (moneyflowService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-moneyflow-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = moneyflowService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = moneyflowService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("moneyflow sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-dc-index-job", "run-dc-index-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(dcIndexService==null || !command.equals("run-dc-index-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=dcIndexService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("dc-index resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (dcIndexService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-dc-index-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = dcIndexService.planDetailed(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = dcIndexService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("dc_index sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-daily-market-job", "run-index-daily-market-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexDailyMarketService==null || !command.equals("run-index-daily-market-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexDailyMarketService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-daily-market resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexDailyMarketService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-daily-market-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexDailyMarketService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = indexDailyMarketService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_daily_market sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-monthly-job", "run-index-monthly-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexMonthlyService==null || !command.equals("run-index-monthly-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexMonthlyService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-monthly resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexMonthlyService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-monthly-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexMonthlyService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = indexMonthlyService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_monthly sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-daily-basic-job", "run-index-daily-basic-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexDailyBasicService==null || !command.equals("run-index-daily-basic-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexDailyBasicService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-daily-basic resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexDailyBasicService == null || !options.keySet().containsAll(java.util.Set.of("--ts-code", "--to", "--logical-date"))
                        || !java.util.Set.of("--ts-code", "--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --ts-code, --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-daily-basic-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = indexDailyBasicService.plan(mode, options.get("--ts-code"),
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = indexDailyBasicService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_daily_basic sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-index-weight-job", "run-index-weight-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(indexWeightService==null || !command.equals("run-index-weight-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=indexWeightService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("index-weight resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (indexWeightService == null || !options.keySet().containsAll(java.util.Set.of("--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from", "--force").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; optional --mode SNAPSHOT|BACKFILL, --from/--to for BACKFILL, --force true|false, and run-only --resume-from");
                boolean planOnly = command.equals("plan-index-weight-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                if(options.containsKey("--force") && !java.util.Set.of("true","false").contains(options.get("--force")))
                    throw new IllegalArgumentException("--force must be true or false");
                var plan = indexWeightService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        java.time.LocalDate.parse(options.get("--logical-date")), Boolean.parseBoolean(options.getOrDefault("--force","false")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (plan.notDue()) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "NOT_DUE", "executed", false, "dataVerified", false, "sourceRequests", 0,
                            "lastRefreshDate", plan.lastRefreshDate(), "nextRefreshDate", plan.nextRefreshDate(), "plan", plan)));
                    return;
                }
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = indexWeightService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("index_weight sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "list-index-daily-market-universe" -> {
                if(!options.isEmpty())throw new IllegalArgumentException("Universe listing accepts no options");
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "CORE57",com.zoutrankil.questdbwithdata.service.IndexDailyMarketUniverse.CORE57,
                        "SW2021_L1_31",com.zoutrankil.questdbwithdata.service.IndexDailyMarketUniverse.SW2021_L1_31)));
            }
            case "plan-etf-portfolio-job", "run-etf-portfolio-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(etfPortfolioService==null || !command.equals("run-etf-portfolio-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var restored=etfPortfolioService.resume(options.get("--resume-from"));
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.errorCode()!=null || !java.util.Set.of(com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED,
                            com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY).contains(restored.state()))
                        throw new IncompleteCommandException("etf-portfolio resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (etfPortfolioService == null || !options.keySet().containsAll(java.util.Set.of("--to", "--logical-date"))
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from for bootstrap/backfill, --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-etf-portfolio-job");
                if (planOnly && options.containsKey("--resume-from")) throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var plan = etfPortfolioService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        java.time.LocalDate.parse(options.get("--to")),
                        java.time.LocalDate.parse(options.get("--logical-date")));
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = etfPortfolioService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("etf_portfolio sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-etf-basic-job", "run-etf-basic-job" -> {
                if (etfBasicService == null) throw new IllegalArgumentException("ETF basic service unavailable");
                boolean planOnly = command.equals("plan-etf-basic-job");
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (options.containsKey("--resume-from")) {
                    if (planOnly || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; saved observation time and scope are restored");
                    var result = etfBasicService.resume(options.get("--resume-from"));
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                        throw new IncompleteCommandException("etf_basic resume incomplete: " + result.state() + "; run=" + result.runId());
                } else {
                    if (!options.keySet().equals(java.util.Set.of("--logical-date")))
                        throw new IllegalArgumentException("ETF basic snapshot requires --logical-date YYYY-MM-DD");
                    var plan = etfBasicService.plan(java.time.LocalDate.parse(options.get("--logical-date")));
                    if (planOnly) System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false, "targetId", plan.targetId(),
                            "plan", plan, "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                    else {
                        var result = etfBasicService.run(plan);
                        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                        if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                            throw new IncompleteCommandException("etf_basic snapshot incomplete: " + result.state() + "; run=" + result.runId());
                    }
                }
            }
            case "plan-stk-st-daily-job", "run-stk-st-daily-job" -> {
                if(options.containsKey("--resume-from")) {
                    if(stockStDailyService==null || !command.equals("run-stk-st-daily-job")
                            || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    String prior=options.get("--resume-from");
                    var restored=stockStDailyService.resume(stockStDailyService.restorePlan(prior),prior);
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(restored));
                    if(restored.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            &&restored.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_st_daily resume incomplete: "+restored.state()+"; run="+restored.runId());
                    return;
                }
                if (stockStDailyService == null || !options.containsKey("--logical-date")
                        || !java.util.Set.of("--from", "--to", "--logical-date", "--mode", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --logical-date required; optional --from, --to (defaults to --logical-date), --mode, and run-only --resume-from");
                boolean planOnly = command.equals("plan-stk-st-daily-job");
                if (planOnly && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                var mode = options.containsKey("--mode")
                        ? com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT))
                        : null;
                var logicalDate = java.time.LocalDate.parse(options.get("--logical-date"));
                var plan = stockStDailyService.plan(mode,
                        options.containsKey("--from") ? java.time.LocalDate.parse(options.get("--from")) : null,
                        options.containsKey("--to") ? java.time.LocalDate.parse(options.get("--to")) : null,
                        logicalDate);
                var json = com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if (planOnly) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status", "PLANNED", "executed", false, "dataVerified", false,
                            "targetId", plan.targetId(), "plan", plan,
                            "request", json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result = options.containsKey("--resume-from")
                            ? stockStDailyService.resume(plan, options.get("--resume-from")) : stockStDailyService.run(plan);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.errorCode() != null || result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_st_daily sync incomplete: " + result.state() + "; run=" + result.runId());
                }
            }
            case "plan-stk-suspend-job", "run-stk-suspend-job" -> {
                if(stockSuspendService==null) throw new IllegalArgumentException("Stock suspension service unavailable");
                var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if(options.containsKey("--resume-from")) {
                    if(!command.equals("run-stk-suspend-job") || !options.keySet().equals(java.util.Set.of("--resume-from")))
                        throw new IllegalArgumentException("Resume requires only --resume-from; the original frozen scope is restored");
                    var result=stockSuspendService.resume(options.get("--resume-from"));
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if(result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            &&result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_suspend resume incomplete: "+result.state()+"; run="+result.runId());
                    return;
                }
                if(!options.containsKey("--to") || !options.containsKey("--logical-date")
                        || !java.util.Set.of("--from","--to","--logical-date","--mode").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --to and --logical-date required; optional --from and --mode");
                var mode=options.containsKey("--mode")?com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode.valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)):null;
                var plan=stockSuspendService.planDetailed(mode,
                        options.containsKey("--from")?java.time.LocalDate.parse(options.get("--from")):null,
                        java.time.LocalDate.parse(options.get("--to")),java.time.LocalDate.parse(options.get("--logical-date")));
                if(command.equals("plan-stk-suspend-job")) {
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "status","PLANNED","executed",false,"dataVerified",false,"targetId",plan.targetId(),
                            "plan",plan,"request",json.readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(plan.request())))));
                } else {
                    var result=stockSuspendService.run(plan.request());
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if(result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            &&result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("stk_suspend incomplete: "+result.state()+"; run="+result.runId());
                }
            }
            case "schedule-put" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON required");
                scheduleService.put(Path.of(options.get("--request")));
                System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                        "status","STORED","executed",false,"dataVerified",false)));
            }
            case "finish-index-monthly-publication", "finish-dc-index-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped")) || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if(command.equals("finish-index-monthly-publication")) {
                    if(indexMonthlyService==null)throw new IllegalArgumentException("Monthly service unavailable");
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(indexMonthlyService.finishInterrupted(options.get("--run"),true)));
                } else {
                    if(dcIndexService==null)throw new IllegalArgumentException("DC index service unavailable");
                    dcIndexService.finishPublication(options.get("--run"),true);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("status","RECOVERED","runId",options.get("--run"))));
                }
            }
            case "finish-stk-suspend-publication", "finish-stk-st-daily-publication" -> {
                if(!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        ||!"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                var json=com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper();
                if(command.equals("finish-stk-suspend-publication")) {
                    if(stockSuspendService==null)throw new IllegalArgumentException("Stock suspension service unavailable");
                    stockSuspendService.finishPublication(options.get("--run"),true);
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(stockSuspendService.status(options.get("--run"))));
                } else {
                    if(stockStDailyService==null)throw new IllegalArgumentException("ST daily service unavailable");
                    System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(stockStDailyService.finishInterrupted(options.get("--run"),true)));
                }
            }
            case "schedule-status" -> {
                if (!options.keySet().equals(java.util.Set.of("--id")))
                    throw new IllegalArgumentException("Explicit --id required");
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(scheduleService.status(options.get("--id"))));
            }
            case "schedule-enable" -> {
                if (!options.keySet().equals(java.util.Set.of("--id","--enabled"))
                        || !java.util.Set.of("true","false").contains(options.get("--enabled")))
                    throw new IllegalArgumentException("Explicit --id and boolean --enabled required");
                scheduleService.setEnabled(options.get("--id"),Boolean.parseBoolean(options.get("--enabled")));
                System.out.println(new ObjectMapper().writeValueAsString(Map.of("status","CONFIGURED",
                        "scheduleId",options.get("--id"),"enabled",Boolean.parseBoolean(options.get("--enabled")),
                        "executed",false,"dataVerified",false)));
            }
            case "schedule-tick" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("schedule-tick has no options");
                var outcomes=scheduleService.tick();
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(outcomes));
                if (outcomes.stream().anyMatch(o -> o.state()!=com.zoutrankil.questdbwithdata.repository.SyncScheduleStore.State.VERIFIED
                        && o.state()!=com.zoutrankil.questdbwithdata.repository.SyncScheduleStore.State.VERIFIED_EMPTY))
                    throw new IncompleteCommandException("One or more schedule slots were not verified");
            }
            case "write-dataset-group" -> {
                if (!options.containsKey("--request")
                        || !java.util.Set.of("--request", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --request required; optional --resume-from");
                var result = writeGroupService.run(Path.of(options.get("--request")), options.get("--resume-from"));
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException("Write group incomplete: " + result.state() + "; run=" + result.runId());
            }
            case "read-dataset-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON file required");
                var request = readGroupReader.readRequest(Path.of(options.get("--request")));
                var result = readGroupReader.read(request, () -> Thread.currentThread().isInterrupted());
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                "complete", result.complete(), "atomicSnapshot", false, "result", result)));
                if (!result.complete()) throw new IncompleteCommandException("Read group contains unsuccessful members");
            }
            case "read-stock-basic-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--codes", "--from", "--to", "--page-size")))
                    throw new IllegalArgumentException("Explicit --codes, --from, --to and --page-size are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var from = java.time.LocalDate.parse(options.get("--from")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                var to = java.time.LocalDate.parse(options.get("--to")).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                int pageSize = Integer.parseInt(options.get("--page-size"));
                var projection = StockBasicDataset.DEFINITION.columns().stream()
                        .map(com.zoutrankil.questdbwithdata.domain.DatasetDefinition.Column::logicalName).toList();
                var members = new java.util.ArrayList<ReadGroupRequest.Member>();
                for (int i = 0; i < codes.size(); i++) {
                    var query = new DatasetReadQuery(projection, Map.of("ts_code", codes.get(i)),
                            "snapshot_ts", from, to, pageSize, null);
                    members.add(new ReadGroupRequest.Member("code" + i, StockBasicDataset.DEFINITION.datasetId(),
                            StockBasicDataset.DEFINITION.schemaVersion(), query));
                }
                var result = readGroupReader.read(new ReadGroupRequest(members, java.time.Duration.ofMinutes(1)), () -> false);
                System.out.println(new ObjectMapper().findAndRegisterModules()
                        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result));
                if (!result.complete()) throw new IncompleteCommandException("Read group contains failed members");
            }
            case "show-sync-group" -> {
                if (!options.keySet().equals(java.util.Set.of("--group", "--version")))
                    throw new IllegalArgumentException("Explicit group and version required");
                var registry = new com.zoutrankil.questdbwithdata.service.SyncGroupRegistry(groupService.definitions(), jobRegistry);
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                        registry.require(options.get("--group"), Integer.parseInt(options.get("--version")))));
            }
            case "show-sync-group-definitions", "list-sync-groups" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Group list accepts no options");
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(groupService.definitions()));
            }
            case "run-stock-basic-group" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = groupService.run(codes, day, options.get("--resume-from"));
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED)
                    throw new IncompleteCommandException("Group did not complete: " + result.state() + "; run=" + result.runId());
            }
            case "cancel-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run").containsAll(options.keySet()) || !options.containsKey("--run"))
                    throw new IllegalArgumentException("--run required; optional --ledger");
                Path path = Path.of(options.getOrDefault("--ledger", "var/sync-ledger.sqlite3"));
                if (!java.nio.file.Files.isRegularFile(path)) throw new IllegalArgumentException("Ledger does not exist");
                var ledger = new com.zoutrankil.questdbwithdata.repository.SyncRunLedger(path);
                boolean accepted = ledger.requestCancellation(options.get("--run"));
                System.out.println(new ObjectMapper().writeValueAsString(Map.of("runId",options.get("--run"),
                        "cancellationRequested",accepted,"state",ledger.get(options.get("--run")).state())));
            }
            case "run-stock-basic-job" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--codes", "--logical-date"))
                        || !java.util.Set.of("--codes", "--logical-date", "--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit --codes and --logical-date are required");
                var codes = java.util.Arrays.asList(options.get("--codes").split(",", -1));
                var day = java.time.LocalDate.parse(options.get("--logical-date"));
                var result = options.containsKey("--resume-from")
                        ? jobService.resume(codes,day,options.get("--resume-from")) : jobService.run(codes,day);
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
                if (result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                        && result.state() != com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                    throw new IncompleteCommandException("Sync did not complete: " + result.state() + "; run=" + result.runId());
            }
            case "plan-stock-detail-job", "run-stock-detail-job" -> {
                if (!options.containsKey("--logical-date")
                        || !java.util.Set.of("--logical-date","--codes","--discover","--resume-from").containsAll(options.keySet())
                        || options.containsKey("--codes") == options.containsKey("--discover"))
                    throw new IllegalArgumentException("Specify --logical-date and exactly one of --codes or --discover true");
                if(command.equals("plan-stock-detail-job") && options.containsKey("--resume-from"))
                    throw new IllegalArgumentException("Planning cannot resume a run");
                if (options.containsKey("--discover") && !"true".equals(options.get("--discover")))
                    throw new IllegalArgumentException("Discovery requires --discover true");
                var codes=options.containsKey("--codes")
                        ? java.util.Arrays.asList(options.get("--codes").split(",",-1)) : java.util.List.<String>of();
                var day=java.time.LocalDate.parse(options.get("--logical-date"));
                var frozen=stockDetailService.plan(codes,options.containsKey("--discover"),day);
                if (command.equals("plan-stock-detail-job")) {
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                    "status","PLANNED","executed",false,"dataVerified",false,
                                    "targetId",stockDetailService.targetId(),"request",
                                    new ObjectMapper().readTree(com.zoutrankil.questdbwithdata.domain.SyncRequestIdentity.snapshotJson(frozen)))));
                } else {
                    var result=options.containsKey("--resume-from")
                            ? stockDetailService.resume(frozen,options.get("--resume-from"))
                            : stockDetailService.run(frozen);
                    System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(result));
                    if (result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.questdbwithdata.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Stock-detail sync incomplete: "+result.state()+"; run="+result.runId());
                }
            }
            case "reconcile-stock-detail-run", "finish-stock-detail-publication" -> {
                if (!options.keySet().equals(java.util.Set.of("--run","--writer-stopped"))
                        || !"true".equals(options.get("--writer-stopped")))
                    throw new IllegalArgumentException("Explicit --run and --writer-stopped true required");
                var result=command.equals("finish-stock-detail-publication")
                        ?stockDetailService.finishInterrupted(options.get("--run"),true)
                        :stockDetailService.reconcilePublished(options.get("--run"),true);
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(result));
            }
            case "show-sync-history" -> {
                if (!java.util.Set.of("--ledger", "--job", "--after", "--limit").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown history option");
                var ledger = com.zoutrankil.questdbwithdata.repository.SyncRunLedger.openReadOnly(
                        Path.of(options.getOrDefault("--ledger", "var/sync-ledger.sqlite3")));
                var rows = ledger.history(options.get("--job"), options.get("--after"),
                        Integer.parseInt(options.getOrDefault("--limit", "100")));
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "runs", rows, "order", "runIdAscending", "readOnly", true,
                        "nextAfter", rows.isEmpty() ? "" : rows.getLast().id())));
            }
            case "show-sync-run" -> {
                if (!java.util.Set.of("--ledger", "--run", "--after", "--limit").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown status query option");
                String run = options.get("--run");
                if (run == null) throw new IllegalArgumentException("--run is required");
                var ledger = com.zoutrankil.questdbwithdata.repository.SyncRunLedger.openReadOnly(
                        Path.of(options.getOrDefault("--ledger", "var/sync-ledger.sqlite3")));
                int limit = Integer.parseInt(options.getOrDefault("--limit", "100"));
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "run", ledger.getRun(run), "entries", ledger.entries(run, options.get("--after"), limit))));
            }
            case "show-sync-job" -> {
                if (!options.keySet().equals(java.util.Set.of("--job", "--version")))
                    throw new IllegalArgumentException("Explicit job and version required");
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(jobRegistry.require(
                                options.get("--job"), Integer.parseInt(options.get("--version")))));
            }
            case "show-sync-job-definitions", "list-sync-jobs" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Job list accepts no options");
                System.out.println(com.zoutrankil.questdbwithdata.domain.JobDefinitionJson.mapper()
                        .writerWithDefaultPrettyPrinter().writeValueAsString(jobRegistry.definitions()));
            }
            case "show-dataset-definitions" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Dataset list accepts no options");
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(datasetRegistry.definitions()));
            }
            case "sync-stock-basic" -> {
                if (!java.util.Set.of("--output").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Unknown stock-basic CSV option");
                Path output = Path.of(options.getOrDefault("--output", "var/stock_basic.csv"));
                int rows = syncService.syncToCsv(output);
                System.out.printf("Fetched %d stocks; wrote %s%n", rows, output.toAbsolutePath());
            }
            case "sync-stock-basic-questdb", "sync-stock-basic-qwp", "sync-stock-basic-jdbc" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Legacy QuestDB sync accepts no options");
                StockBasicSyncReport result = syncService.syncToQuestDb();
                System.out.printf(
                        "Tushare rows=%d; QuestDB visible rows=%d; snapshot=%s%n",
                        result.submittedRows(), result.visibleRows(),
                        result.snapshotTimestamp());
            }
            case "migrate-questdb-schema", "create-questdb-schema" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Schema command accepts no options");
                syncService.initializeQuestDbSchema();
                System.out.println("QuestDB Flyway migrations completed");
            }
            case "show-stock-basic-latest" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("Stock-basic read accepts no options");
                var rows = syncService.loadLatestStocks();
                rows.forEach(System.out::println);
                System.out.printf("Read %d rows from latest-stock view%n", rows.size());
            }
            case "verify-questdb-jdbc" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("JDBC probe accepts no options");
                syncService.verifyQuestDbConnection();
                System.out.println("PostgreSQL JDBC connected; SELECT 1 passed");
            }
            default -> throw new IllegalArgumentException(usage());
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            int equals = argument.indexOf('=');
            if (equals > 2) {
                putOption(options, argument.substring(0, equals), argument.substring(equals + 1));
                continue;
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            putOption(options, argument, args[++i]);
        }
        return options;
    }

    private static void putOption(Map<String,String> options, String key, String value) {
        if (!key.matches("--[a-z][a-z0-9-]*") || value.isBlank())
            throw new IllegalArgumentException("Invalid or empty option: " + key);
        if (options.putIfAbsent(key, value) != null)
            throw new IllegalArgumentException("Duplicate option: " + key);
    }

    private static String usage() {
        return "Usage: run-exchange-calendar|plan-exchange-calendar --exchanges SSE,SZSE --from YYYY-MM-DD --to YYYY-MM-DD "
                + "--logical-date YYYY-MM-DD [--mode incremental|backfill|reconcile] [--resume-from RUN_ID] OR sync-stock-basic [--output PATH] OR "
                + "sync-stock-basic-questdb OR migrate-questdb-schema OR "
                + "show-stock-basic-latest OR verify-questdb-jdbc OR show-dataset-definitions OR show-sync-job-definitions OR "
                + "list-sync-jobs OR show-sync-job --job ID --version N OR "
                + "list-sync-groups OR show-sync-group --group ID --version N OR "
                + "show-sync-group-definitions OR run-stock-basic-group --codes CODE,CODE --logical-date YYYY-MM-DD "
                + "[--resume-from GROUP_RUN_ID] OR "
                + "read-dataset-group --request PATH OR "
                + "write-dataset-group --request PATH [--resume-from RUN_ID] OR "
                + "schedule-put --request PATH OR schedule-status --id ID OR "
                + "schedule-enable --id ID --enabled true|false OR schedule-tick OR "
                + "read-stock-basic-group --codes CODE,CODE --from YYYY-MM-DD --to YYYY-MM-DD --page-size N OR "
                + "plan-sync-job|validate-sync-job --job ID --version N --logical-date YYYY-MM-DD "
                + "[--parameters JSON|--parameters-file PATH] [--mode MODE] [--from YYYY-MM-DD --to YYYY-MM-DD] OR "
                + "plan-sync-group|validate-sync-group --group ID --version N --logical-date YYYY-MM-DD "
                + "[--parameters JSON|--parameters-file PATH] [--overrides JSON|--overrides-file PATH] "
                + "[--mode MODE] [--from YYYY-MM-DD --to YYYY-MM-DD] OR "
                + "show-sync-history [--ledger PATH] [--job ID] [--after RUN_ID] [--limit N] OR "
                + "show-sync-run --run ID [--ledger PATH] [--after ENTRY_ID] [--limit N] OR "
                + "run-stock-basic-job --codes CODE,CODE --logical-date YYYY-MM-DD [--resume-from RUN_ID] OR "
                + "plan-daily-job|run-daily-job --from YYYY-MM-DD [--to YYYY-MM-DD] --logical-date YYYY-MM-DD [--mode MODE] OR run-daily-job --resume-from RUN_ID OR "
                + "plan-daily-basic-job|run-daily-basic-job [--from YYYY-MM-DD] [--to YYYY-MM-DD (defaults to --logical-date)] --logical-date YYYY-MM-DD [--mode MODE] OR run-daily-basic-job --resume-from RUN_ID OR "
                + "plan-stk-factor-job|run-stk-factor-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] [--ts-code CODE] OR run-stk-factor-job --resume-from RUN_ID OR "
                + "plan-stk-limit-job|run-stk-limit-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-stk-limit-job --resume-from RUN_ID OR "
                + "plan-etf-daily-job|run-etf-daily-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-etf-daily-job --resume-from RUN_ID OR "
                + "plan-etf-basic-job|run-etf-basic-job --logical-date YYYY-MM-DD OR run-etf-basic-job --resume-from RUN_ID OR "
                + "plan-etf-portfolio-job|run-etf-portfolio-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-etf-portfolio-job --resume-from RUN_ID OR "
                + "plan-etf-share-job|run-etf-share-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-etf-share-job --resume-from RUN_ID OR "
                + "plan-etf-factor-job|run-etf-factor-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-etf-factor-job --resume-from RUN_ID OR "
                + "plan-index-daily-market-job|run-index-daily-market-job --ts-code CODE --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-index-daily-market-job --resume-from RUN_ID OR "
                + "plan-index-daily-basic-job|run-index-daily-basic-job --ts-code CODE --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-index-daily-basic-job --resume-from RUN_ID OR "
                + "plan-dc-index-job|run-dc-index-job --logical-date YYYY-MM-DD --to YYYY-MM-DD [--from YYYY-MM-DD] [--mode INCREMENTAL|BACKFILL] OR run-dc-index-job --resume-from RUN_ID OR finish-index-monthly-publication|finish-dc-index-publication --run RUN_ID --writer-stopped true OR "
                + "plan-index-monthly-job|run-index-monthly-job --ts-code CODE --logical-date YYYY-MM-DD --to YYYY-MM-DD [--from YYYY-MM-DD] [--mode INCREMENTAL|BACKFILL|RECONCILE] OR run-index-monthly-job --resume-from RUN_ID OR "
                + "plan-index-weight-job|run-index-weight-job --logical-date YYYY-MM-DD [--mode SNAPSHOT|BACKFILL] [--from YYYY-MM-DD --to YYYY-MM-DD] [--force true|false] OR run-index-weight-job --resume-from RUN_ID OR "
                + "list-index-daily-market-universe OR "
                + "plan-etf-adj-job|run-etf-adj-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-etf-adj-job --resume-from RUN_ID OR "
                + "plan-stk-suspend-job|run-stk-suspend-job --to YYYY-MM-DD --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--mode MODE] OR run-stk-suspend-job --resume-from RUN_ID OR "
                + "finish-stk-suspend-publication|finish-stk-st-daily-publication --run RUN_ID --writer-stopped true OR "
                + "plan-stk-st-daily-job|run-stk-st-daily-job --logical-date YYYY-MM-DD [--from YYYY-MM-DD] [--to YYYY-MM-DD (defaults to --logical-date)] [--mode MODE] OR run-stk-st-daily-job --resume-from RUN_ID OR "
                + "plan-ths-index-job|run-ths-index-job --logical-date YYYY-MM-DD [--resume-from ID] OR "
                + "plan-ths-member-job|run-ths-member-job --board-code THS_CODE --logical-date YYYY-MM-DD OR "
                + "finish-ths-member-publication --run ID --writer-stopped true OR "
                + "discover-index-member-catalog --output-directory PATH OR "
                + "plan-index-member-job|run-index-member-job --classification-receipt PATH --classification-sha256 SHA "
                + "--industries CODE,CODE --selection CURRENT|HISTORICAL|BOTH --logical-date YYYY-MM-DD "
                + "[--resume-from ID [--writer-stopped true]] OR finish-index-member-child|finish-index-member-prepared --run ID --writer-stopped true OR "
                + "finish-ths-index-publication --run ID --writer-stopped true OR "
                + "plan-index-catalog-job|run-index-catalog-job --file PATH --logical-date YYYY-MM-DD [--resume-from ID] OR "
                + "finish-index-catalog-publication --run ID --writer-stopped true OR "
                + "plan-stock-detail-job|run-stock-detail-job --logical-date YYYY-MM-DD (--codes CODE,CODE|--discover true) "
                + "[--resume-from FAILED_RUN_ID] OR "
                + "reconcile-stock-detail-run|finish-stock-detail-publication --run ID --writer-stopped true OR cancel-sync-run --run ID [--ledger PATH]";
    }
}
