package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.storage.IndexWeightWritePort;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import io.questdb.client.QuestDB;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Fill absent formal membership keys from a captured response; never certify historical availability. */
public final class IndexWeightCapturedJob {
    private IndexWeightCapturedJob() {}
    public static final SyncJobDefinition DEFINITION=new SyncJobDefinition(
            "data.index_weight_captured",1,"index_weight",1,"index_weight_owner",
            Set.of(Mode.BACKFILL),Mode.BACKFILL,
            Map.of("targetId",new Parameter(ParameterType.STRING,true,128,1,Set.of()),
                   "sourceSha256",new Parameter(ParameterType.STRING,true,64,1,Set.of())),
            "local.frozen_index_weight","index_weight.index_month","questdb.full_key_values",
            new RetryPolicy(1,Duration.ofSeconds(1),Duration.ofMinutes(3)),Duration.ofMinutes(30),
            new Budget(31,40,40,10000,1024*1024),0,List.of(),Frequency.MANUAL,ZoneId.of("Asia/Shanghai"),true,false);
    public static Map<String,Object> execute(JdbcTemplate jdbc,QuestDB questdb,Path ledger,Path source,
            Path evidenceRoot,LocalDate logical,boolean publish)throws Exception {
        if(logical.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))throw new IllegalArgumentException("Future logical date");
        var json=JobDefinitionJson.canonicalMapper();byte[] bytes=read(source);String sha=FileEvidenceStore.sha256(bytes);
        var root=json.readTree(bytes);var params=root.path("params");String provider=params.path("index_code").asText();
        if(!"index_weight".equals(root.path("endpoint").asText()) || !root.path("code").isIntegralNumber()
                || root.path("code").intValue()!=0 || !provider.matches("[0-9]{6}\\.(SH|SZ|CSI)")
                || !root.path("rows").isArray() || root.path("rows").isEmpty() || root.path("rows").size()>10000)
            throw new IllegalArgumentException("Bounded successful captured index_weight response required");
        var fields=new HashSet<String>();for(var f:root.path("fields"))if(!f.isTextual() || !fields.add(f.asText()))throw new IllegalArgumentException("Unique fields required");
        if(!fields.equals(Set.of("index_code","con_code","trade_date","weight")))throw new IllegalArgumentException("Exact index_weight source fields required");
        LocalDate from=LocalDate.parse(params.path("start_date").asText(),DateTimeFormatter.BASIC_ISO_DATE),
                to=LocalDate.parse(params.path("end_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
        if(from.isAfter(to) || to.isAfter(logical) || to.toEpochDay()-from.toEpochDay()>30)throw new IllegalArgumentException("Bounded historical source interval required");
        Instant captured=Instant.parse(root.path("observedAt").asText());
        if(captured.isAfter(Instant.now()) || captured.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().isAfter(logical))throw new IllegalArgumentException("Actual capture time required");
        String code=provider.substring(0,6);var supplied=new LinkedHashMap<IndexWeightKey,IndexWeight>();
        for(var node:root.path("rows")) {
            LocalDate date=LocalDate.parse(node.path("trade_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
            if(!provider.equals(node.path("index_code").asText()) || date.isBefore(from) || date.isAfter(to)
                    || date.isAfter(captured.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()) || !node.path("weight").isNumber())throw new IllegalArgumentException("Source row outside captured scope");
            String symbol=node.path("con_code").asText();
            if(!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("Canonical stock identity required");
            var row=new IndexWeight(code,symbol,date,null,null,null,null,symbol.substring(7),null,node.path("weight").doubleValue(),captured);
            if(supplied.putIfAbsent(row.key(),row)!=null)throw new IllegalArgumentException("Duplicate source membership key");
        }
        QuestDbWriteChecks.preflight(jdbc,"index_weight",IndexWeightDataset.DEFINITION);
        var physical=jdbc.queryForMap("SELECT id,directoryName FROM tables() WHERE table_name='index_weight'");
        String target=StaticTargetIdentity.identify(jdbc,"index_weight",((Number)physical.get("id")).longValue(),(String)physical.get("directoryName"));
        var port=IndexWeightWritePort.formal(target,jdbc,questdb);port.preflight();
        if(!port.walSettled())throw new IllegalStateException("Membership WAL unsettled");
        var before=port.readRange(code,from,to);var expected=new HashMap<IndexWeightKey,IndexWeight>();
        for(var row:before)if(expected.putIfAbsent(row.key(),row)!=null)throw new IllegalStateException("Duplicate physical membership key");
        var additions=new ArrayList<IndexWeight>();int conflicts=0;
        for(var row:supplied.values()) {
            var old=expected.get(row.key());
            if(old==null){additions.add(row);expected.put(row.key(),row);}
            else if(!old.weight().equals(row.weight()))conflicts++;
        }
        additions.sort(Comparator.comparing(IndexWeight::tradeDate).thenComparing(IndexWeight::conCode));
        Path dir=evidenceRoot.toAbsolutePath().normalize();Files.createDirectories(dir.getParent());Files.createDirectory(dir);
        String runId="index-weight-captured-"+UUID.randomUUID();
        var report=new LinkedHashMap<String,Object>();report.put("runId",runId);report.put("writerPid",ProcessHandle.current().pid());
        report.put("sourcePath",source.toAbsolutePath().normalize().toString());
        report.put("sourceSha256",sha);report.put("targetId",target);report.put("capturedAt",captured);
        report.put("sourceRows",supplied.size());report.put("existingRows",before.size());report.put("missingRows",additions.size());
        report.put("conflictingWeightsPreserved",conflicts);report.put("sourceComplete",false);report.put("historicalAvailabilityCertified",false);
        FileEvidenceStore.writeNewDurable(dir.resolve("before.json"),json.writeValueAsBytes(before));
        FileEvidenceStore.writeNewDurable(dir.resolve("additions.json"),json.writeValueAsBytes(additions));
        FileEvidenceStore.writeNewDurable(dir.resolve("plan.json"),json.writeValueAsBytes(report));
        report.put("executed",false);if(!publish || additions.isEmpty())return report;
        var request=DEFINITION.freeze(Mode.BACKFILL,Map.of("targetId",target,"sourceSha256",sha),from,to,logical);
        var adapter=new SyncJobRunner.Adapter<IndexWeight,IndexWeightKey>() {
            boolean submitted,verified;
            void unchanged()throws Exception{if(!sha.equals(FileEvidenceStore.sha256(read(source))))throw new IllegalStateException("Captured source changed");}
            public void preflight(FrozenRequest request)throws Exception{unchanged();port.preflight();}
            public boolean recoveryRequired(String id){return submitted && !verified;}
            public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<IndexWeight> consumer,BooleanSupplier stop)throws Exception {
                unchanged();if(!port.walSettled() || !before.equals(port.readRange(code,from,to)))throw new IllegalStateException("Membership changed before lock acquisition");
                int pages=0;
                for(int start=0;start<additions.size();start+=250){
                    if(stop.getAsBoolean())throw new java.util.concurrent.CancellationException();
                    unchanged();int end=Math.min(start+250,additions.size());submitted=true;
                    String fingerprint=FileEvidenceStore.sha256(json.writeValueAsBytes(List.of(sha,start,end)));
                    consumer.accept(new SyncJobRunner.Page<>(additions.subList(start,end),fingerprint,source.toAbsolutePath().toString(),Integer.toString(pages++)));
                }
                unchanged();var actual=new HashMap<IndexWeightKey,IndexWeight>();
                for(var row:port.readRange(code,from,to))if(actual.putIfAbsent(row.key(),row)!=null)throw new IllegalStateException("Duplicate post-write membership");
                if(!actual.equals(expected) || !port.walSettled())throw new IllegalStateException("Full membership window differs after write");
                FileEvidenceStore.writeNewDurable(dir.resolve("verified.json"),json.writeValueAsBytes(Map.of("rows",actual.size(),"sourceSha256",sha,"historicalAvailabilityCertified",false)));
                verified=true;return new SyncJobRunner.SourceCompletion(pages,additions.size(),false,dir.resolve("verified.json").toString());
            }
            public VerifiedBatchExecutor.Codec<IndexWeight,IndexWeightKey> codec(){return port.codec();}
            public VerifiedBatchExecutor.Port<IndexWeight,IndexWeightKey> port(){return port;}
            public Duration visibilityTimeout(){return Duration.ofMinutes(3);}
        };
        var result=SyncRunExecution.execute(ledger,runId,null,target,request,"Membership cancellation lookup failed",()->adapter);
        report.put("executed",true);report.put("result",result);
        FileEvidenceStore.writeNewDurable(dir.resolve("result.json"),json.writeValueAsBytes(report));return report;
    }
    private static byte[] read(Path source)throws Exception {
        if(Files.isSymbolicLink(source) || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Regular captured source required");
        return FileEvidenceStore.readBounded(source,8*1024*1024,()->new IllegalArgumentException("Source exceeds 8 MiB"));
    }
}
