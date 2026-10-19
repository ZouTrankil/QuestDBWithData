package com.zoutrankil.data.strategy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.strategy.input.QuestDbStrategyInputs;
import com.zoutrankil.data.strategy.input.StrategySourceCoverage;
import com.zoutrankil.data.strategy.input.StrategyCoverageAssembler;
import com.zoutrankil.data.strategy.publication.ScoreArtifactPublisher;
import com.zoutrankil.data.strategy.publication.NativeMarketInputPackagePublisher;
import com.zoutrankil.data.strategy.publication.ScoreArtifactPublisher.Publication;
import com.zoutrankil.data.strategy.score.*;
import com.zoutrankil.data.strategy.store.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Java 负责来源准备、按已验收权重评分、成对发布及正式方案存储。 */
@Service @Lazy
public final class MainStrategyDataService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(MainStrategyDataService.class);
    private static final Set<String> SCORE_IDENTITIES=Set.of("trade_date","ts","timestamp","ts_code","symbol");
    private final QuestDbStrategyInputs reader;
    private final ScoreArtifactPublisher publisher;
    private final NativeMarketInputPackagePublisher marketPackages;
    private final OfficialPlanStore plans;
    private final Path recipe, pins, allowedRoot, planDatabase;
    private final LocalDate start;
    private final String coverageFile,coverageSha256;
    private String coverageInputs="",coverageInputsSha256="";
    @org.springframework.beans.factory.annotation.Autowired
    public void configureCoverageAssembly(@Value("${app.sync.batch.coverage-inputs:}")String file,@Value("${app.sync.batch.coverage-inputs-sha256:}")String sha) {
        if(file.isBlank()!=sha.isBlank()||!sha.isBlank()&&!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("覆盖组装输入和摘要需成对配置");coverageInputs=file;coverageInputsSha256=sha;
    }
    public MainStrategyDataService(JdbcTemplate jdbc,String researchRoot,String recipe,String pins,String allowedRoot,
            String planDatabase,String start,long rowBudget) {
        this(jdbc,researchRoot,recipe,pins,allowedRoot,planDatabase,start,rowBudget,"","");
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MainStrategyDataService(JdbcTemplate jdbc,
            @Value("${app.sync.batch.research-root:F:/repo/engine/back-monitor/artifacts/quant_platform}") String researchRoot,
            @Value("${app.sync.batch.promoted-recipe:F:/repo/engine/back-monitor/artifacts/factor_research/l2/etf_only_top2_promoted_recipe/promoted_recipe.json}") String recipe,
            @Value("${app.sync.batch.promoted-recipe-pins:F:/repo/engine/back-monitor/artifacts/factor_research/l2/etf_only_top2_promoted_recipe/accepted-sha256.json}") String pins,
            @Value("${app.sync.batch.promoted-artifact-root:F:/repo/engine/back-monitor}") String allowedRoot,
            @Value("${app.sync.batch.official-plan-db:F:/repo/engine/back-monitor/artifacts/factor_research/l2/etf_only_fusion_main_daily/etf_only_paper/main_strategy_plans.sqlite3}") String planDatabase,
            @Value("${app.sync.batch.score-start-date:2025-07-01}") String start,
            @Value("${app.sync.batch.score-source-row-budget:5000000}") long rowBudget,
            @Value("${app.sync.batch.source-coverage:}") String coverageFile,
            @Value("${app.sync.batch.source-coverage-sha256:}") String coverageSha256) {
        this.coverageFile=coverageFile;this.coverageSha256=coverageSha256;
        reader=new QuestDbStrategyInputs(jdbc,rowBudget,coverageFile,coverageSha256);publisher=new ScoreArtifactPublisher(Path.of(researchRoot));
        marketPackages=new NativeMarketInputPackagePublisher(Path.of(researchRoot));
        this.recipe=Path.of(recipe).toAbsolutePath().normalize();this.pins=Path.of(pins).toAbsolutePath().normalize();
        this.allowedRoot=Path.of(allowedRoot).toAbsolutePath().normalize();this.planDatabase=Path.of(planDatabase).toAbsolutePath().normalize();
        plans=new OfficialPlanStore(this.planDatabase);this.start=LocalDate.parse(start);
    }
    public String definitionFingerprint()throws Exception {
        String base=com.zoutrankil.batch.domain.RunRequest.hash("java.main_strategy.data.v1",start.toString(),recipe.toString(),stamp(recipe),pins.toString(),stamp(pins),allowedRoot.toString(),planDatabase.toString(),coverageFile,coverageSha256);
        return coverageInputs.isBlank()?base:com.zoutrankil.batch.domain.RunRequest.hash(base,coverageInputs,coverageInputsSha256);
    }
    public Map<String,Object> status()throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("producer","java.main_strategy.data");result.put("scoreStart",start);result.put("promotedRecipe",recipe.toString());result.put("recipePins",pins.toString());result.put("planDatabase",planDatabase.toString());
        var absent=new ArrayList<String>();for(Path p:List.of(recipe,pins,planDatabase))if(!Files.isRegularFile(p))absent.add(p.toString());result.put("missingArtifacts",absent);
        try {reader.revalidateCoverage();result.put("sourceCoverage","已核验");}
        catch(Exception e){result.put("sourceCoverage",Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
        try {var p=publisher.latest(null);result.put("scores",p);result.put("marketInputPackage",marketPackages.requireLatest(p));}catch(Exception e){result.put("dataReadiness",Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
        return result;
    }
    /**
     * 读取有界原始输入，使用摘要固定的已验收方案计算评分，并发布评分、覆盖率和市场输入包。
     * 提交产物前重查来源版本及方案摘要，防止计算过程中发生的数据变化被悄然发布。
     */
    public Publication prepareScores(LocalDate target,String runId)throws Exception {
        FrozenPromotedRecipe frozen=recipe();
        StrategySourceCoverage coverage;
        if(coverageInputs.isBlank())coverage=new StrategySourceCoverage(coverageFile,coverageSha256);
        else {
            var assembled=StrategyCoverageAssembler.assemble(Path.of(coverageInputs),coverageInputsSha256,
                    Path.of("var/strategy-source-coverage").resolve(UUID.randomUUID()+".json"),reader.clocks());
            coverage=new StrategySourceCoverage(assembled.path().toString(),assembled.sha256());
        }
        var prepared=calculateScores(target,frozen,coverage);
        var output=prepared.output();
        ScoreArtifactPublisher.PublicationGate gate=()->{
            coverage.revalidate();
            if(!prepared.proof().get("sourceClocks").equals(reader.clocks())||!prepared.proof().get("backtestState").equals(reader.backtestState()))throw new IllegalStateException("Sources or backtest result changed during Java data preparation");
            if(!frozen.artifactSha256().equals(recipe().artifactSha256()))throw new IllegalStateException("Frozen recipe changed during Java data preparation");
        };
        gate.check();
        var proof=new LinkedHashMap<String,Object>(prepared.proof());proof.put("recipeHashes",frozen.artifactSha256());proof.put("recipeCodeRevision",frozen.recipe().codeRevision());proof.put("scoreProvenance",output.provenance());proof.put("optionalChip","Empty input permitted by existing contract");
        proof.put("appliedFactorWeights",applied(output.factorWeights()));proof.put("appliedFactorStates",applied(output.factorStates()));
        var panel=frames(output.panel(),true);
        LOG.info("Publishing complete Java score pair: panel rows={}, coverage rows={}",panel.size(),output.coverage().size());
        var published=publisher.publish(runId,panel,frames(output.coverage(),false),prepared.signalDates(),frozen.artifactSha256().get("recipe"),proof,gate);
        LOG.info("Published complete Java score pair; publishing native market input package for {}",runId);
        marketPackages.publish(runId,start,target,prepared.calendar(),prepared.etfDaily(),prepared.marketContext(),panel,published,proof,gate);
        return published;
    }
    private record PreparedInputs(EtfOnlyTop2ScoreProducer.Inputs inputs,Map<String,Object> proof,
            List<Map<String,Object>> calendar,List<Map<String,Object>> etfDaily,List<Map<String,Object>> marketContext) {}
    private record CalculatedScores(EtfOnlyTop2ScoreProducer.Output output,List<LocalDate> signalDates,Map<String,Object> proof,
            List<Map<String,Object>> calendar,List<Map<String,Object>> etfDaily,List<Map<String,Object>> marketContext) {}
    /** 评分输入在展开发布结果前离开作用域，同时保留原批准清单供发布前复核。 */
    private CalculatedScores calculateScores(LocalDate target,FrozenPromotedRecipe frozen,StrategySourceCoverage coverage)throws Exception {
        var prepared=prepareInputs(target,coverage);
        var output=new EtfOnlyTop2ScoreProducer().produce(prepared.inputs(),frozen);
        return new CalculatedScores(output,prepared.inputs().signalDates(),prepared.proof(),prepared.calendar(),prepared.etfDaily(),prepared.marketContext());
    }
    /** 原始宽表仅在准备阶段持有，评分前可回收未使用的列。 */
    private PreparedInputs prepareInputs(LocalDate target,StrategySourceCoverage coverage)throws Exception {
        var raw=reader.read(start,target,460,coverage);
        var metadata=new LinkedHashMap<String,L2StockScoreProducer.StockMetadata>();
        for(var row:raw.tables().get("stock_metadata")) {
            String code=QuestDbStrategyInputs.date(row.get("trade_date"))+"|"+text(row,"ts_code");if(metadata.put(code,new L2StockScoreProducer.StockMetadata(text(row,"name"),text(row,"industry")))!=null)throw new IllegalStateException("Duplicate dated stock metadata business key");
        }
        var holdings=new ArrayList<EtfOnlyTop2ScoreProducer.Holding>();
        for(var row:raw.tables().get("etf_portfolio"))holdings.add(new EtfOnlyTop2ScoreProducer.Holding(text(row,"ts_code"),text(row,"symbol"),QuestDbStrategyInputs.date(row.get("ann_date")),QuestDbStrategyInputs.date(row.get("end_date")),number(row.get("stk_mkv_ratio")),number(row.get("mkv"))));
        var references=EtfOnlyTop2ScoreProducer.prepareEtfReference(raw.tables().get("etf_reference"),false);
        var versions=new TreeMap<>(raw.sourceVersions());versions.put("l2",versions.get("l2_raw"));versions.put("market_sentiment",versions.get("sentiment"));versions.put("chip","optional-empty-v1");
        var codes=references.stream().map(EtfOnlyTop2ScoreProducer.EtfReference::symbol).collect(java.util.stream.Collectors.toSet());
        LOG.info("Preparing complete ETF factor history: raw rows={}, eligible codes={}",raw.tables().get("etf_factor").size(),codes.size());
        var factors=EtfOnlyTop2ScoreProducer.prepareEtfFactor(rows(raw,"etf_factor","trade_date","ts_code"),rows(raw,"etf_daily","timestamp","ts_code"),codes,target);
        LOG.info("Prepared complete ETF factor history: rows={}",factors.size());
        var sentiment=EtfOnlyTop2ScoreProducer.prepareMarketSentiment(rows(raw,"sentiment","trade_date",null),start,target);
        var inputs=new EtfOnlyTop2ScoreProducer.Inputs(rows(raw,"stock_daily","trade_date","ts_code"),rows(raw,"l2_raw","ts","symbol"),metadata,factors,references,holdings,List.of(),sentiment,raw.signalDates(),versions,target,true);
        return new PreparedInputs(inputs,raw.proof(),raw.tables().get("calendar"),raw.tables().get("etf_daily"),raw.tables().get("market_context"));
    }
    public Publication scores(LocalDate required)throws Exception {
        var frozen=recipe();
        var p=required==null?publisher.latest(null):publisher.forDate(required);
        requireRecipeBinding(p,frozen);
        marketPackages.requireByRun(p);
        if(!frozen.artifactSha256().equals(recipe().artifactSha256()))throw new IllegalStateException("Accepted recipe changed while resolving Java inputs");
        return p;
    }
    public Map<String,Object> inputPackage(LocalDate required)throws Exception {
        var p=marketPackages.requireByRun(scores(required));var ref=p.manifestRef();
        var document=new ObjectMapper().readTree(Files.readAllBytes(ref.path()));
        var result=new ObjectMapper().convertValue(document,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        result.put("manifest",Map.of("uri",ref.uri(),"version",ref.version(),"content_hash",ref.contentHash(),"media_type","application/json"));
        result.put("node_id","local");result.put("data_location",ref.path().toString());return result;
    }
    public void requireStart(LocalDate requested){if(requested!=null&&!requested.equals(start))throw new IllegalArgumentException("Requested score start differs from the configured frozen strategy start: "+start);}
    public void validateScoreSources(Publication p)throws Exception {
        var mapper=new ObjectMapper();var proof=mapper.readTree(Files.readAllBytes(publisher.provenancePath(p)));
        if(!proof.path("sourceClocks").toString().equals(mapper.writeValueAsString(reader.clocks()))||!proof.path("backtestState").toString().equals(mapper.writeValueAsString(reader.backtestState())))
            throw new IllegalStateException("Current daily score source identity, schema, values frontier or backtest result changed after publication");
    }
    public List<Path> scoreEvidence(Publication p)throws Exception {var files=new ArrayList<Path>(List.of(p.panelRef().path(),p.coverageRef().path(),publisher.provenancePath(p)));files.addAll(marketPackages.evidence(marketPackages.requireLatest(p)));return List.copyOf(files);}
    public StoredOfficialPlan importPlan(Path source,String expectedSha256)throws Exception {
        return importPlan(source,expectedSha256,null);
    }
    public StoredOfficialPlan importPlan(Path source,String expectedSha256,String scoreRunId)throws Exception {
        Path canonicalSource=source.toAbsolutePath().normalize();
        if(!canonicalSource.startsWith(allowedRoot)||!canonicalSource.toRealPath().startsWith(allowedRoot.toRealPath()))throw new IllegalArgumentException("Official source must stay inside the configured promoted artifact root");
        if(Files.size(canonicalSource)>8*1024*1024)throw new IllegalArgumentException("Canonical plan source exceeds eight MiB");
        var sourceDocument=new ObjectMapper().readTree(Files.readAllBytes(canonicalSource));
        LocalDate signal=LocalDate.parse(sourceDocument.path("signal_date").asText());
        var previous=plans.latestOfficialWeekly();
        if((scoreRunId==null||scoreRunId.isBlank())&&previous.isPresent()&&previous.get().plan().signalDate().equals(signal)&&previous.get().provenance().isPresent()){
            var bound=previous.get().provenance().orElseThrow();requirePlan(signal);
            return plans.importSnapshot(canonicalSource,expectedSha256,bound);
        }
        var p=scoreRunId==null||scoreRunId.isBlank()?publisher.forDate(signal):publisher.byRun(scoreRunId);var frozen=recipe();
        requireRecipeBinding(p,frozen);
        String planStrategy=sourceDocument.path("strategy").asText();
        if(!planStrategy.equals(frozen.recipe().strategyId())&&!planStrategy.equals(frozen.recipe().scoreRecipeId()))throw new IllegalStateException("Official plan strategy differs from its accepted scoring recipe");
        var packageProof=marketPackages.requireByRun(p);
        requireWeeklyFrontier(signal,LocalDate.parse(sourceDocument.path("execution_date").asText()),signal,calendarOpenDates(packageProof));
        if(!frozen.artifactSha256().equals(recipe().artifactSha256()))throw new IllegalStateException("Accepted recipe changed while importing official authority");
        return plans.importSnapshot(canonicalSource,expectedSha256,new PlanInputProvenance(p.producerRunId(),p.panelRef().contentHash(),p.coverageRef().contentHash(),p.recipeSha256(),p.resolvedEndDate(),frozen.recipe().codeRevision()));
    }
    public StoredOfficialPlan requirePlan(LocalDate day)throws Exception {
        var plan=plans.latestOfficialWeekly().orElseThrow(()->new IllegalStateException("Authoritative official weekly plan is missing"));
        if(plan.plan().signalDate().isAfter(day))throw new IllegalStateException("Official plan signal is after the data acceptance date");
        var proof=plan.provenance().orElseThrow(()->new IllegalStateException("Official plan lacks Java input provenance"));
        var p=publisher.byRun(proof.scoreRunId());
        var pinnedSource=new ObjectMapper().readTree(Files.readAllBytes(publisher.provenancePath(p)));
        if(!"ETF_ONLY_TOP2".equals(plan.plan().strategy())&&!plan.plan().strategy().equals(pinnedSource.path("scoreProvenance").path("score_recipe_id").asText()))throw new IllegalStateException("Official authority strategy differs from its original pinned scoring recipe");
        var packageProof=marketPackages.requireByRun(p);
        plans.requireLatest(plan.plan().strategy(),plan.plan().signalDate(),proof);
        if(!proof.scorePanelSha256().equals(p.panelRef().contentHash())||!proof.scoreCoverageSha256().equals(p.coverageRef().contentHash())||!proof.recipeSha256().equals(p.recipeSha256())||!proof.maxCompleteDate().equals(p.resolvedEndDate()))throw new IllegalStateException("Official plan does not bind its original exact Java score artifacts");
        requireWeeklyFrontier(plan.plan().signalDate(),plan.plan().executionDate(),day,calendarOpenDates(packageProof));
        return plan;
    }
    public List<Path> planEvidence(StoredOfficialPlan plan)throws Exception {
        var p=publisher.byRun(plan.provenance().orElseThrow().scoreRunId());
        var files=new ArrayList<Path>(List.of(Path.of(plan.sourceSnapshotPath()),p.panelRef().path(),p.coverageRef().path(),publisher.provenancePath(p)));
        files.addAll(marketPackages.evidence(marketPackages.requireByRun(p)));return List.copyOf(files);
    }
    private static List<LocalDate> calendarOpenDates(NativeMarketInputPackagePublisher.Publication product)throws Exception {
        var calendar=product.components().stream().filter(ref->ref.uri().contains("/calendar-")).findFirst().orElseThrow(()->new IllegalStateException("Frozen weekly plan calendar is missing"));
        if(calendar.rowCount()>1500)throw new IllegalStateException("Frozen weekly calendar exceeds its bounded natural-day budget");
        var open=new ArrayList<LocalDate>();LocalDate expected=calendar.start();long count=0;
        try(var reader=org.apache.parquet.hadoop.ParquetFileReader.open(new org.apache.parquet.io.LocalInputFile(calendar.path()))) {
            var schema=reader.getFooter().getFileMetaData().getSchema();
            var columns=new org.apache.parquet.io.ColumnIOFactory().getColumnIO(schema);
            org.apache.parquet.column.page.PageReadStore pages;
            while((pages=reader.readNextRowGroup())!=null) {
                var rows=columns.getRecordReader(pages,new org.apache.parquet.example.data.simple.convert.GroupRecordConverter(schema));
                for(long i=0;i<pages.getRowCount();i++) {
                    var row=rows.read();LocalDate date=LocalDate.ofEpochDay(row.getInteger("cal_date",0));int flag=(int)row.getLong("is_open",0);
                    if(++count>1500||!"SSE".equals(row.getString("exchange",0))||!date.equals(expected)||flag<0||flag>1)throw new IllegalStateException("Frozen weekly calendar is not a contiguous typed SSE grid");
                    if(flag==1)open.add(date);expected=expected.plusDays(1);
                }
            }
        }
        if(count!=calendar.rowCount()||!expected.equals(calendar.end().plusDays(1)))throw new IllegalStateException("Frozen weekly calendar coverage differs from its certified extent");
        return List.copyOf(open);
    }
    static void requireWeeklyFrontier(LocalDate signal,LocalDate execution,LocalDate day,List<LocalDate> open) {
        if(!open.contains(day)||open.stream().noneMatch(date->date.isAfter(day)))throw new IllegalStateException("Frozen weekly calendar must cover the acceptance session and its next open session");
        LocalDate expectedSignal=null,expectedExecution=null;var week=java.time.temporal.WeekFields.ISO;
        for(int i=0;i+1<open.size();i++) {
            LocalDate current=open.get(i),next=open.get(i+1);if(current.isAfter(day))break;
            if(current.get(week.weekBasedYear())!=next.get(week.weekBasedYear())||current.get(week.weekOfWeekBasedYear())!=next.get(week.weekOfWeekBasedYear())){expectedSignal=current;expectedExecution=next;}
        }
        if(!signal.equals(expectedSignal)||!execution.equals(expectedExecution))throw new IllegalStateException("Official weekly authority is stale or does not match the frozen SSE weekly frontier: "+expectedSignal+" -> "+expectedExecution);
    }
    public OfficialPlanStore.Inspection inspectPlans()throws Exception{return plans.inspect(plans.latestOfficialWeekly().map(plan->plan.plan().strategy()).orElse("ETF_ONLY_TOP2"));}
    private void requireRecipeBinding(Publication p,FrozenPromotedRecipe frozen)throws Exception {
        var mapper=new ObjectMapper();var proof=mapper.readTree(Files.readAllBytes(publisher.provenancePath(p)));
        if(!p.recipeSha256().equals(frozen.artifactSha256().get("recipe"))||!mapper.valueToTree(frozen.artifactSha256()).equals(proof.path("recipeHashes")))throw new IllegalStateException("Java scores differ from the currently accepted frozen recipe and training artifacts");
    }
    private FrozenPromotedRecipe recipe()throws Exception {
        if(!Files.isRegularFile(recipe)||!Files.isRegularFile(pins))throw new IllegalStateException("Accepted promoted recipe and SHA256 pins are required: "+recipe+", "+pins);
        if(Files.size(pins)>16384)throw new IllegalStateException("Recipe pin document exceeds budget");
        var document=new ObjectMapper().readTree(Files.readAllBytes(pins));var hashes=new LinkedHashMap<String,String>();
        for(String role:List.of("recipe","factor_weights","factor_states","l2_factor_weights")){String value=document.path(role).asText();if(!value.matches("[a-f0-9]{64}"))throw new IllegalStateException("Missing accepted training artifact SHA256: "+role);hashes.put(role,value);}
        return FrozenPromotedRecipe.load(recipe,allowedRoot,hashes);
    }
    private static List<ScoreRow> rows(QuestDbStrategyInputs.Snapshot raw,String table,String date,String symbol){return raw.tables().get(table).stream().map(row->new ScoreRow(QuestDbStrategyInputs.date(row.get(date)),symbol==null?"MARKET":text(row,symbol),FrozenScalarValues.excluding(row,SCORE_IDENTITIES))).toList();}
    static List<Map<String,Object>> frames(List<ScoreRow> rows,boolean symbol) {return rows.stream().map(row->{var identities=new LinkedHashMap<String,Object>();identities.put("trade_date",row.tradeDate());if(symbol)identities.put("ts_code",row.symbol());return FrozenScalarValues.merging(row.values(),identities);}).toList();}
    private static List<Map<String,Object>> applied(List<FrozenPromotedRecipe.DatedValues> rows) {return rows.stream().map(row->{var values=new LinkedHashMap<String,Object>();row.values().forEach((key,value)->values.put(key,value instanceof Number n&&Double.isNaN(n.doubleValue())?null:value));values.put("trade_date",row.date().toString());return (Map<String,Object>)values;}).toList();}
    private static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private static double number(Object v){return v instanceof Number n?n.doubleValue():Double.NaN;}
    private static String stamp(Path path)throws Exception{return Files.isRegularFile(path)?ScoreArtifactPublisher.sha(path):"absent";}
}
