package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import com.zoutrankil.data.service.TusharePageService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real provider + temporary SQLite file + real QuestDB. Explicitly enabled, isolated targets only. */
@EnabledIfEnvironmentVariable(named="JDB_SOURCE_LIVE",matches="1")
class NativeProducerLiveTest {
    @Test void collectMapWriteVerifyAndRepeatNativeDailySources() throws Exception {
        String qdb=System.getenv("JDB_QUESTDB_TEST_URL"); assertNotNull(qdb);
        Path archive=Path.of("var/jdb-source-acceptance",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(archive);
        var reports=new ArrayList<Map<String,Object>>();
        {
            var application=new SpringApplication(BatchApplication.class);
            application.setDefaultProperties(Map.of("spring.config.name","batch-runtime"));
            try(var context=application.run("--jdb.metadata-url=jdbc:sqlite:"+archive.resolve("metadata.sqlite").toAbsolutePath(),
                    "--jdb.api-port=0",
                    "--jdb.api-token=live-test-management-token-not-for-deployment","--jdb.archive-root="+archive,
                    "--jdb.questdb-http-url="+qdb,"--jdb.scheduling-enabled=false")) {
                var collector=context.getBean(SourceCollector.class);var pages=context.getBean(TusharePageService.class);
                var launches=context.getBean(LaunchService.class);var ledger=context.getBean(SqliteLedger.class);
                var selected=SourceContract.SUPPORTED.stream().sorted().toList();
                String selection=System.getenv("JDB_SOURCE_DATASETS");
                if(selection!=null&&!selection.isBlank()) selected=Arrays.stream(selection.split(",")).map(String::trim).filter(s -> !s.isEmpty()).peek(SourceContract::load).toList();
                String token=System.getenv("JDB_TUSHARE_TOKEN");
                if(selected.stream().anyMatch(d -> !d.equals("cn_bond_yield_curve"))) { assertNotNull(token);assertFalse(token.isBlank()); }
                for(String dataset:selected) {
                    var contract=SourceContract.load(dataset);
                    String selectedCode=System.getenv("JDB_SOURCE_CODE");
                    List<String> codeList=selectedCode!=null&&!selectedCode.isBlank()?List.of(selectedCode):
                            dataset.equals("index_daily_market")?List.of("000300.SH","000016.SH","399006.SZ","000905.SH","000852.SH","000985.CSI","801080.SI"):
                            dataset.equals("index_daily_basic")?List.of("000300.SH","000016.SH","399006.SZ","000905.SH","000852.SH"):
                            dataset.equals("exchange_calendar")?List.of("SSE","SZSE"):
                            dataset.equals("fut_basic")?List.of("CFFEX"):
                            dataset.equals("etf_basic")?List.of("E"):
                            dataset.equals("fut_holding")?List.of("IF"):
                            Set.of("fut_daily","fut_settle","ft_limit").contains(dataset)?List.of("IF2610.CFX"):
                            dataset.equals("fut_mapping")?List.of("IF.CFX"):
                            List.of(dataset.startsWith("etf_")?"510300.SH":"000001.SZ");
                    var codes=Collections.unmodifiableSet(new TreeSet<>(codeList));
                    String codeLabel=String.join(",",codes);
                    var sourceRequest=dataset.equals("etf_portfolio")?
                            new SourceCollector.Request(dataset,LocalDate.of(2026,8,29),Set.of(codeList.getFirst()),"frozen-portfolio-fund-v1",codeList.getFirst()):
                            dataset.equals("stk_factor")?
                            new SourceCollector.Request(dataset,LocalDate.of(2026,9,24),Set.of(codeList.getFirst()),"frozen-factor-reference-v1",codeList.getFirst()):
                            dataset.equals("dividend")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2018-05-21")),Set.of(codeList.getFirst()),"bounded-dividend-acceptance-v1",codeList.getFirst()):
                            dataset.equals("fina_audit")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2018-04-28")),Set.of(codeList.getFirst()),"bounded-fina-audit-acceptance-v1",codeList.getFirst()):
                            dataset.equals("fut_basic")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-master-acceptance-v1",null):
                            dataset.equals("disclosure_date")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-06-30")),Set.of(),"bounded-disclosure-date-acceptance-v1",null):
                            dataset.equals("fut_daily")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-acceptance-v1",codeList.getFirst()):
                            dataset.equals("fut_settle")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-settlement-acceptance-v1",codeList.getFirst()):
                            dataset.equals("fut_mapping")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-mapping-acceptance-v1",codeList.getFirst()):
                            dataset.equals("ft_limit")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-limits-acceptance-v1",codeList.getFirst()):
                            dataset.equals("fut_holding")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(codeList.getFirst()),"bounded-futures-holding-acceptance-v1",codeList.getFirst()):
                            dataset.equals("share_float")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2018-12-20")),codes,"bounded-share-float-acceptance-v1",codes.size()==1?codeList.getFirst():null):
                            dataset.equals("shibor")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(),"bounded-shibor-acceptance-v1",null):
                            dataset.equals("shibor_lpr")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-08-20")),Set.of(),"bounded-shibor-lpr-acceptance-v1",null):
                            dataset.equals("hibor")?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-09-28")),Set.of(),"bounded-hibor-acceptance-v1",null):
                            SourceContract.MONTHLY_AGGREGATES.contains(dataset)?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-07-01")),Set.of(),"bounded-"+dataset+"-month-acceptance-v1",null,
                                    LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_START_DATE",System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-07-01"))),
                                    LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-07-01"))):
                            SourceContract.QUARTERLY_AGGREGATES.contains(dataset)?
                            new SourceCollector.Request(dataset,LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-06-30")),Set.of(),"bounded-"+dataset+"-quarter-acceptance-v1",null,
                                    LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_START_DATE",System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-06-30"))),
                                    LocalDate.parse(System.getenv().getOrDefault("JDB_SOURCE_DATE","2026-06-30"))):
                            new SourceCollector.Request(dataset,dataset.equals("fina_mainbz")?LocalDate.of(2026,6,30):LocalDate.of(2026,9,28),contract.isMarketAggregate()?Set.of():codes,
                                    dataset.startsWith("index_")?"bounded-index-acceptance-v1":"frozen-universe-v1",codes.size()==1?codeList.getFirst():null);
                    SourceCollector.ContractFetcher fetch=dataset.equals("cn_bond_yield_curve")?
                            (provider,params) -> context.getBean(ChinabondYieldSource.class).fetcher().fetch(params):
                            (provider,params) -> pages.fetcher(provider,() -> false).fetch(params);
                    var captured=new ArrayList<Object>();
                    var collected=collector.collect(sourceRequest,(provider,params) -> { var result=fetch.fetch(provider,params);captured.add(result.rows());return result; });
                    if(!contract.emptyAllowed()) assertTrue(collected.rows()>0,"Must validate a nonempty required source");
                    if(dataset.equals("fina_mainbz")) assertTrue(collected.rows()>0,"Known reporting security/period must provide at least one segment");
                    if(dataset.equals("dividend")) assertTrue(collected.rows()>0,"Acceptance date must contain a dividend announcement for the chosen security");
                    if(dataset.equals("share_float")) assertTrue(collected.rows()>0,"Acceptance announcement date must contain a share-float event for the chosen security");
                    if(dataset.equals("fina_audit")) assertTrue(collected.rows()>0,"Acceptance announcement date must contain a fina_audit observation for the chosen security");
                    if(dataset.equals("fut_daily")) assertTrue(collected.rows()>0,"Acceptance trade date must contain a futures daily observation for the chosen contract");
                    if(dataset.equals("fut_settle")) assertTrue(collected.rows()>0,"Acceptance trade date must contain futures settlement parameters for the chosen contract");
                    if(dataset.equals("fut_mapping")) assertTrue(collected.rows()>0,"Acceptance trade date must contain a mapping for the chosen continuous contract");
                    if(dataset.equals("ft_limit")) assertTrue(collected.rows()>0,"Acceptance trade date must contain futures limit data for the chosen contract");
                    if(dataset.equals("fut_holding")) assertTrue(collected.rows()>0,"Acceptance trade date must contain futures holding ranks for the chosen product");
                    if(dataset.equals("shibor")) assertTrue(collected.rows()>0,"Acceptance date must be a published Shibor observation date");
                    if(dataset.equals("shibor_lpr")) assertTrue(collected.rows()>0,"Acceptance date must be a published LPR observation date");
                    if(dataset.equals("hibor")) assertTrue(collected.rows()>0,"Acceptance date must be a published HIBOR observation date");
                    if(SourceContract.MONTHLY_AGGREGATES.contains(dataset)) assertTrue(collected.rows()>0,"Acceptance month must have a published macro observation");
                    if(SourceContract.QUARTERLY_AGGREGATES.contains(dataset)) assertTrue(collected.rows()>0,"Acceptance quarter must have a published GDP observation");
                    if(dataset.equals("etf_portfolio")) assertTrue(collected.rows()>50,"Nonempty portfolio must exercise multiple write batches");
                    assertTrue(collected.completeCoverage());
                    String expectedState=collected.rows()==0?"VERIFIED_EMPTY":"VERIFIED";
                    Files.writeString(archive.resolve(dataset+"-provider.json"),Json.write(captured));
                    var now=Instant.now();var date=sourceRequest.logicalDate();
                    var request=new RunRequest("live-"+dataset+"-"+RunRequest.hash(codeLabel),"source_"+dataset,date,sourceRequest.rangeStart(),sourceRequest.rangeEnd(),contract.version(),"0",null,null,
                            collected.fingerprint(),"frozen-calendar-20260928","Asia/Shanghai",now,now,collected.scopeIdentity());
                    var result=launches.launch(request);
                    // An acknowledged but delayed WAL batch may be verified by restarting the same Batch instance.
                    long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
                    while("VERIFYING".equals(result.get("business_state")) && System.nanoTime()<deadline) {
                        Thread.sleep(100);result=launches.launch(request);
                    }
                    assertEquals(expectedState,result.get("business_state"),Json.write(result));
                    if(SourceContract.MONTHLY_AGGREGATES.contains(dataset)&&collected.rows()>0) {
                        var persisted=ledger.monthlyCoverage().stream().filter(c -> c.dataset().equals(dataset)
                                && c.definitionVersion().equals(contract.version())&&c.scopeIdentity().equals(collected.scopeIdentity())).findFirst().orElseThrow();
                        assertTrue(persisted.verifiedSegments().stream().anyMatch(segment -> !segment.start().isAfter(sourceRequest.rangeStart())
                                && !segment.end().isBefore(sourceRequest.rangeEnd())),"verified source interval must publish a continuous persisted watermark");
                    }
                    if(SourceContract.QUARTERLY_AGGREGATES.contains(dataset)&&collected.rows()>0) {
                        var persisted=ledger.quarterlyCoverage().stream().filter(c -> c.dataset().equals(dataset)
                                && c.definitionVersion().equals(contract.version())&&c.scopeIdentity().equals(collected.scopeIdentity())).findFirst().orElseThrow();
                        assertTrue(persisted.verifiedSegments().stream().anyMatch(segment -> !segment.start().isAfter(sourceRequest.rangeStart())
                                && !segment.end().isBefore(sourceRequest.rangeEnd())),"verified source interval must publish a continuous persisted quarterly watermark");
                    }
                    int attempts=ledger.jdbc().queryForObject("SELECT sum(attempt) FROM write_intent WHERE instance_id=?",Integer.class,request.instanceId());
                    assertEquals(Math.max(1,(collected.rows()+contract.writeBatchSize()-1)/contract.writeBatchSize()),attempts);
                    assertEquals(expectedState,launches.launch(request).get("business_state"));
                    assertEquals(attempts,ledger.jdbc().queryForObject("SELECT sum(attempt) FROM write_intent WHERE instance_id=?",Integer.class,request.instanceId()));
                    var report=new LinkedHashMap<String,Object>();report.put("dataset",dataset);report.put("code",contract.isMarketAggregate()?"aggregate-no-security-dimension":codeLabel);report.put("source",collected);
                    report.put("result",result);report.put("sendAttempts",attempts);report.put("dataPostCount",collected.rows()==0?0:attempts);report.put("physicalColumns",contract.columns());
                    if(SourceContract.MONTHLY_AGGREGATES.contains(dataset)) report.put("persistedCoverage",ledger.monthlyCoverage().stream()
                            .filter(c -> c.dataset().equals(dataset)&&c.definitionVersion().equals(contract.version())
                                    &&c.scopeIdentity().equals(collected.scopeIdentity())).findFirst().orElseThrow());
                    if(SourceContract.QUARTERLY_AGGREGATES.contains(dataset)) report.put("persistedCoverage",ledger.quarterlyCoverage().stream()
                            .filter(c -> c.dataset().equals(dataset)&&c.definitionVersion().equals(contract.version())
                                    &&c.scopeIdentity().equals(collected.scopeIdentity())).findFirst().orElse(null));
                    report.put("sourceKind",dataset.equals("cn_bond_yield_curve")?"real-chinabond":"real-tushare");report.put("pythonBaselineCompared",false);reports.add(report);
                    Files.writeString(archive.resolve("results.json"),Json.write(reports));
                }
            }
        }
        Path output=Path.of(System.getenv().getOrDefault("JDB_SOURCE_REPORT","build/reports/jdb-native-producers.json"));Files.createDirectories(output.getParent());
        Files.writeString(output,Json.write(Map.of("archive",archive.toString(),"products",reports,"coverage","bounded requested windows; monthly aggregates support contiguous month ranges; security datasets remain explicitly scoped; not full-market acceptance")));
    }
}
