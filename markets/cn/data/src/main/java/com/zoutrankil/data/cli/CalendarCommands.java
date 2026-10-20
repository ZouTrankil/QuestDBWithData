package com.zoutrankil.data.cli;

import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService.Plan;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner.Result;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class CalendarCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of("run-exchange-calendar", "plan-exchange-calendar");
    private final ExchangeCalendarJobService calendarService;

    public CalendarCommands(ExchangeCalendarJobService calendarService) {
        this.calendarService = calendarService;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "run-exchange-calendar", "plan-exchange-calendar" -> {
                if (!options.keySet()
                                .containsAll(
                                        Set.of("--exchanges", "--from", "--to", "--logical-date"))
                        || !Set.of(
                                        "--exchanges",
                                        "--from",
                                        "--to",
                                        "--logical-date",
                                        "--mode",
                                        "--resume-from")
                                .containsAll(options.keySet()))
                    throw new IllegalArgumentException(
                            "Explicit exchanges, bootstrap from, to and logical-date required");
                var exchanges = Arrays.asList(options.get("--exchanges").split(",", -1));
                var from = LocalDate.parse(options.get("--from"));
                var to = LocalDate.parse(options.get("--to"));
                var date = LocalDate.parse(options.get("--logical-date"));
                var mode =
                        options.containsKey("--mode")
                                ? Mode.valueOf(options.get("--mode").toUpperCase(Locale.ROOT))
                                : null;
                if (command.equals("plan-exchange-calendar")) {
                    if (options.containsKey("--resume-from"))
                        throw new IllegalArgumentException(
                                "Resume requires exact frozen run inputs");
                    var plan = calendarService.plan(exchanges, from, to, date, mode);
                    CliOutput.printJson(
                            Map.of(
                                    "status",
                                    "PLANNED",
                                    "executed",
                                    false,
                                    "dataVerified",
                                    false,
                                    "targetId",
                                    plan.targetId(),
                                    "checkpointCandidates",
                                    plan.checkpointCandidates(),
                                    "checkedTargetRows",
                                    plan.checkedTargetRows(),
                                    "request",
                                    CliOutput.readTree(
                                            SyncRequestIdentity.snapshotJson(plan.request()),
                                            CliOutput.Profile.PLAIN)),
                            CliOutput.Profile.JOB_DEFINITION,
                            false);
                } else {
                    Result result;
                    if (options.containsKey("--resume-from")) {
                        var frozen =
                                ExchangeCalendarSyncAdapter.definition(true)
                                        .freeze(
                                                mode,
                                                Map.of("exchanges", exchanges),
                                                from,
                                                to,
                                                date);
                        result =
                                calendarService.execute(
                                        new Plan(frozen, calendarService.targetId(), Map.of(), 0),
                                        options.get("--resume-from"));
                    } else result = calendarService.run(exchanges, from, to, date, mode);
                    CliOutput.printJson(result, CliOutput.Profile.PLAIN, false);
                    if (result.state() != SyncRunState.VERIFIED
                            && result.state() != SyncRunState.VERIFIED_EMPTY)
                        throw new IncompleteCommandException(
                                "Calendar sync incomplete: " + result.state());
                }
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
