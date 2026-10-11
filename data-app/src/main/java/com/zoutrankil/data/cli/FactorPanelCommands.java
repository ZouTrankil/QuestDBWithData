package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.DailyFactorPanel;
import com.zoutrankil.data.factor.storage.QuestDbDailyFactorPanel;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-only, full-market panel execution and phase timing; not a publication command. */
@Component
@ConditionalOnNotWebApplication
public final class FactorPanelCommands implements CliCommandFamily {
    private final JdbcTemplate jdbc;
    public FactorPanelCommands(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public Set<String> commands() { return Set.of("compute-factor-panel"); }
    @Override public void execute(String command,Map<String,String> options) throws Exception {
        if(!commands().contains(command) || !options.keySet().containsAll(Set.of("--from","--to","--output"))
                || !Set.of("--from","--to","--output","--values-output","--evidence-output","--bundle-output","--factors","--pools","--pool-output").containsAll(options.keySet()))
            throw new IllegalArgumentException("Expected --from DATE --to DATE --output PATH");
        LocalDate from=LocalDate.parse(options.get("--from")),through=LocalDate.parse(options.get("--to"));
        Path output=Path.of(options.get("--output")).toAbsolutePath().normalize();
        if(Files.exists(output))throw new IllegalArgumentException("Output already exists");
        if((options.containsKey("--evidence-output") || options.containsKey("--bundle-output"))!=options.containsKey("--factors")
                || options.containsKey("--evidence-output") && options.containsKey("--bundle-output"))
            throw new IllegalArgumentException("Exactly one evidence or bundle output and --factors must be supplied together");
        var catalog=com.zoutrankil.data.factor.application.FactorDefinitionCatalog.load();
        var factorIds=options.containsKey("--factors")?java.util.List.of(options.get("--factors").split(",",-1)):java.util.List.<String>of();
        var marketFactorIds=factorIds.stream().filter(id->catalog.require(id).sourceGroup().equals("market_state")).toList();
        var stockFactorIds=factorIds.stream().filter(id->!catalog.require(id).sourceGroup().equals("market_state")).toList();
        if(options.containsKey("--pools")!=options.containsKey("--pool-output"))
            throw new IllegalArgumentException("--pools and --pool-output must be supplied together");
        var poolIds=options.containsKey("--pools")?java.util.List.of(options.get("--pools").split(",",-1)):java.util.List.<String>of();
        if(!poolIds.isEmpty()) {
            if(stockFactorIds.isEmpty())throw new IllegalArgumentException("Pool output requires stock factors");
            if(Files.exists(Path.of(options.get("--pool-output"))))throw new IllegalArgumentException("Pool output already exists");
            new com.zoutrankil.data.factor.engine.PointInTimeStockPools.Index(java.util.List.of()).resolve(from,java.util.List.of(),poolIds);
        }
        if(options.containsKey("--factors")) {
            if(new java.util.HashSet<>(factorIds).size()!=factorIds.size())throw new IllegalArgumentException("Duplicate factors");
            for(String id:factorIds) {
                var definition=catalog.require(id);
                if(!definition.enabled() || !definition.publishObservations())
                    throw new IllegalArgumentException("Factor is disabled for publication: "+id);
            }
            if(Files.exists(Path.of(options.get(options.containsKey("--bundle-output")?"--bundle-output":"--evidence-output"))))throw new IllegalArgumentException("Evidence output already exists");
        }
        for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
        boolean dailyFeatures=factorIds.isEmpty() || options.containsKey("--values-output") || stockFactorIds.stream()
                .map(catalog::require).anyMatch(d->Set.of("canonical_daily","daily_price_tech","global_structural_daily").contains(d.sourceGroup()));
        int warmupDays=dailyFeatures || !marketFactorIds.isEmpty() || !stockFactorIds.isEmpty() || !poolIds.isEmpty()?460:0;
        long start=System.nanoTime();
        var snapshot=new QuestDbDailyFactorPanel(jdbc).read(from,through,warmupDays,5_000_000);
        long readNanos=System.nanoTime()-start;
        long computeStart=System.nanoTime();
        var features=dailyFeatures?DailyFactorPanel.compute(snapshot.securities(),from,through)
                :DailyFactorPanel.selectRaw(snapshot.securities(),from,through);
        long computeNanos=System.nanoTime()-computeStart;
        long marketStateStart=System.nanoTime();
        var marketState=marketFactorIds.isEmpty()?null:com.zoutrankil.data.factor.engine.MarketStateFeatures.compute(snapshot.securities(),from,through);
        long marketStateNanos=marketState==null?0:System.nanoTime()-marketStateStart;
        var sourceState=new java.util.ArrayList<>(snapshot.sourceState());
        long financialReadNanos=0,financialComputeNanos=0,financialRows=0;
        if(factorIds.stream().map(catalog::require).anyMatch(d->d.sourceGroup().equals("fundamental_pit"))) {
            long financialStart=System.nanoTime();
            var financial=new com.zoutrankil.data.factor.storage.QuestDbFinancialIndicators(jdbc).read(through,1_000_000);
            financialReadNanos=System.nanoTime()-financialStart;financialRows=financial.rows();
            sourceState.addAll(financial.sourceState());financialStart=System.nanoTime();
            var enriched=new java.util.ArrayList<DailyFactorPanel.Features>(features.size());
            for(var security:features) {
                var columns=new LinkedHashMap<>(security.columns());
                columns.putAll(financial.align(security.symbol(),security.dates()));
                enriched.add(new DailyFactorPanel.Features(security.symbol(),security.dates(),java.util.Collections.unmodifiableMap(columns)));
            }
            features=java.util.List.copyOf(enriched);financialComputeNanos=System.nanoTime()-financialStart;
        }
        long statementReadNanos=0,statementComputeNanos=0,statementRows=0;
        if(factorIds.stream().map(catalog::require).anyMatch(d->d.sourceGroup().equals("global_structural_pit"))) {
            long statementStart=System.nanoTime();
            var statements=new com.zoutrankil.data.factor.storage.QuestDbFinancialStatements(jdbc).read(through,1_000_000);
            statementReadNanos=System.nanoTime()-statementStart;statementRows=statements.rows();
            sourceState.addAll(statements.sourceState());statementStart=System.nanoTime();
            var enriched=new java.util.ArrayList<DailyFactorPanel.Features>(features.size());
            for(var security:features) {
                var columns=new LinkedHashMap<>(security.columns());
                columns.putAll(statements.compute(security.symbol(),security.dates(),columns.get("total_mv")));
                enriched.add(new DailyFactorPanel.Features(security.symbol(),security.dates(),java.util.Collections.unmodifiableMap(columns)));
            }
            features=java.util.List.copyOf(enriched);statementComputeNanos=System.nanoTime()-statementStart;
        }
        long eventStart=System.nanoTime();
        var eventIds=factorIds.stream().filter(id->catalog.require(id).sourceGroup().equals("event_pit")).toList();
        Map<String,Long> eventRows=Map.of();
        if(!eventIds.isEmpty()) {
            var events=new com.zoutrankil.data.factor.storage.QuestDbEventFactors(jdbc).read(from,through,eventIds,1_000_000);
            eventRows=events.sourceRows();sourceState.addAll(events.sourceState());
            var enriched=new java.util.ArrayList<DailyFactorPanel.Features>(features.size());
            for(var security:features) {
                var columns=new LinkedHashMap<>(security.columns());
                for(String id:eventIds)columns.put(com.zoutrankil.data.factor.engine.FactorFormulaEngine.inputColumn(catalog.require(id)),
                        events.align(id,security.symbol(),security.dates()));
                enriched.add(new DailyFactorPanel.Features(security.symbol(),security.dates(),java.util.Collections.unmodifiableMap(columns)));
            }
            features=java.util.List.copyOf(enriched);
        }
        long eventNanos=eventIds.isEmpty()?0:System.nanoTime()-eventStart;
        com.zoutrankil.data.factor.application.NativePoolFactors.Result poolResult=null;
        long poolStart=System.nanoTime();
        if(!poolIds.isEmpty()) {
            var membership=new com.zoutrankil.data.factor.storage.QuestDbStockMembership(jdbc).read(through,1_000_000);
            sourceState.addAll(membership.sourceState());
            poolResult=com.zoutrankil.data.factor.application.NativePoolFactors.compute(snapshot.securities(),features,
                    membership,poolIds,stockFactorIds,catalog);
        }
        long poolNanos=poolResult==null?0:System.nanoTime()-poolStart;
        requireSourceStateUnchanged(sourceState);
        long outputRows=0;double checksum=0;
        for(var security:features) for(int i=0;i<security.dates().length;i++) {
            LocalDate date=security.dates()[i];
            if(!date.isBefore(from) && !date.isAfter(through)) {
                outputRows++;
                var beta=security.columns().get("market_beta_252");double value=beta==null?Double.NaN:beta[i];
                if(Double.isFinite(value))checksum+=value;
            }
        }
        var peaks=new LinkedHashMap<String,Long>();
        for(var pool:ManagementFactory.getMemoryPoolMXBeans())peaks.put(pool.getName(),pool.getPeakUsage().getUsed());
        var report=new LinkedHashMap<String,Object>();
        report.put("sourceRows",snapshot.rows());report.put("securities",snapshot.securities().size());
        report.put("requestedSecurityDateRows",outputRows);report.put("missingDailyBasicRows",snapshot.missingBasicRows());
        report.put("warmupFrom",snapshot.warmupFrom());report.put("requestedFrom",from);report.put("requestedThrough",through);
        report.put("readMillis",readNanos/1_000_000.0);report.put("computeMillis",computeNanos/1_000_000.0);
        report.put("dailyFeaturesComputed",dailyFeatures);
        report.put("eventSourceRows",eventRows);report.put("eventReadAndComputeMillis",eventNanos/1_000_000.0);
        if(!eventIds.isEmpty())report.put("eventWarmupFrom",from.minusDays(460));
        report.put("poolReadAndComputeMillis",poolNanos/1_000_000.0);
        if(poolResult!=null) {
            report.put("poolSections",poolResult.sections().size());
            report.put("poolProjectionBuilds",poolResult.projectionBuilds());
            report.put("poolProjectionHits",poolResult.projectionHits());
            Path poolPath=Path.of(options.get("--pool-output")).toAbsolutePath().normalize();
            var poolIdentity=new LinkedHashMap<String,Object>();
            poolIdentity.put("sourceState",sourceState);poolIdentity.put("from",from);poolIdentity.put("through",through);
            poolIdentity.put("warmupFrom",snapshot.warmupFrom());poolIdentity.put("pools",poolIds);
            poolIdentity.put("factors",stockFactorIds);poolIdentity.put("catalogSha256",catalog.sourceConfigSha256());
            poolIdentity.put("kernelSha256",com.zoutrankil.data.factor.application.FactorComputationIdentity.sha256(FactorPanelCommands.class));
            try(var rules=FactorPanelCommands.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
                if(rules==null)throw new IllegalStateException("Missing stock aliases");
                poolIdentity.put("aliasRulesSha256",FileEvidenceStore.sha256(rules.readAllBytes()));
            }
            String poolFingerprint=FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(poolIdentity));
            var poolArtifact=new LinkedHashMap<String,Object>();poolArtifact.put("schema","java.factor_pool_values.v1");
            poolArtifact.put("inputIdentity",poolIdentity);poolArtifact.put("inputFingerprint",poolFingerprint);
            poolArtifact.put("sourceComplete",false);poolArtifact.put("coverage",poolResult.coverage());
            poolArtifact.put("sections",poolResult.sections());poolArtifact.put("projectionBuilds",poolResult.projectionBuilds());
            poolArtifact.put("projectionHits",poolResult.projectionHits());
            byte[] poolBytes=JobDefinitionJson.mapper().writeValueAsBytes(poolArtifact);
            Files.createDirectories(poolPath.getParent());
            FileEvidenceStore.writeNew(poolPath,poolBytes);
            report.put("poolInputFingerprint",poolFingerprint);report.put("poolArtifactSha256",FileEvidenceStore.sha256(poolBytes));
        }
        report.put("marketStateComputeMillis",marketStateNanos/1_000_000.0);
        report.put("memoryPoolPeakBytes",peaks);report.put("betaChecksum",dailyFeatures?checksum:null);report.put("sourceState",sourceState);
        report.put("financialSourceRows",financialRows);report.put("financialReadMillis",financialReadNanos/1_000_000.0);
        report.put("financialComputeMillis",financialComputeNanos/1_000_000.0);
        report.put("statementRows",statementRows);report.put("statementReadMillis",statementReadNanos/1_000_000.0);
        report.put("statementComputeMillis",statementComputeNanos/1_000_000.0);
        report.put("publicationReady",false);report.put("listingDatesLoaded",true);
        report.put("missingListingDates",snapshot.missingListingDates());
        report.put("excludedMarketRows",snapshot.excludedMarketRows());
        report.put("excludedBasicRows",snapshot.excludedBasicRows());
        if(options.containsKey("--evidence-output") || options.containsKey("--bundle-output")) {
            boolean bundle=options.containsKey("--bundle-output");
            long marketRows=marketState==null?0:(long)marketState.dates().length*marketFactorIds.size();
            if(outputRows*stockFactorIds.size()+marketRows>(bundle?2_000_000:100_000))throw new IllegalArgumentException("Selected observations exceed output budget");
            var dates=new java.util.HashSet<LocalDate>();
            for(var security:features)for(var date:security.dates())
                if(!date.isBefore(from) && !date.isAfter(through))dates.add(date);
            var observations=new java.util.ArrayList<com.zoutrankil.data.factor.domain.GlobalFactorObservation>();
            var computedAt=java.time.LocalDateTime.now();
            var publicationScope=stockFactorIds.isEmpty()?java.util.Map.<LocalDate,com.zoutrankil.data.factor.application.StockPublicationScope.Day>of()
                    :com.zoutrankil.data.factor.application.StockPublicationScope.resolve(snapshot.securities(),features,dates);
            if(!stockFactorIds.isEmpty())com.zoutrankil.data.factor.application.NativeDailyObservations.streamPanel(features,dates,stockFactorIds,catalog,
                    computedAt,page -> page.stream().filter(row -> publicationScope.get(row.signalDate()).symbols().contains(row.entityId()))
                            .forEach(observations::add));
            var marketEngine=new com.zoutrankil.data.factor.engine.FactorFormulaEngine();
            for(String id:marketFactorIds) {
                var definition=catalog.require(id);
                if(!definition.assetType().equals("market"))throw new IllegalArgumentException("Market factor has another entity type");
                double[] raw=marketEngine.compute(definition,marketState.columns());
                for(int i=0;i<marketState.dates().length;i++)observations.add(com.zoutrankil.data.factor.application.GlobalFactorObservations.raw(
                        marketState.dates()[i],"MARKET.CN",definition,raw[i],null,computedAt,"ok","",null));
            }
            var delta=bundle?null:new com.zoutrankil.data.factor.storage.QuestDbGlobalObservations(jdbc).reconcile(observations);
            var identity=new LinkedHashMap<String,Object>();
            identity.put("sourceState",sourceState);identity.put("warmupFrom",snapshot.warmupFrom().toString());
            identity.put("from",from.toString());
            identity.put("kernelSha256",com.zoutrankil.data.factor.application.FactorComputationIdentity.sha256(FactorPanelCommands.class));
            identity.put("through",through.toString());identity.put("catalogSha256",catalog.sourceConfigSha256());
            identity.put("factors",factorIds);
            identity.put("stockPublicationUniverseVersion",com.zoutrankil.data.factor.engine.PointInTimeStockPools.VERSION);
            identity.put("stockPublicationMembership",publicationScope.values().stream()
                    .map(day -> Map.of("date",day.date(),"membershipHash",day.membershipHash(),"members",day.symbols().size())).toList());
            report.put("stockPublicationMembership",identity.get("stockPublicationMembership"));
            try(var rules=FactorPanelCommands.class.getResourceAsStream("/factor/stock-code-aliases.tsv")) {
                if(rules==null)throw new IllegalStateException("Missing stock aliases");
                identity.put("aliasRulesSha256",FileEvidenceStore.sha256(rules.readAllBytes()));
            }
            String fingerprint=FileEvidenceStore.sha256(JobDefinitionJson.mapper().writeValueAsBytes(identity));
            requireSourceStateUnchanged(sourceState);
            if(bundle) {
                Path directory=Path.of(options.get("--bundle-output")).toAbsolutePath().normalize();
                Files.createDirectories(directory.getParent());Files.createDirectory(directory);
                var parts=new java.util.ArrayList<Map<String,Object>>();
                for(int offset=0;offset<observations.size();offset+=100_000) {
                    int end=Math.min(offset+100_000,observations.size());
                    String name=String.format(java.util.Locale.ROOT,"observations-%03d.json",parts.size());
                    String partFingerprint=FileEvidenceStore.sha256((fingerprint+"\n"+offset+"\n"+end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    String sha=com.zoutrankil.data.factor.application.FactorObservationEvidence.writeNew(
                            directory.resolve(name),observations.subList(offset,end),false,partFingerprint);
                    parts.add(Map.of("file",name,"rows",end-offset,"sha256",sha,"inputFingerprint",partFingerprint));
                }
                if(parts.isEmpty())throw new IllegalStateException("No observations for bundle");
                var manifest=Map.of("schema","java.factor_observation_bundle.v1","sourceComplete",false,
                        "inputFingerprint",fingerprint,"factors",factorIds,"rows",observations.size(),"parts",parts);
                FileEvidenceStore.writeNewDurable(directory.resolve("manifest.json"),JobDefinitionJson.mapper().writeValueAsBytes(manifest));
                report.put("bundleDirectory",directory.toString());report.put("bundleParts",parts.size());
                report.put("observationRows",observations.size());report.put("inputFingerprint",fingerprint);
            } else {
            // Preserve the entire expected set: the publisher rechecks current physical rows under its lock.
            String evidenceSha=com.zoutrankil.data.factor.application.FactorObservationEvidence.writeNew(
                    Path.of(options.get("--evidence-output")),observations,false,fingerprint);
            report.put("observationRows",observations.size());report.put("matchingObservationRows",delta.matching());
            report.put("pendingObservationRows",delta.writes().size());report.put("unexpectedObservationRows",delta.unexpected());
            report.put("evidenceSha256",evidenceSha);report.put("inputFingerprint",fingerprint);
            }
            report.put("sourceComplete",false);
        }
        if(options.containsKey("--values-output")) {
            var values=new java.util.ArrayList<Map<String,Object>>();
            for(var security:features) for(int i=0;i<security.dates().length;i++) {
                var date=security.dates()[i];
                if(date.isBefore(from) || date.isAfter(through))continue;
                var row=new LinkedHashMap<String,Object>();row.put("ts_code",security.symbol());row.put("trade_date",date.toString());
                for(var column:security.columns().entrySet()) {
                    double value=column.getValue()[i];row.put(column.getKey(),Double.isFinite(value)?value:null);
                }
                values.add(row);
            }
            Path valuesPath=Path.of(options.get("--values-output")).toAbsolutePath().normalize();
            Files.createDirectories(valuesPath.getParent());
            FileEvidenceStore.writeNew(valuesPath,JobDefinitionJson.mapper().writeValueAsBytes(values));
        }
        Files.createDirectories(output.getParent());
        FileEvidenceStore.writeNew(output,JobDefinitionJson.mapper().writeValueAsBytes(report));
        CliOutput.printJson(report,CliOutput.Profile.JOB_DEFINITION,true);
    }
    void requireSourceStateUnchanged(java.util.List<Map<String,Object>> expected) {
        for(var source:expected) {
            com.zoutrankil.data.factor.storage.BacktestFactorSource.requireUnchanged(jdbc,source);
            var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE id=?",source.get("id"));
            if(rows.size()!=1)throw new IllegalStateException("Factor source disappeared during pipeline");
            var actual=rows.getFirst();
            for(String column:java.util.List.of("id","directoryName","table_txn","wal_txn"))
                if(!sameMetadataValue(source.get(column),actual.get(column)))
                    throw new IllegalStateException("Factor source changed during pipeline: "+actual.get("table_name"));
            if(source.containsKey("table_name") && !java.util.Objects.equals(source.get("table_name"),actual.get("table_name"))
                    || Boolean.TRUE.equals(actual.get("table_suspended")))
                throw new IllegalStateException("Factor source renamed or suspended during pipeline");
        }
    }
    private static boolean sameMetadataValue(Object expected,Object actual) {
        if(expected instanceof Number left && actual instanceof Number right)
            return new java.math.BigDecimal(left.toString()).compareTo(new java.math.BigDecimal(right.toString()))==0;
        return java.util.Objects.equals(expected,actual);
    }
}
