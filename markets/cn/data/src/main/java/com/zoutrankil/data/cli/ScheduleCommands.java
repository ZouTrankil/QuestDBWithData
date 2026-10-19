package com.zoutrankil.data.cli;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;

/** Commands and service dependencies for this CLI family. */
@Component
@ConditionalOnNotWebApplication
public final class ScheduleCommands implements CliCommandFamily {
    private static final java.util.Set<String> COMMANDS = java.util.Set.of(
            "schedule-put", "schedule-status", "schedule-enable",
            "schedule-tick");
    private final com.zoutrankil.data.service.StockBasicScheduleService scheduleService;

    public ScheduleCommands(@org.springframework.context.annotation.Lazy com.zoutrankil.data.service.StockBasicScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Override public java.util.Set<String> commands() { return COMMANDS; }

    @Override public void execute(String command, Map<String, String> options) throws Exception {
        switch (command) {
            case "schedule-put" -> {
                if (!options.keySet().equals(java.util.Set.of("--request")))
                    throw new IllegalArgumentException("Explicit --request JSON required");
                scheduleService.put(Path.of(options.get("--request")));
                CliOutput.printJson(Map.of(
                        "status","STORED","executed",false,"dataVerified",false), CliOutput.Profile.PLAIN, false);
            }
            case "schedule-status" -> {
                if (!options.keySet().equals(java.util.Set.of("--id")))
                    throw new IllegalArgumentException("Explicit --id required");
                CliOutput.printJson(scheduleService.status(options.get("--id")), CliOutput.Profile.MODULES, true);
            }
            case "schedule-enable" -> {
                if (!options.keySet().equals(java.util.Set.of("--id","--enabled"))
                        || !java.util.Set.of("true","false").contains(options.get("--enabled")))
                    throw new IllegalArgumentException("Explicit --id and boolean --enabled required");
                scheduleService.setEnabled(options.get("--id"),Boolean.parseBoolean(options.get("--enabled")));
                CliOutput.printJson(Map.of("status","CONFIGURED",
                        "scheduleId",options.get("--id"),"enabled",Boolean.parseBoolean(options.get("--enabled")),
                        "executed",false,"dataVerified",false), CliOutput.Profile.PLAIN, false);
            }
            case "schedule-tick" -> {
                if (!options.isEmpty()) throw new IllegalArgumentException("schedule-tick has no options");
                var outcomes=scheduleService.tick();
                CliOutput.printJson(outcomes, CliOutput.Profile.MODULES, true);
                if (outcomes.stream().anyMatch(o -> o.state()!=com.zoutrankil.data.service.StockBasicScheduleService.State.VERIFIED
                        && o.state()!=com.zoutrankil.data.service.StockBasicScheduleService.State.VERIFIED_EMPTY))
                    throw new IncompleteCommandException("One or more schedule slots were not verified");
            }
            default -> throw new IllegalArgumentException(CliUsage.text());
        }
    }
}
