package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncGroupDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Manual one-member sample showing group composition without adding another dataset adapter. */
@Service
public class StockBasicGroupService {
    private final SyncJobRegistry jobs;
    private final StockBasicJobService stock;
    private final Path ledgerPath;
    private static final SyncGroupDefinition SAMPLE = new SyncGroupDefinition("group.stock_basic_manual", 1,
            List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_basic", 2), List.of())),
            true, false);

    public StockBasicGroupService(SyncJobRegistry jobs, StockBasicJobService stock,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jobs=jobs; this.stock=stock; this.ledgerPath=Path.of(ledgerPath).toAbsolutePath().normalize();
    }
    public List<SyncGroupDefinition> definitions() { return List.of(SAMPLE); }
    public SyncGroupRunner.Result run(List<String> codes, LocalDate logicalDate, String priorGroupRunId)
            throws Exception {
        var registry=new SyncGroupRegistry(definitions(),jobs);
        String targetId=stock.targetId();
        var input=new SyncGroupRunner.MemberInput(null,Map.of("codes",List.copyOf(codes)),null,targetId);
        var request=new SyncGroupRunner.Request(logicalDate,SyncGroupRunner.Window.none(),
                Map.of("data.stock_basic",input));
        var runner=new SyncGroupRunner(registry,jobs,new SyncRunLedger(ledgerPath));
        String groupRunId="group-run-"+UUID.randomUUID();
        SyncGroupRunner.ChildExecutor child=(childId,parentId,priorChild,target,frozen)-> {
            if (!frozen.definition().jobId().equals("data.stock_basic"))
                throw new IllegalArgumentException("Unsupported sample group member");
            @SuppressWarnings("unchecked")
            List<String> childCodes=(List<String>) frozen.parameters().get("codes");
            return stock.runAsGroupChild(childId,parentId,priorChild,target,childCodes,frozen.logicalDate());
        };
        return priorGroupRunId==null
                ? runner.run(groupRunId,SAMPLE.groupId(),SAMPLE.version(),request,child)
                : runner.resume(groupRunId,priorGroupRunId,SAMPLE.groupId(),SAMPLE.version(),request,child);
    }
}
