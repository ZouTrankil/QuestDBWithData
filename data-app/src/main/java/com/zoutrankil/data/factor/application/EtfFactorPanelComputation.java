package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.factor.storage.QuestDbEtfInputs;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Native ETF calculation/export shared by CLI and finite post-close workflows. */
public final class EtfFactorPanelComputation {
    public record Request(LocalDate historyFrom,LocalDate from,LocalDate through,Path output) {
        public Request {
            Objects.requireNonNull(historyFrom);Objects.requireNonNull(from);
            Objects.requireNonNull(through);Objects.requireNonNull(output);
        }
    }
    public record ExportRequest(Path panel,Path output,LocalDateTime computedAt,List<String> factors) {
        public ExportRequest {
            Objects.requireNonNull(panel);Objects.requireNonNull(output);Objects.requireNonNull(computedAt);
            factors=List.copyOf(factors);
        }
    }
    private final JdbcTemplate jdbc;
    public EtfFactorPanelComputation(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    /** Require every declared source group and the backtest result/view identity before reusing a panel. */
    public void requireSourceStateUnchanged(com.fasterxml.jackson.databind.JsonNode panel) throws Exception {
        var groups=Map.of("sourceState",Set.of("etf_basic","etf_daily","etf_share"),
                "l2SourceState",Set.of("l2_daily_features"),"stockSourceState",Set.of("stock_detail_info","mv_backtest_daily"),
                "holdingSourceState",Set.of("etf_portfolio"),"technicalSourceState",Set.of("etf_factor"),
                "csiFinancialSourceState",Set.of("cashflow","fina_indicator"),"csiHoldingSourceState",Set.of("etf_portfolio"),
                "csiMarketSourceState",Set.of("daily_basic","stock_detail_info","mv_backtest_daily"),
                "earningsSourceState",Set.of("express","forecast"));
        var guard=new FactorPanelComputation(jdbc);var checked=new HashSet<String>();
        for(var group:groups.entrySet()) {
            var nodes=panel.path(group.getKey());var names=new HashSet<String>();
            if(!nodes.isArray())throw new IllegalStateException("Missing ETF source group: "+group.getKey());
            for(var node:nodes)if(!names.add(node.path("table_name").asText()))
                throw new IllegalStateException("Duplicate ETF source identity");
            if(!names.equals(group.getValue()))throw new IllegalStateException("ETF source group differs: "+group.getKey());
            var sources=JobDefinitionJson.mapper().convertValue(nodes,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            guard.requireSourceStateUnchanged(sources);
            for(var source:sources) {
                String name=(String)source.get("table_name");
                if(source.get("wal_txn")!=null && checked.add(name)
                        && !com.zoutrankil.data.repository.QuestDbWriteChecks.walSettled(jdbc,name))
                    throw new IllegalStateException("ETF source WAL not settled: "+name);
            }
        }
    }
    public Map<String,Object> exportObservations(ExportRequest request)throws Exception {
            var bytes=FileEvidenceStore.readBounded(request.panel(),64*1024*1024,
                    ()->new IllegalArgumentException("ETF panel exceeds 64 MiB"));
            var panel=JobDefinitionJson.mapper().readTree(bytes);
            var catalog=com.zoutrankil.data.factor.application.FactorDefinitionCatalog.load();
            var computedAt=request.computedAt();
            var rows=com.zoutrankil.data.factor.application.EtfPanelObservations.convert(panel,catalog,computedAt);
            var selected=new TreeSet<String>();
            if(!request.factors().isEmpty()) {
                for(String id:request.factors()) {
                    var definition=catalog.require(id);
                    if(!selected.add(id) || !definition.assetType().equals("etf") || !definition.enabled() || !definition.publishObservations())
                        throw new IllegalArgumentException("Unique enabled publishable ETF factors required");
                }
                rows=rows.stream().filter(row->selected.contains(row.factorId())).toList();
            } else for(var definition:catalog.definitions())if(definition.assetType().equals("etf") && definition.enabled() && definition.publishObservations())selected.add(definition.factorId());
            var counts=new TreeMap<String,Long>();
            var validCounts=new TreeMap<String,Long>();
            for(var definition:catalog.definitions())if(selected.contains(definition.factorId()))
                {counts.put(definition.factorId(),0L);validCounts.put(definition.factorId(),0L);}
            for(var row:rows){counts.merge(row.factorId(),1L,Long::sum);if(row.factorValue()!=null)validCounts.merge(row.factorId(),1L,Long::sum);}
            var fingerprint=FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(Map.of(
                    "panelSha256",FileEvidenceStore.sha256(bytes),"catalogSha256",catalog.sourceConfigSha256(),
                    "emitterSha256",FactorComputationIdentity.sha256(EtfFactorPanelComputation.class),"computedAt",computedAt.toString(),"factorIds",selected)));
            var sha=com.zoutrankil.data.factor.application.FactorObservationEvidence.writeNew(request.output(),rows,false,fingerprint);
            return Map.of("rows",rows.size(),"sha256",sha,"sourceComplete",false,"factorReady",false,
                    "factorRows",counts,"validFactorRows",validCounts,
                    "emptyFactors",validCounts.entrySet().stream().filter(e->e.getValue()==0).map(Map.Entry::getKey).toList());
    }
    public Map<String,Object> compute(Request request)throws Exception {
        var history=request.historyFrom();var from=request.from();
        var through=request.through();
        if(history.isAfter(from) || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>30)
            throw new IllegalArgumentException("ETF output window must be 1..31 calendar days following history start");
        Path output=request.output().toAbsolutePath().normalize();
        if(Files.exists(output))throw new IllegalArgumentException("Output already exists");
        var reader=new QuestDbEtfInputs(jdbc);long start=System.nanoTime();
        var snapshot=reader.read(history,through,1_000_000);long readNanos=System.nanoTime()-start;
        long l2Start=System.nanoTime();
        var l2Reader=new com.zoutrankil.data.factor.storage.QuestDbEtfL2(jdbc);
        var l2=l2Reader.read(from,through,1_000_000);
        long l2ReadNanos=System.nanoTime()-l2Start;
        long stockStart=System.nanoTime();
        var stockReader=new com.zoutrankil.data.factor.storage.QuestDbDailyFactorPanel(jdbc);
        var csiSources=stockReader.readCsi(history,through,5_000_000);
        var stocks=csiSources.market();
        long stockReadNanos=System.nanoTime()-stockStart;
        var outputDates=new TreeSet<LocalDate>();
        snapshot.market().inputs().forEach(input->{for(var date:input.dates())if(!date.isBefore(from))outputDates.add(date);});
        var constituentReader=new com.zoutrankil.data.factor.storage.QuestDbEtfConstituents(jdbc);
        var holdings=new TreeMap<LocalDate,List<EtfConstituentWeights.Member>>();
        var holdingEvidence=new ArrayList<Map<String,Object>>();
        com.zoutrankil.data.factor.storage.QuestDbEtfConstituents.Snapshot lastHoldings=null;
        long holdingRows=0;long holdingStart=System.nanoTime();
        for(var date:outputDates) {
            var current=constituentReader.read(date,1_000_000);
            if(lastHoldings!=null && !lastHoldings.sourceState().equals(current.sourceState()))
                throw new IllegalStateException("Constituent source changed across output dates");
            var members=current.normalize(snapshot.market().categories().keySet());holdingRows+=members.size();
            if(holdingRows>2_000_000)throw new IllegalStateException("Dated holdings exceed memory budget; split output interval");
            holdings.put(date,members);holdingEvidence.add(Map.of("date",date,"sourceRows",current.rows().size(),"members",members.size()));
            lastHoldings=current;
        }
        long holdingReadNanos=System.nanoTime()-holdingStart;
        long earningsStart=System.nanoTime();
        var earningsReader=new com.zoutrankil.data.factor.storage.QuestDbEtfEarnings(jdbc);
        var earnings=earningsReader.read(from,through,1_000_000);
        long earningsReadNanos=System.nanoTime()-earningsStart;
        long csiReadStart=System.nanoTime();
        var financialReader=new com.zoutrankil.data.factor.storage.QuestDbCsiFinancials(jdbc);
        var financials=financialReader.read(from,through,1_000_000);
        if(outputDates.isEmpty())throw new IllegalStateException("No ETF output dates");
        var csiWindow=constituentReader.readWindow(from,through,outputDates.last(),snapshot.market().categories().keySet(),1_000_000);
        var csiHistory=csiWindow.history();
        long csiReadNanos=System.nanoTime()-csiReadStart;
        var technicalReader=new com.zoutrankil.data.factor.storage.QuestDbEtfTechnical(jdbc);
        var technical=technicalReader.read(from,through,1_000_000);
        start=System.nanoTime();
        var csiHoldings=csiHistory.csiHoldings(snapshot.market().categories().keySet());
        var csi=EtfCsiPipeline.compute(csiSources.prepare(financials),csiHoldings,from,through,outputDates);
        var earningsRows=new ArrayList<Map<String,Object>>();
        for(var entry:holdings.entrySet()) {
            var exposures=entry.getValue().stream().map(member->new EtfEarningsOutlook.Exposure(member.etf(),member.stock(),member.weight())).toList();
            for(var row:EtfEarningsOutlook.aggregate(earnings.latest(entry.getKey()),exposures,entry.getKey())) {
                var values=new LinkedHashMap<String,Object>();values.put("symbol",row.etf());values.put("date",row.date());
                values.put("weighted_outlook",finite(row.weightedOutlook()));values.put("earnings_coverage",finite(row.coverage()));
                values.put("component_earnings_outlook",finite(row.value()));earningsRows.add(values);
            }
        }
        var computed=EtfConstituentPipeline.compute(snapshot.market(),snapshot.shares(),stocks.securities(),holdings,l2.dates(),l2.rows()>0);
        var panel=computed.market();
        var risk=EtfRiskContext.compute(snapshot.market().inputs(),panel.securities());
        long computeNanos=System.nanoTime()-start;var sections=new ArrayList<Map<String,Object>>();long rows=0;
        for(var security:panel.securities()) {
            int first=0,end=security.dates().length;
            while(first<end && security.dates()[first].isBefore(from))first++;
            if(first==end)continue;
            rows+=end-first;if(rows>100_000)throw new IllegalStateException("ETF output exceeds 100000 rows");
            var columns=new LinkedHashMap<String,Object>();
            for(var entry:security.columns().entrySet()) {
                Double[] values=new Double[end-first];
                for(int i=first;i<end;i++)values[i-first]=Double.isFinite(entry.getValue()[i])?entry.getValue()[i]:null;
                columns.put(entry.getKey(),values);
            }
            sections.add(Map.of("symbol",security.symbol(),"dates",Arrays.copyOfRange(security.dates(),first,end),"columns",columns));
        }
        if(rows==0)throw new IllegalStateException("No observed ETF output dates");
        var identities=new ArrayList<EtfSelfL2.Market>();
        for(var security:panel.securities())for(var date:security.dates())identities.add(new EtfSelfL2.Market(
                date,security.symbol(),snapshot.market().names().get(security.symbol()),snapshot.market().categories().get(security.symbol())));
        var selfL2=new ArrayList<Map<String,Object>>();
        for(var row:EtfSelfL2.compute(l2.selfInputs(),identities,Set.of())) {
            var values=new LinkedHashMap<String,Object>();values.put("date",row.date());values.put("symbol",row.symbol());
            values.put("name",row.name());values.put("category",row.category());
            values.put("etf_l2_obi1",finite(row.obi1()));values.put("etf_l2_ofi",finite(row.ofi()));
            values.put("etf_l2_spread",finite(row.spread()));values.put("etf_l2_depth",finite(row.depth()));selfL2.add(values);
        }
        l2Reader.verifyUnchanged(l2);
        technicalReader.verifyUnchanged(technical);
        stockReader.verifyUnchanged(csiSources);
        financialReader.verifyUnchanged(financials);
        constituentReader.verifyUnchanged(csiHistory);
        earningsReader.verifyUnchanged(earnings);
        if(lastHoldings!=null)constituentReader.verifyUnchanged(lastHoldings);
        reader.verifyUnchanged(snapshot);
        var report=new LinkedHashMap<String,Object>();report.put("schema","java.etf_shared_panel.v1");
        report.put("historyFrom",history);report.put("from",from);report.put("through",through);
        report.put("kernelSha256",FactorComputationIdentity.sha256(EtfFactorPanelComputation.class));report.put("sourceState",snapshot.sourceState());
        report.put("marketSourceRows",snapshot.marketRows());report.put("shareSourceRows",snapshot.shareRows());
        report.put("l2SourceState",l2.sourceState());report.put("l2SourceRows",l2.rows());
        report.put("l2ReadNanos",l2ReadNanos);report.put("selfL2",selfL2);
        report.put("stockSourceState",stocks.sourceState());report.put("stockSourceRows",stocks.sourceRows());
        report.put("stockReadNanos",stockReadNanos);report.put("holdingReadNanos",holdingReadNanos);
        report.put("holdingDates",holdingEvidence);report.put("holdingSourceState",lastHoldings==null?List.of():lastHoldings.sourceState());
        var componentRows=new ArrayList<Map<String,Object>>();
        for(var row:computed.components()) {
            var columns=new LinkedHashMap<String,Double>();row.columns().forEach((name,value)->columns.put(name,finite(value)));
            componentRows.add(Map.of("symbol",row.etf(),"date",row.date(),"columns",columns));
        }
        report.put("components",componentRows);
        var marketKeys=new HashSet<List<Object>>();
        for(var identity:identities)marketKeys.add(List.of(identity.date(),identity.symbol()));
        var technicalRows=new ArrayList<Map<String,Object>>();
        for(var row:technical.rows())if(marketKeys.contains(List.of(row.date(),row.symbol()))) {
            var values=new LinkedHashMap<String,Object>();values.put("date",row.date());values.put("symbol",row.symbol());
            values.put("technical_rsi_6d",finite(row.rsi6()));technicalRows.add(values);
        }
        report.put("technical",technicalRows);report.put("technicalSourceRows",technical.rows().size());
        report.put("technicalSourceState",technical.sourceState());
        var csiRows=new ArrayList<Map<String,Object>>();
        for(var row:csi) {
            var values=new LinkedHashMap<String,Object>();values.put("date",row.date());values.put("symbol",row.etf());
            values.put("factor",row.factor());values.put("value",finite(row.value()));values.put("coverage",finite(row.coverage()));
            values.put("componentCount",row.componentCount());values.put("announced",row.announced());values.put("periodEnd",row.periodEnd());
            csiRows.add(values);
        }
        report.put("csiStyles",csiRows);report.put("csiReadNanos",csiReadNanos);
        report.put("csiRefresh",csiWindow.refresh());
        report.put("csiFinancialSourceState",financials.sourceState());report.put("csiFinancialRows",financials.rows());
        report.put("csiFinancialRetainedEvents",financials.tables().values().stream().flatMap(t->t.values().stream()).mapToLong(List::size).sum());
        report.put("csiHoldingSourceState",csiHistory.sourceState());report.put("csiHoldingRows",csiHistory.rows().size());
        report.put("csiMarketSourceState",csiSources.sourceState());report.put("csiBasicRows",csiSources.basic().sourceRows());
        report.put("earnings",earningsRows);report.put("earningsSourceState",earnings.sourceState());
        report.put("earningsSourceRows",earnings.sourceRows());report.put("earningsReadNanos",earningsReadNanos);
        report.put("earningsRetainedEvents",earnings.forecasts().size()+earnings.express().size());
        var riskRows=new ArrayList<Map<String,Object>>();
        for(var row:risk.rows())if(!row.date().isBefore(from)) {
            var values=new LinkedHashMap<String,Object>();values.put("date",row.date());
            values.put("average_correlation_20d",finite(row.averageCorrelation()));values.put("correlation_z_60d",finite(row.correlationZ()));
            values.put("breadth_1d",finite(row.breadth()));values.put("cross_section_volatility",finite(row.crossSectionVolatility()));
            values.put("liquidity_ratio_5d_20d",finite(row.liquidityRatio()));values.put("state",row.state());riskRows.add(values);
        }
        report.put("riskContext",riskRows);report.put("riskSelectionDates",risk.selectionDates());report.put("riskSelectedSymbols",risk.selectedSymbols());
        report.put("riskHistoricalRevisionPolicy","Selection uses snapshot last 100 dates; changed window or input requires recomputing affected history before incremental publication.");
        report.put("readNanos",readNanos);report.put("computeNanos",computeNanos);report.put("outputRows",rows);
        report.put("sourceComplete",false);report.put("referencePointInTime",false);report.put("factorReady",false);
        report.put("missingStages",List.of("publication"));
        report.put("securities",sections);
        FileEvidenceStore.writeNewDurable(output,JobDefinitionJson.mapper().writeValueAsBytes(report));
        return Map.of("output",output.toString(),"rows",rows,"securities",sections.size(),"factorReady",false);
    }
    private static Double finite(double value){return Double.isFinite(value)?value:null;}
}
