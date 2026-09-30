package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsDataset;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncJobDefinition.FrozenRequest;
import com.zoutrankil.data.repository.ExchangeCalendarReadRepository;
import com.zoutrankil.data.repository.MarginSecsStorage;
import com.zoutrankil.data.repository.MarginSecsWritePort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.Mode;

/** D030 one-source-call-per-SSE-session adapter with full-day readback after every write slice. */
public final class MarginSecsSyncAdapter implements SyncJobRunner.Adapter<MarginSecs,MarginSecsKey> {
    private final MarginSecsSource source;
    private final ExchangeCalendarReadRepository calendars;
    private final MarginSecsWritePort port;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final Path evidenceRoot;
    public MarginSecsSyncAdapter(MarginSecsSource source,ExchangeCalendarReadRepository calendars,MarginSecsWritePort port,
            org.springframework.jdbc.core.JdbcTemplate jdbc,Path evidenceRoot){
        this.source=Objects.requireNonNull(source);this.calendars=Objects.requireNonNull(calendars);this.port=Objects.requireNonNull(port);
        this.jdbc=Objects.requireNonNull(jdbc);
        this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
    }
    @Override public void preflight(FrozenRequest request){validateRequest(request);port.preflight();}

    @Override public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<MarginSecs> consumer,
            BooleanSupplier cancelled)throws Exception {
        validateRequest(request);
        var encoded=split(request.parameters().get("calendarDays"));var open=split(request.parameters().get("tradeDates"));
        List<LocalDate> dates=MarginSecsTradingDates.validateFrozen(request.from(),request.to(),encoded,
                (String)request.parameters().get("calendarFingerprint"),open);
        int pages=0,rows=0;var receipts=new ArrayList<Map<String,Object>>();
        for(LocalDate date:dates){check(cancelled);var page=source.fetch(date,cancelled);var sourceKeys=new HashSet<MarginSecsKey>();
            for(var row:page.rows())if(!date.equals(row.tradeDate())||!sourceKeys.add(row.key()))throw new IllegalStateException("D030 source has a duplicate/out-of-date full key");
            var existingRows=new MarginSecsStorage(jdbc,port.table()).readDate(date);
            for(var old:existingRows)if(!sourceKeys.contains(old.key()))
                throw new IllegalStateException("D030 source omitted an existing natural key; removal needs an explicit publication policy");
            consumer.accept(page);
            var actual=port.readDate(date);if(!MarginSecsStorage.sameRows(page.rows(),actual))throw new IllegalStateException("D030 all-field date readback mismatch after ACK");
            receipts.add(Map.of("tradeDate",date,"rows",page.rows().size(),"sourceFingerprint",page.sourceFingerprint(),"responseEvidence",page.responseEvidence()));
            pages=Math.addExact(pages,1);rows=Math.addExact(rows,page.rows().size());
        }
        Files.createDirectories(evidenceRoot);Path complete=evidenceRoot.resolve("complete-window.json");var body=new LinkedHashMap<String,Object>();
        body.put("jobId",MarginSecsSyncJobOwner.DEFINITION.jobId());body.put("jobVersion",MarginSecsSyncJobOwner.DEFINITION.version());
        body.put("datasetId","margin_secs");body.put("mode",request.mode());body.put("targetId",request.parameters().get("targetId"));
        body.put("physicalTargetId",request.parameters().get("physicalTargetId"));body.put("fromInclusive",request.from());body.put("toInclusive",request.to());
        body.put("logicalDate",request.logicalDate());body.put("calendarFingerprint",request.parameters().get("calendarFingerprint"));
        body.put("calendarDays",encoded);body.put("tradeDates",dates);body.put("completedDateSlices",pages);body.put("sourceRows",rows);
        body.put("sourceReceipts",receipts);body.put("complete",true);
        byte[] bytes=JobDefinitionJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(body);
        if(bytes.length>2*1024*1024)throw new IllegalStateException("D030 window completion receipt exceeds two MiB");
        try{Files.write(complete,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
        catch(java.nio.file.FileAlreadyExistsException collision){throw new IllegalStateException("D030 completion receipt path collision",collision);}
        return new SyncJobRunner.SourceCompletion(pages,rows,true,complete.toString());
    }
    @Override public VerifiedBatchExecutor.Codec<MarginSecs,MarginSecsKey> codec(){return MarginSecsWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<MarginSecs,MarginSecsKey> port(){return port;}

    public static void validateRequest(FrozenRequest request){
        if(request==null||!MarginSecsSyncJobOwner.DEFINITION.equals(request.definition())||request.definition().datasetVersion()!=MarginSecsDataset.DEFINITION.schemaVersion()
                ||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode())||request.from()==null||request.to()==null
                ||request.from().isAfter(request.to())||request.to().isAfter(request.logicalDate())
                ||ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginSecsSyncJobOwner.MAX_WINDOW_DAYS)
            throw new IllegalArgumentException("Frozen bounded D030 request required");
        var p=request.parameters();Set<String> allowed=MarginSecsSyncJobOwner.DEFINITION.parameters().keySet();
        if(!p.keySet().containsAll(Set.of("targetId","physicalTargetId","targetRowsBefore","targetFingerprint","calendarFingerprint","calendarDays","tradeDates"))||!allowed.containsAll(p.keySet()))
            throw new IllegalArgumentException("D030 frozen target/calendar parameters are missing or unexpected");
        for(String name:List.of("targetId","physicalTargetId"))if(!(p.get(name) instanceof String id)||!id.matches("static-v2-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Frozen D030 target identity required");
        if(!Objects.equals(p.get("targetId"),p.get("physicalTargetId")))throw new IllegalArgumentException("D030 logical and physical target bindings must identify the same frozen table generation");
        if(!(p.get("targetRowsBefore") instanceof Integer count)||count<0||!(p.get("targetFingerprint") instanceof String fp)||!fp.matches("[0-9a-f]{64}")
                ||!(p.get("calendarFingerprint") instanceof String cal)||!cal.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("D030 frozen target/calendar fingerprints required");
        if((p.get("targetMinBefore")==null)!=(p.get("targetMaxBefore")==null))throw new IllegalArgumentException("D030 physical bounds must be paired");
        if(!(p.get("targetRowsBefore") instanceof Integer targetRows)||targetRows<0
                ||(targetRows==0)!=(p.get("targetMinBefore")==null))throw new IllegalArgumentException("D030 physical bounds/count disagree");
        for(String name:List.of("targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore"))if(p.get(name)!=null&&!(p.get(name) instanceof LocalDate))
            throw new IllegalArgumentException("Invalid D030 frozen date parameter");
        if(request.mode()==Mode.INCREMENTAL){if(!(p.get("checkpointAnchor") instanceof LocalDate anchor)||request.from().isBefore(anchor))throw new IllegalArgumentException("D030 incremental anchor required");
            Object before=p.get("checkpointBefore");if(before==null&&!request.from().equals(anchor))throw new IllegalArgumentException("D030 first incremental must start at explicit bootstrap anchor");
            if(before!=null&&(!(before instanceof LocalDate prior)||prior.isBefore(anchor)||prior.isAfter(request.to())
                    ||!request.from().equals(prior.minusDays(MarginSecsSyncJobOwner.REVISION_DAYS-1L).isBefore(anchor)?anchor:prior.minusDays(MarginSecsSyncJobOwner.REVISION_DAYS-1L))))
                throw new IllegalArgumentException("Invalid D030 prior checkpoint or overlap window");}
        else if(p.get("checkpointAnchor")!=null||p.get("checkpointBefore")!=null)throw new IllegalArgumentException("D030 repair request cannot carry checkpoint fields");
        var calendar=split(p.get("calendarDays"));var open=split(p.get("tradeDates"));MarginSecsTradingDates.validateFrozen(request.from(),request.to(),calendar,(String)p.get("calendarFingerprint"),open);
    }
    private static List<String> split(Object value){if(!(value instanceof String text)||text.isBlank())return List.of();return List.of(text.split(",",-1));}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D030 cancelled between bounded daily source calls");}
}
