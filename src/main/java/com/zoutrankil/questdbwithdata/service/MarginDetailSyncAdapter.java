package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.MarginDetailWritePort;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;

/** Sequential date-sliced fetch adapter with full-key checks and durable source-completion evidence. */
public final class MarginDetailSyncAdapter implements SyncJobRunner.Adapter<MarginDetail,MarginDetailKey> {
    private final MarginDetailSource source;private final MarginDetailTradingDates calendar;private final MarginDetailWritePort port;private final Path evidenceRoot;private final String targetId;private final boolean resume;
    public MarginDetailSyncAdapter(MarginDetailSource source,MarginDetailTradingDates calendar,MarginDetailWritePort port,Path evidenceRoot,String targetId,boolean resume){
        this.source=Objects.requireNonNull(source);this.calendar=Objects.requireNonNull(calendar);this.port=Objects.requireNonNull(port);this.evidenceRoot=Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();this.targetId=Objects.requireNonNull(targetId);this.resume=resume;
    }
    @Override public void preflight(SyncJobDefinition.FrozenRequest request)throws Exception{
        validateRequest(request);if(!targetId.equals(request.parameters().get("targetId")))throw new IllegalStateException("Frozen D029 physical target differs from writer target");port.preflight();
        if(!decodeDates(request).equals(calendar.read(request.from(),request.to())))throw new IllegalStateException("D029 SSE open-date calendar changed after request freeze");
        if(!resume){var physical=port.readTargetRange();var params=request.parameters();if(physical.rows()!=Long.parseLong((String)params.get("targetRowsBefore"))
                    ||!Objects.equals(physical.min(),params.get("targetMinBefore"))||!Objects.equals(physical.max(),params.get("targetMaxBefore")))throw new IllegalStateException("D029 physical target baseline changed after plan; replan before a new run");}
    }
    @Override public SyncJobRunner.SourceCompletion fetch(SyncJobDefinition.FrozenRequest request,SyncJobRunner.PageConsumer<MarginDetail> consumer,BooleanSupplier cancelled)throws Exception{
        var dates=decodeDates(request);var refs=new ArrayList<Map<String,Object>>();int total=0;
        for(LocalDate date:dates){check(cancelled);var page=source.fetch(date,cancelled);verifyNoSourceKeyRemoval(date,page.rows());consumer.accept(page);total=Math.addExact(total,page.rows().size());
            refs.add(Map.of("tradeDate",date.toString(),"rows",page.rows().size(),"sourceFingerprint",page.sourceFingerprint(),"responseEvidence",page.responseEvidence()));}
        if(dates.isEmpty()||dates.size()>MarginDetailSyncJobOwner.MAX_SOURCE_SLICES||total<1||total>MarginDetailSyncJobOwner.DEFINITION.budget().maxRows())throw new IllegalStateException("D029 source completion is empty or exceeds the frozen bounded window");
        Files.createDirectories(evidenceRoot);var body=new LinkedHashMap<String,Object>();body.put("dataset","margin_detail");body.put("endpoint","margin_detail");body.put("targetId",targetId);body.put("mode",request.mode().name());
        body.put("fromInclusive",request.from().toString());body.put("toInclusive",request.to().toString());body.put("logicalDate",request.logicalDate().toString());body.put("tradeDates",dates);
        body.put("completedDateSlices",dates.size());body.put("sourceRows",total);body.put("returnedRows",total);body.put("sourceReceipts",refs);body.put("sourceComplete",true);body.put("complete",true);
        byte[] bytes=JobDefinitionJson.mapper().copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsBytes(body);if(bytes.length>1024*1024)throw new IllegalStateException("D029 complete-window manifest exceeds 1 MiB");
        Path complete=evidenceRoot.resolve("complete-window.json");try{Files.write(complete,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}catch(FileAlreadyExistsException exists){if(!Arrays.equals(Files.readAllBytes(complete),bytes))throw new IllegalStateException("Conflicting D029 completion manifest",exists);}
        return new SyncJobRunner.SourceCompletion(dates.size(),total,true,complete.toString());
    }
    private void verifyNoSourceKeyRemoval(LocalDate date,List<MarginDetail> rows){var keys=new HashSet<MarginDetailKey>();for(var row:rows)if(!keys.add(row.key()))throw new IllegalStateException("D029 duplicate source natural key");
        var existing=port.readDate(date);if(existing.stream().anyMatch(row->!keys.contains(row.key())))throw new IllegalStateException("D029 provider omitted an existing date key; no source deletion/tombstone contract exists");}
    public static List<LocalDate> decodeDates(SyncJobDefinition.FrozenRequest request){Object raw=request.parameters().get("trade_dates");if(!(raw instanceof String encoded)||encoded.isBlank())throw new IllegalArgumentException("D029 frozen request requires SSE trade dates");
        var dates=Arrays.stream(encoded.split(",",-1)).map(s->{if(!s.matches("[0-9]{8}"))throw new IllegalArgumentException("D029 source dates must be YYYYMMDD");return LocalDate.parse(s,DateTimeFormatter.BASIC_ISO_DATE);}).toList();
        if(dates.size()>MarginDetailSyncJobOwner.MAX_SOURCE_SLICES||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList())||dates.stream().anyMatch(d->d.isBefore(request.from())||d.isAfter(request.to())))throw new IllegalArgumentException("D029 source dates are duplicate, unordered or outside the frozen window");return dates;}
    public static void validateRequest(SyncJobDefinition.FrozenRequest request){
        if(request==null||!request.definition().equals(MarginDetailSyncJobOwner.DEFINITION)||request.definition().datasetVersion()!=MarginDetailDataset.DEFINITION.schemaVersion()
                ||!Set.of(Mode.INCREMENTAL,Mode.BACKFILL).contains(request.mode())||request.from()==null||request.to()==null||request.from().isAfter(request.to())
                ||request.to().isAfter(request.logicalDate())||ChronoUnit.DAYS.between(request.from(),request.to())+1>MarginDetailSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalArgumentException("Frozen bounded D029 request required");
        Map<String,Object> p=request.parameters();Set<String> allowed=Set.of("targetId","trade_dates","targetRowsBefore","targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore");
        if(!allowed.containsAll(p.keySet())||!p.keySet().containsAll(Set.of("targetId","trade_dates","targetRowsBefore","checkpointAnchor")))throw new IllegalArgumentException("Unexpected/missing D029 frozen target/checkpoint parameters");
        if(!(p.get("targetId") instanceof String target)||!target.matches("static-v2-[0-9a-f]{64}")||!(p.get("targetRowsBefore") instanceof String rowCount)||!rowCount.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Frozen D029 physical target identity/count required");
        for(String field:List.of("targetMinBefore","targetMaxBefore","checkpointAnchor","checkpointBefore"))if(p.get(field)!=null&&!(p.get(field) instanceof LocalDate))throw new IllegalArgumentException("Invalid frozen D029 date parameter "+field);
        if((p.get("targetMinBefore")==null)!=(p.get("targetMaxBefore")==null))throw new IllegalArgumentException("D029 physical target bounds must be paired");
        if(!(p.get("checkpointAnchor") instanceof LocalDate anchor)||request.from().isBefore(anchor))throw new IllegalArgumentException("D029 frozen checkpoint anchor is required and must precede the request");
        LocalDate before=(LocalDate)p.get("checkpointBefore");if(request.mode()==Mode.INCREMENTAL){if(before==null&&!request.from().equals(anchor))throw new IllegalArgumentException("D029 bootstrap must start at its frozen anchor");
            if(before!=null&&(!before.isAfter(anchor.minusDays(1))||before.isAfter(request.to())||!request.from().equals(max(anchor,before.minusDays(MarginDetailSyncJobOwner.REVISION_DAYS)))))throw new IllegalArgumentException("D029 incremental overlap differs from frozen checkpoint");}
        else if(before==null||request.from().isBefore(anchor)||request.to().isAfter(before))throw new IllegalArgumentException("D029 BACKFILL must stay inside its frozen checkpoint coverage");decodeDates(request);
    }
    private static LocalDate max(LocalDate a,LocalDate b){return a.isAfter(b)?a:b;}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("D029 cancelled between bounded trade-date source calls");}
    @Override public VerifiedBatchExecutor.Codec<MarginDetail,MarginDetailKey> codec(){return MarginDetailWritePort.CODEC;}
    @Override public VerifiedBatchExecutor.Port<MarginDetail,MarginDetailKey> port(){return port;}
}
