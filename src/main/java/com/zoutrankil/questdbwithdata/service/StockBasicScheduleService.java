package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.questdbwithdata.domain.SyncScheduleDefinition;
import com.zoutrankil.questdbwithdata.repository.SyncScheduleStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Manual application entry. No timer, schedule, or exchange calendar is silently enabled. */
@Service
@org.springframework.context.annotation.Lazy
public class StockBasicScheduleService {
    private final SyncScheduleManager manager;
    private final StockBasicJobService job;
    private final StockBasicGroupService group;
    @Autowired
    public StockBasicScheduleService(SyncJobRegistry jobs, StockBasicJobService job,
            StockBasicGroupService group,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) throws Exception {
        this(jobs,job,group,ledgerPath,Clock.systemUTC(),date -> false);
    }
    StockBasicScheduleService(SyncJobRegistry jobs, StockBasicJobService job,
            StockBasicGroupService group, String ledgerPath, Clock clock,
            Predicate<LocalDate> exchangeCalendar) throws Exception {
        this.job=job; this.group=group;
        this.manager=new SyncScheduleManager(new SyncScheduleStore(Path.of(ledgerPath)),jobs,
                new SyncGroupRegistry(group.definitions(),jobs),exchangeCalendar,clock);
    }
    public void put(Path jsonFile) throws Exception {
        if (Files.size(jsonFile)>64*1024) throw new IllegalArgumentException("Schedule definition exceeds 64 KiB");
        var json=new ObjectMapper().findAndRegisterModules()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var definition=json.readValue(Files.readAllBytes(jsonFile),SyncScheduleDefinition.class);
        if (definition.dayRule()==SyncScheduleDefinition.DayRule.EXCHANGE_SESSION)
            throw new IllegalArgumentException("Exchange-session calendar owner is not registered; schedule blocked");
        codes(definition); // reject missing or unsupported runner parameters before saving
        manager.put(definition);
    }
    public SyncScheduleManager.Status status(String id) throws Exception { return manager.status(id); }
    public void setEnabled(String id,boolean enabled) throws Exception { manager.setEnabled(id,enabled); }
    public List<SyncScheduleStore.History> tick() throws Exception {
        return manager.tick((definition,slot) -> {
            var codes=codes(definition);
            if (definition.target()==SyncScheduleDefinition.Target.JOB
                    && definition.targetId().equals("data.stock_basic") && definition.targetVersion()==2) {
                var result=job.run(codes,slot.localDate());
                return new SyncScheduleManager.RunResult(result.runId(),result.state());
            }
            if (definition.target()==SyncScheduleDefinition.Target.GROUP
                    && definition.targetId().equals("group.stock_basic_manual") && definition.targetVersion()==1) {
                var result=group.run(codes,slot.localDate(),null);
                return new SyncScheduleManager.RunResult(result.runId(),result.state());
            }
            throw new IllegalArgumentException("No admitted schedule runner for target");
        });
    }
    private static List<String> codes(SyncScheduleDefinition definition) {
        if (!definition.parameters().keySet().equals(Set.of("codes")))
            throw new IllegalArgumentException("Only explicit stock-basic codes are admitted for scheduling");
        var codes=Arrays.asList(definition.parameters().get("codes").split(",",-1));
        if (codes.isEmpty() || codes.size()>20 || codes.stream().anyMatch(c -> !c.matches("[0-9]{6}\\.(SZ|SH|BJ)"))
                || new HashSet<>(codes).size()!=codes.size())
            throw new IllegalArgumentException("Bounded unique stock codes required");
        return List.copyOf(codes);
    }
}
