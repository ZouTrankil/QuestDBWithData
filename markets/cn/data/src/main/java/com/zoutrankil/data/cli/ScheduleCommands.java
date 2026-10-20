package com.zoutrankil.data.cli;

import com.zoutrankil.data.service.StockBasicScheduleService;
import com.zoutrankil.data.service.StockBasicScheduleService.State;

import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class ScheduleCommands implements CliCommandFamily {
    private static final Set<String> COMMANDS =
            Set.of("schedule-put", "schedule-status", "schedule-enable", "schedule-tick");
    private final StockBasicScheduleService scheduleService;

    public ScheduleCommands(@Lazy StockBasicScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override
    public Set<String> commands() {
        return COMMANDS;
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "schedule-put" -> {
                if (!options.keySet().equals(Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON required");
                scheduleService.put(Path.of(options.get("--request")));
                CliOutput.printJson(
                        Map.of("status", "STORED", "executed", false, "dataVerified", false),
                        CliOutput.Profile.PLAIN,
                        false);
            }
            case "schedule-status" -> {
                if (!options.keySet().equals(Set.of("--id")))
                    throw new IllegalArgumentException("Explicit --id required");
                CliOutput.printJson(
                        scheduleService.status(options.get("--id")),
                        CliOutput.Profile.MODULES,
                        true);
            }
            case "schedule-enable" -> {
                if (!options.keySet().equals(Set.of("--id", "--enabled"))
                        || !Set.of("true", "false").contains(options.get("--enabled")))
                    throw new IllegalArgumentException(
                            "Explicit --id and boolean --enabled required");
                scheduleService.setEnabled(
                        options.get("--id"), Boolean.parseBoolean(options.get("--enabled")));
                CliOutput.printJson(
                        Map.of(
                                "status",
                                "CONFIGURED",
                                "scheduleId",
                                options.get("--id"),
                                "enabled",
                                Boolean.parseBoolean(options.get("--enabled")),
                                "executed",
                                false,
                                "dataVerified",
                                false),
                        CliOutput.Profile.PLAIN,
                        false);
            }
            case "schedule-tick" -> {
                if (!options.isEmpty())
                    throw new IllegalArgumentException("schedule-tick has no options");
                var outcomes = scheduleService.tick();
                CliOutput.printJson(outcomes, CliOutput.Profile.MODULES, true);
                if (outcomes.stream()
                        .anyMatch(
                                o ->
                                        o.state() != State.VERIFIED
                                                && o.state() != State.VERIFIED_EMPTY))
                    throw new IncompleteCommandException(
                            "One or more schedule slots were not verified");
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
