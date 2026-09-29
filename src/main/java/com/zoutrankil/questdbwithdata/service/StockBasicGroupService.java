package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncGroupDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Manual registered compositions; dataset owners retain all source and write logic. */
@Service
public class StockBasicGroupService {
    private final SyncJobRegistry jobs;
    private final StockBasicJobService stock;
    private final ExchangeCalendarJobService calendar;
    private final StockDetailInfoJobService stockDetail;
    private final IndexCatalogJobService indexCatalog;
    private final Path ledgerPath;
    private static final SyncGroupDefinition SAMPLE = new SyncGroupDefinition("group.stock_basic_manual", 1,
            List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_basic", 2), List.of())),
            true, false);

    @org.springframework.beans.factory.annotation.Autowired
    public StockBasicGroupService(SyncJobRegistry jobs, StockBasicJobService stock, ExchangeCalendarJobService calendar,
            StockDetailInfoJobService stockDetail, IndexCatalogJobService indexCatalog,
            @Value("${app.sync.ledger-path:var/sync-ledger.sqlite3}") String ledgerPath) {
        this.jobs=jobs; this.stock=stock; this.calendar=calendar; this.stockDetail=stockDetail;
        this.indexCatalog=indexCatalog;
        this.ledgerPath=Path.of(ledgerPath).toAbsolutePath().normalize();
    }
    public StockBasicGroupService(SyncJobRegistry jobs,StockBasicJobService stock,ExchangeCalendarJobService calendar,
                                 StockDetailInfoJobService detail,String ledgerPath) {
        this(jobs,stock,calendar,detail,null,ledgerPath);
    }
    public StockBasicGroupService(SyncJobRegistry jobs, StockBasicJobService stock, ExchangeCalendarJobService calendar,
            String ledgerPath) {
        this(jobs,stock,calendar,null,ledgerPath);
    }
    public StockBasicGroupService(SyncJobRegistry jobs,StockBasicJobService stock,String ledgerPath) {
        this(jobs,stock,null,ledgerPath);
    }
    public List<SyncGroupDefinition> definitions() {
        if(calendar==null && stockDetail==null && indexCatalog==null) return List.of(SAMPLE);
        var definitions=new ArrayList<SyncGroupDefinition>();definitions.add(SAMPLE);
        if(indexCatalog!=null) definitions.add(new SyncGroupDefinition("group.index_catalog_manual",1,
                List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.index",1),List.of())),true,false));
        if(calendar!=null) {
        var ref=new SyncJobDefinition.JobRef("data.exchange_calendar",1);
        var member=new SyncGroupDefinition.Member(ref,List.of());
        definitions.add(new SyncGroupDefinition("group.exchange_calendar_manual",1,List.of(member),true,false));
        definitions.add(
                new SyncGroupDefinition("group.reference_manual",1,List.of(member,
                        new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_basic",2),List.of(ref))),true,false));
        }
        if(stockDetail!=null) definitions.add(new SyncGroupDefinition("group.stock_detail_manual",1,
                List.of(new SyncGroupDefinition.Member(new SyncJobDefinition.JobRef("data.stock_detail_info",1),List.of())),
                true,false));
        return List.copyOf(definitions);
    }
    public SyncGroupRunner.Result run(List<String> codes, LocalDate logicalDate, String priorGroupRunId)
            throws Exception {
        String targetId=stock.targetId();
        var input=new SyncGroupRunner.MemberInput(null,Map.of("codes",List.copyOf(codes)),null,targetId);
        var request=new SyncGroupRunner.Request(logicalDate,SyncGroupRunner.Window.none(),
                Map.of("data.stock_basic",input));
        return execute(SAMPLE.groupId(),SAMPLE.version(),request,priorGroupRunId);
    }
    public SyncGroupRunner.Result execute(String groupId,int version,SyncGroupRunner.Request request,
                                         String priorGroupRunId) throws Exception {
        var registry=new SyncGroupRegistry(definitions(),jobs);
        var runner=new SyncGroupRunner(registry,jobs,new SyncRunLedger(ledgerPath));
        String groupRunId="group-run-"+UUID.randomUUID();
        SyncGroupRunner.ChildExecutor child=new SyncGroupRunner.ChildExecutor() {
          public SyncJobRunner.Result execute(String childId,String parentId,String priorChild,String target,
                  SyncJobDefinition.FrozenRequest frozen) throws Exception {
            if(frozen.definition().jobId().equals("data.exchange_calendar") && calendar!=null)
                return calendar.runAsGroupChild(childId,parentId,priorChild,target,frozen);
            if(frozen.definition().jobId().equals("data.index") && indexCatalog!=null) {
                if(priorChild!=null) throw new IllegalStateException("Uncertain catalog child needs explicit reconciliation");
                return indexCatalog.runAsGroupChild(childId,parentId,target,frozen);
            }
            if(frozen.definition().jobId().equals("data.stock_detail_info") && stockDetail!=null) {
                if(priorChild!=null) throw new IllegalStateException("Static uncertain child needs explicit reconciliation");
                return stockDetail.runAsGroupChild(childId,parentId,target,frozen);
            }
            if (!frozen.definition().jobId().equals("data.stock_basic"))
                throw new IllegalArgumentException("Unsupported group member");
            @SuppressWarnings("unchecked")
            List<String> childCodes=(List<String>) frozen.parameters().get("codes");
            return stock.runAsGroupChild(childId,parentId,priorChild,target,childCodes,frozen.logicalDate());
          }
          public String revalidateCompleted(String priorChild,String target,
                  SyncJobDefinition.FrozenRequest frozen) throws Exception {
              if(frozen.definition().jobId().equals("data.index") && indexCatalog!=null)
                  return indexCatalog.revalidateGroupChild(priorChild,target,frozen);
              if(frozen.definition().jobId().equals("data.exchange_calendar") && calendar!=null)
                  return calendar.revalidateGroupChild(priorChild,target,frozen,groupRunId);
              if(frozen.definition().jobId().equals("data.stock_detail_info") && stockDetail!=null)
                  return stockDetail.revalidateGroupChild(priorChild,target,frozen);
              if(!frozen.definition().jobId().equals("data.stock_basic"))
                  throw new IllegalArgumentException("Unsupported group member");
              return stock.revalidateGroupChild(priorChild,target,frozen,groupRunId);
          }
        };
        return priorGroupRunId==null
                ? runner.run(groupRunId,groupId,version,request,child)
                : runner.resume(groupRunId,priorGroupRunId,groupId,version,request,child);
    }
    public SyncGroupRunner.Result runPlan(SyncGroupPlan plan,
                                         String priorGroupRunId) throws Exception {
        var members=new LinkedHashMap<String,SyncGroupRunner.MemberInput>();
        for(var frozen:plan.requests()) {
            String jobId=frozen.definition().jobId(); String target;
            if(jobId.equals("data.exchange_calendar") && calendar!=null) {
                if(priorGroupRunId==null) {
                    @SuppressWarnings("unchecked") var exchanges=(List<String>)frozen.parameters().get("exchanges");
                    var incremental=calendar.plan(exchanges,frozen.from(),frozen.to(),frozen.logicalDate(),frozen.mode());
                    frozen=incremental.request();target=incremental.targetId();
                } else target=calendar.targetId();
            } else if(jobId.equals("data.stock_basic")) target=stock.targetId();
            else if(jobId.equals("data.index") && indexCatalog!=null) {
                if(priorGroupRunId==null) target=indexCatalog.targetId();
                else {
                    var ledger=SyncRunLedger.openReadOnly(ledgerPath);
                    var prior=ledger.groupMembers(priorGroupRunId).stream().filter(m->m.jobId().equals("data.index")).toList();
                    if(prior.size()!=1 || prior.getFirst().childRunId()==null)
                        throw new IllegalStateException("Prior catalog child identity required for group recovery");
                    target=ledger.getRun(prior.getFirst().childRunId()).targetId();
                }
            }
            else if(jobId.equals("data.stock_detail_info") && stockDetail!=null) target=stockDetail.targetId();
            else throw new IllegalArgumentException("Unsupported group member");
            members.put(jobId,new SyncGroupRunner.MemberInput(frozen.mode(),frozen.parameters(),
                    new SyncGroupRunner.Window(frozen.from(),frozen.to()),target));
        }
        return execute(plan.definition().groupId(),plan.definition().version(),
                new SyncGroupRunner.Request(plan.logicalDate(),SyncGroupRunner.Window.none(),members),priorGroupRunId);
    }
}
