package com.zoutrankil.data.cli;

import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class CalendarCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "run-exchange-calendar", "plan-exchange-calendar");
    private final com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService;

    public CalendarCommands(com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService) {
        this.calendarService = calendarService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "run-exchange-calendar", "plan-exchange-calendar" -> {
                if (!options.keySet().containsAll(java.util.Set.of("--exchanges","--from","--to","--logical-date"))
                        || !java.util.Set.of("--exchanges","--from","--to","--logical-date","--mode","--resume-from").containsAll(options.keySet()))
                    throw new IllegalArgumentException("Explicit exchanges, bootstrap from, to and logical-date required");
                var exchanges=java.util.Arrays.asList(options.get("--exchanges").split(",",-1));
                var from=java.time.LocalDate.parse(options.get("--from"));
                var to=java.time.LocalDate.parse(options.get("--to"));
                var date=java.time.LocalDate.parse(options.get("--logical-date"));
                var mode=options.containsKey("--mode")?com.zoutrankil.data.domain.SyncJobDefinition.Mode
                        .valueOf(options.get("--mode").toUpperCase(java.util.Locale.ROOT)):null;
                if(command.equals("plan-exchange-calendar")) {
                    if(options.containsKey("--resume-from")) throw new IllegalArgumentException("Resume requires exact frozen run inputs");
                    var plan=calendarService.plan(exchanges,from,to,date,mode);
                    CliOutput.printJson(Map.of(
                            "status","PLANNED","executed",false,"dataVerified",false,"targetId",plan.targetId(),
                            "checkpointCandidates",plan.checkpointCandidates(),"checkedTargetRows",plan.checkedTargetRows(),
                            "request",CliOutput.readTree(com.zoutrankil.data.domain.SyncRequestIdentity.snapshotJson(plan.request()), CliOutput.Profile.PLAIN)), CliOutput.Profile.JOB_DEFINITION, false);
                } else {
                    com.zoutrankil.data.service.SyncJobRunner.Result result;
                    if(options.containsKey("--resume-from")) {
                        var frozen=com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter.definition(true)
                                .freeze(mode,Map.of("exchanges",exchanges),from,to,date);
                        result=calendarService.execute(new com.zoutrankil.data.calendar.application.ExchangeCalendarJobService.Plan(
                                frozen,calendarService.targetId(),Map.of(),0),options.get("--resume-from"));
                    } else result=calendarService.run(exchanges,from,to,date,mode);
                    CliOutput.printJson(result, CliOutput.Profile.PLAIN, false);
                    if(result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED
                            && result.state()!=com.zoutrankil.data.domain.SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException("Calendar sync incomplete: "+result.state());
                }
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
