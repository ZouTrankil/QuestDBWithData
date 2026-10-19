package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.application.FactorDefinitionCatalog;
import com.zoutrankil.data.factor.storage.QuestDbL2FactorPanel;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared raw L2 computation for parity/performance measurement; no publication side effects. */
public final class L2FactorPanelComputation {
    private final com.zoutrankil.data.domain.temporal.MarketTime marketTime;
    private final JdbcTemplate jdbc;
    public L2FactorPanelComputation(JdbcTemplate jdbc) { this(jdbc,com.zoutrankil.market.cn.time.CnMarketTime.system()); }
    /** 生产装配和边界测试可以明确传入 A 股时钟。 */
    public L2FactorPanelComputation(JdbcTemplate jdbc,com.zoutrankil.data.domain.temporal.MarketTime marketTime) {
        this.jdbc=jdbc; this.marketTime=java.util.Objects.requireNonNull(marketTime).requireMarket(com.zoutrankil.data.domain.temporal.MarketId.CN);
    }
    public Map<String,Object> compute(Map<String,String> options) throws Exception {
        if(!options.keySet().containsAll(Set.of("--history-from","--from","--to","--factors","--output"))
                || !Set.of("--history-from","--from","--to","--factors","--output","--pools","--receipt-directory","--evidence-output","--evidence-directory").containsAll(options.keySet())
                || options.containsKey("--pools")!=options.containsKey("--receipt-directory"))
            throw new IllegalArgumentException("Expected --history-from DATE --from DATE --to DATE --factors ID,ID --output PATH");
        Path path=Path.of(options.get("--output")).toAbsolutePath();
        if(Files.exists(path))throw new IllegalArgumentException("Output already exists");
        if(options.containsKey("--evidence-output") && options.containsKey("--evidence-directory"))
            throw new IllegalArgumentException("Select a single evidence file or a bundle directory");
        String evidenceTarget=options.getOrDefault("--evidence-output",options.get("--evidence-directory"));
        if(evidenceTarget!=null && (!options.containsKey("--pools")
                || !List.of(options.get("--pools").split(",",-1)).contains("all_market")
                || Files.exists(Path.of(evidenceTarget))))
            throw new IllegalArgumentException("L2 evidence requires all_market scope and a new output path");
        var catalog=FactorDefinitionCatalog.load();
        var ids=List.of(options.get("--factors").split(",",-1));
        if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Duplicate factor selection");
        var definitions=ids.stream().map(catalog::require).toList();
        var history=LocalDate.parse(options.get("--history-from"));
        var from=LocalDate.parse(options.get("--from"));var through=LocalDate.parse(options.get("--to"));
        long started=System.nanoTime();
        var snapshot=new QuestDbL2FactorPanel(jdbc).read(history,from,through,definitions,1_000_000);
        double readMillis=(System.nanoTime()-started)/1_000_000.0;
        long outputRows=snapshot.outputDateRows().values().stream().mapToLong(Long::longValue).sum();
        if(outputRows*ids.size()>2_000_000)throw new IllegalArgumentException("L2 output exceeds 2000000 values");
        started=System.nanoTime();var sections=snapshot.panel().compute(definitions);
        double computeMillis=(System.nanoTime()-started)/1_000_000.0;
        var sourceState=new ArrayList<>(snapshot.sourceState());
        com.zoutrankil.data.factor.application.NativePoolFactors.Result poolResult=null;
        var scope=new ArrayList<com.zoutrankil.data.factor.application.L2SourceScope.Decision>();
        var receipts=new TreeMap<LocalDate,com.zoutrankil.data.factor.application.L2SourceScope.Receipt>();
        long poolStart=System.nanoTime();
        if(options.containsKey("--pools")) {
            var poolIds=List.of(options.get("--pools").split(",",-1));
            if(new HashSet<>(poolIds).size()!=poolIds.size())throw new IllegalArgumentException("Duplicate pools");
            Path receiptDirectory=Path.of(options.get("--receipt-directory"));
            if(!Files.isDirectory(receiptDirectory))throw new IllegalArgumentException("Existing L2 receipt directory required");
            for(var date:snapshot.outputDateRows().keySet())receipts.put(date,
                    com.zoutrankil.data.factor.application.L2SourceScope.inspect(receiptDirectory,date));
            var market=new com.zoutrankil.data.factor.storage.QuestDbDailyFactorPanel(jdbc).read(from,through,460,5_000_000);
            var membership=new com.zoutrankil.data.factor.storage.QuestDbStockMembership(jdbc).read(through,1_000_000);
            sourceState.addAll(market.sourceState());sourceState.addAll(membership.sourceState());
            var features=com.zoutrankil.data.factor.engine.DailyFactorPanel.selectRaw(market.securities(),from,through);
            poolResult=com.zoutrankil.data.factor.application.NativePoolFactors.computeL2(market.securities(),features,
                    membership,poolIds,ids,catalog,sections,options.containsKey("--evidence-directory")?2_000_000:100_000);
            for(var date:snapshot.outputDateRows().keySet()) {
                var pools=new ArrayList<com.zoutrankil.data.factor.application.L2SourceScope.Pool>();
                for(var row:poolResult.coverage())if(date.equals(row.get("date"))) {
                    @SuppressWarnings("unchecked") var members=(List<com.zoutrankil.data.factor.engine.PointInTimeStockPools.Member>)row.get("members");
                    var included=new HashSet<String>();members.stream().filter(com.zoutrankil.data.factor.engine.PointInTimeStockPools.Member::included)
                            .forEach(member->included.add(member.symbol()));
                    pools.add(new com.zoutrankil.data.factor.application.L2SourceScope.Pool((String)row.get("pool"),Boolean.TRUE.equals(row.get("available")),included));
                }
                scope.add(com.zoutrankil.data.factor.application.L2SourceScope.assess(receipts.get(date),pools));
                if(!receipts.get(date).equals(com.zoutrankil.data.factor.application.L2SourceScope.inspect(receiptDirectory,date)))
                    throw new IllegalStateException("L2 source receipt changed during computation");
            }
        }
        double poolMillis=poolResult==null?0:(System.nanoTime()-poolStart)/1_000_000.0;
        new FactorPanelComputation(jdbc).requireSourceStateUnchanged(sourceState);
        var report=new LinkedHashMap<String,Object>();
        report.put("schema","java.l2_factor_diagnostic.v1");report.put("historyFrom",history);
        report.put("from",from);report.put("through",through);report.put("factors",ids);
        report.put("sourceRows",snapshot.sourceRows());report.put("retainedRows",snapshot.panel().retainedRows());
        report.put("selectedColumns",snapshot.columns());report.put("sourceState",sourceState);
        report.put("outputDateRows",snapshot.outputDateRows());report.put("outputValues",outputRows*ids.size());
        report.put("readMillis",readMillis);report.put("computeMillis",computeMillis);
        report.put("featureVersion",QuestDbL2FactorPanel.FEATURE_VERSION);report.put("parserVersion",QuestDbL2FactorPanel.PARSER_VERSION);
        report.put("catalogSha256",catalog.sourceConfigSha256());
        report.put("kernelSha256",com.zoutrankil.data.factor.application.FactorComputationIdentity.sha256(L2FactorPanelComputation.class));
        report.put("sourceComplete",false);report.put("publicationReady",false);
        report.put("sourceScope","all observed L2 symbols; pool membership and archive receipts not yet applied");
        if(poolResult!=null) {
            report.put("sourceScope","raw values retain observed symbols; pool values use frozen stock membership and observed L2 keys");
            report.put("poolResult",poolResult);report.put("sourceScopeDecisions",scope);report.put("poolReadAndComputeMillis",poolMillis);
            report.put("scopePublishable",scope.stream().allMatch(com.zoutrankil.data.factor.application.L2SourceScope.Decision::publishable));
        }
        if(evidenceTarget!=null) {
            var identity=new LinkedHashMap<String,Object>();
            identity.put("sourceState",sourceState);identity.put("sourceScopeDecisions",scope);
            identity.put("historyFrom",history);identity.put("from",from);identity.put("through",through);
            identity.put("factors",definitions);identity.put("kernelSha256",report.get("kernelSha256"));
            identity.put("catalogSha256",catalog.sourceConfigSha256());
            identity.put("allMarketMembership",poolResult.coverage().stream().filter(row->"all_market".equals(row.get("pool")))
                    .map(row->Map.of("date",row.get("date"),"membershipHash",row.get("membershipHash"))).toList());
            String fingerprint=FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(identity));
            if(options.containsKey("--evidence-directory")) {
                var bundle=com.zoutrankil.data.factor.application.L2ObservationBundle.writeNew(Path.of(evidenceTarget),poolResult.sections(),scope,
                        ids,catalog,marketTime.inBusinessZone(marketTime.now()).toLocalDateTime(),fingerprint);
                report.put("observationRows",bundle.rows());report.put("observationBundle",bundle);
            } else {
                var observations=com.zoutrankil.data.factor.application.L2GlobalObservations.fromPools(poolResult.sections(),scope,ids,catalog,
                        marketTime.inBusinessZone(marketTime.now()).toLocalDateTime());
                String sha=com.zoutrankil.data.factor.application.FactorObservationEvidence.writeNew(Path.of(evidenceTarget),observations,false,fingerprint);
                report.put("observationRows",observations.size());report.put("observationEvidenceSha256",sha);
            }
            report.put("inputIdentity",identity);report.put("inputFingerprint",fingerprint);
        }
        report.put("sections",sections);
        Files.createDirectories(path.getParent());
        FileEvidenceStore.writeNew(path,JobDefinitionJson.mapper().writeValueAsBytes(report));
        report.remove("sections");report.remove("poolResult");return Collections.unmodifiableMap(report);
    }
}
