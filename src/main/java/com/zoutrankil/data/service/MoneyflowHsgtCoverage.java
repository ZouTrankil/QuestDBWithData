package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MoneyflowHsgt;
import com.zoutrankil.data.domain.MoneyflowHsgtKey;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.MoneyflowHsgtStorage;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** D027 checkpoint and physical date/value coverage backed by raw receipts and verified publication journal entries. */
public final class MoneyflowHsgtCoverage {
    private static final int PAGE=100,MAX_HISTORY=10_000,MAX_ENTRIES=1000;
    private MoneyflowHsgtCoverage(){}
    public record Checkpoint(LocalDate anchor,LocalDate through,Instant latestVerifiedAt){
        public Checkpoint{Objects.requireNonNull(anchor);Objects.requireNonNull(through);Objects.requireNonNull(latestVerifiedAt);if(through.isBefore(anchor))throw new IllegalArgumentException("Invalid D027 checkpoint");}
    }
    private record Slice(LocalDate from,LocalDate to,SyncJobRunner.Page<MoneyflowHsgt> page){}
    private record Interval(String runId,SyncJobDefinition.Mode mode,LocalDate from,LocalDate to,LocalDate anchor,
            Instant verifiedAt,List<Slice> slices){}
    public static Checkpoint checkpoint(Path ledgerPath,String targetId)throws Exception {
        var intervals=verifiedIntervals(ledgerPath,targetId,EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL));
        var grouped=new HashMap<LocalDate,List<Interval>>();
        for(var interval:intervals){if(interval.anchor()==null)throw new IllegalStateException("D027 incremental receipt lacks explicit bootstrap anchor");grouped.computeIfAbsent(interval.anchor(),k->new ArrayList<>()).add(interval);}
        var checkpoints=new ArrayList<Checkpoint>();
        for(var entry:grouped.entrySet()){
            LocalDate anchor=entry.getKey(),through=anchor.minusDays(1);Instant latest=Instant.MIN;
            var ordered=entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)
                    .thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();boolean started=false;
            for(var interval:ordered){if(!started){if(!interval.from().equals(anchor))continue;started=true;}
                if(interval.from().isAfter(through.plusDays(1)))break;if(interval.to().isAfter(through))through=interval.to();
                if(interval.verifiedAt().isAfter(latest))latest=interval.verifiedAt();}
            if(started&&!through.isBefore(anchor))checkpoints.add(new Checkpoint(anchor,through,latest));
        }
        return checkpoints.stream().max(Comparator.comparing(Checkpoint::through).thenComparing(Checkpoint::latestVerifiedAt)
                .thenComparing(Checkpoint::anchor,Comparator.reverseOrder())).orElse(null);
    }

    /** Every physical date/value must be backed by the latest verified window receipt for the same logical target. */
    public static int validateTarget(Path ledgerPath,String targetId,MoneyflowHsgtStorage.Snapshot target)throws Exception {
        var intervals=verifiedIntervals(ledgerPath,targetId,EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL,
                SyncJobDefinition.Mode.BACKFILL,SyncJobDefinition.Mode.RECONCILE));
        if(target.rows().isEmpty()&&intervals.isEmpty())return 0;
        var latest=new HashMap<LocalDate,Slice>();var latestAt=new HashMap<LocalDate,Instant>();var latestRun=new HashMap<LocalDate,String>();
        for(var interval:intervals)for(var slice:interval.slices())for(LocalDate date=slice.from();!date.isAfter(slice.to());date=date.plusDays(1)){
            Instant old=latestAt.get(date);String oldRun=latestRun.get(date);
            if(old==null||interval.verifiedAt().isAfter(old)||interval.verifiedAt().equals(old)&&interval.runId().compareTo(oldRun)>0){
                latest.put(date,slice);latestAt.put(date,interval.verifiedAt());latestRun.put(date,interval.runId());}}
        if(latest.isEmpty())throw new IllegalStateException("D027 nonempty physical target has no same-target verified source coverage");
        var actual=new HashMap<LocalDate,List<MoneyflowHsgt>>();
        for(var row:target.rows())actual.computeIfAbsent(row.tradeDate(),k->new ArrayList<>()).add(row);
        for(var entry:actual.entrySet())if(!latest.containsKey(entry.getKey()))throw new IllegalStateException("D027 physical row is outside verified window coverage: "+entry.getKey());
        for(var entry:latest.entrySet()){
            var expected=entry.getValue().page().rows().stream().filter(row->row.tradeDate().equals(entry.getKey())).toList();
            var found=actual.getOrDefault(entry.getKey(),List.of());
            if(!MoneyflowHsgtStorage.sameRows(expected,found))throw new IllegalStateException("D027 physical date differs from latest raw-source receipt: "+entry.getKey());
        }
        return target.rows().size();
    }
    private static List<Interval> verifiedIntervals(Path ledgerPath,String targetId,Set<SyncJobDefinition.Mode> modes)throws Exception {
        Objects.requireNonNull(ledgerPath);Objects.requireNonNull(targetId);
        if(!Files.isRegularFile(ledgerPath)||!hasSchema(ledgerPath))return List.of();
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();
        if(Files.exists(evidenceRoot))evidenceRoot=evidenceRoot.toRealPath();
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var json=JobDefinitionJson.mapper();JsonNode expected=json.valueToTree(MoneyflowHsgtSyncJobOwner.DEFINITION);
        var found=new ArrayList<Interval>();String after=null;int scanned=0;
        while(true){var summaries=ledger.history(MoneyflowHsgtSyncJobOwner.DEFINITION.jobId(),after,PAGE);
            for(var summary:summaries){if(++scanned>MAX_HISTORY)throw new IllegalStateException("D027 ledger history exceeds its bound");
                if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())||!targetId.equals(summary.targetId())
                        ||summary.jobVersion()!=MoneyflowHsgtSyncJobOwner.DEFINITION.version())continue;
                var run=ledger.getRun(summary.id());if(!targetId.equals(run.targetId())||!MoneyflowHsgtSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        ||run.jobVersion()!=MoneyflowHsgtSyncJobOwner.DEFINITION.version())continue;
                JsonNode frozen=json.readTree(run.frozenJson());if(!sameDefinition(expected,frozen.path("definition")))continue;
                SyncJobDefinition.Mode mode;try{mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());}catch(RuntimeException bad){throw new IllegalStateException("Invalid D027 frozen mode",bad);}
                if(!modes.contains(mode)||!targetId.equals(frozen.path("parameters").path("targetId").asText()))continue;
                var pub=new ReferencePublicationJournal(ledgerPath,"moneyflow_hsgt").findForRun(summary.id());
                if(pub.isEmpty()||pub.get().state()!=ReferencePublicationJournal.State.VERIFIED)continue;
                LocalDate from=LocalDate.parse(requiredDate(frozen.path("from"))),to=LocalDate.parse(requiredDate(frozen.path("to")));
                long days=ChronoUnit.DAYS.between(from,to)+1;if(days<1||days>MoneyflowHsgtSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(LocalDate.parse(run.logicalDate())))
                    throw new IllegalStateException("D027 frozen run has invalid bounded date range");
                LocalDate anchor=null;
                if(mode==SyncJobDefinition.Mode.INCREMENTAL){anchor=LocalDate.parse(requiredDate(frozen.path("parameters").path("checkpointAnchor")));
                    if(from.isBefore(anchor)||to.isBefore(anchor))throw new IllegalStateException("D027 incremental range precedes frozen anchor");}
                else if(!frozen.path("parameters").path("checkpointAnchor").isMissingNode()||!frozen.path("parameters").path("checkpointBefore").isMissingNode())
                    throw new IllegalStateException("D027 non-incremental run carries incremental checkpoint fields");
                var slices=verifiedSlices(ledger,summary.id(),summary.state(),from,to,evidenceRoot);
                found.add(new Interval(summary.id(),mode,from,to,anchor,Instant.parse(summary.updatedAt()),slices));
            }
            if(summaries.size()<PAGE)break;after=summaries.getLast().id();
        }
        return List.copyOf(found);
    }
    private static List<Slice> verifiedSlices(SyncRunLedger ledger,String runId,SyncRunState runState,
            LocalDate from,LocalDate to,Path evidenceRoot)throws Exception {
        var entries=ledger.entries(runId,null,MAX_ENTRIES);if(entries.size()>=MAX_ENTRIES)throw new IllegalStateException("D027 run exceeds child bound");
        var sliceEntries=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE).toList();
        if(sliceEntries.isEmpty()||sliceEntries.size()>MoneyflowHsgtSyncJobOwner.MAX_SOURCE_SLICES)throw new IllegalStateException("D027 source slice count invalid");
        var unordered=new ArrayList<Slice>();int rows=0;
        for(var entry:sliceEntries){if(entry.state()!=SyncRunState.VERIFIED&&entry.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D027 terminal run contains unverified source slice");
            var fetchedEvents=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();
            if(fetchedEvents.size()!=1)throw new IllegalStateException("D027 source slice must have one FETCHED ledger event");
            JsonNode fetched=JobDefinitionJson.mapper().readTree(fetchedEvents.getFirst().payloadJson());String cursor=fetched.path("cursor").asText("");
            String[] bounds=cursor.split("\\.\\.",-1);if(bounds.length!=2)throw new IllegalStateException("D027 source cursor lacks frozen date range");
            LocalDate sliceFrom=LocalDate.parse(bounds[0],java.time.format.DateTimeFormatter.BASIC_ISO_DATE),sliceTo=LocalDate.parse(bounds[1],java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if(sliceTo.isBefore(sliceFrom)||sliceTo.isAfter(to)||ChronoUnit.DAYS.between(sliceFrom,sliceTo)+1>MoneyflowHsgtSource.MAX_RANGE_DAYS)
                throw new IllegalStateException("D027 verified slice exceeds its bounded date contract");
            String fingerprint=fetched.path("sourceFingerprint").asText(""),raw=fetched.path("responseEvidence").asText("");
            if(!fingerprint.matches("[0-9a-f]{64}")||raw.isBlank())throw new IllegalStateException("D027 FETCHED event lacks source receipt reference");
            Path receipt=Path.of(raw).toAbsolutePath().normalize().toRealPath();if(!receipt.startsWith(evidenceRoot))throw new IllegalStateException("D027 source receipt escapes ledger evidence root");
            var page=MoneyflowHsgtSource.reopen(receipt,fingerprint,sliceFrom,sliceTo);
            if(page.rows().size()!=fetched.path("returnedRows").asInt(-1)||(page.rows().isEmpty()!=(entry.state()==SyncRunState.VERIFIED_EMPTY)))
                throw new IllegalStateException("D027 source slice state/row count differs from immutable receipt");
            rows=Math.addExact(rows,page.rows().size());unordered.add(new Slice(sliceFrom,sliceTo,page));
        }
        var result=unordered.stream().sorted(Comparator.comparing(Slice::from)).toList();LocalDate next=from;
        for(var slice:result){if(!slice.from().equals(next))throw new IllegalStateException("D027 verified slices do not form a contiguous source cover");next=slice.to().plusDays(1);}
        if(!next.equals(to.plusDays(1))||(runState==SyncRunState.VERIFIED_EMPTY)!=(rows==0))throw new IllegalStateException("D027 terminal state does not cover frozen window");
        return List.copyOf(result);
    }
    private static boolean sameDefinition(JsonNode expected,JsonNode actual){try{return JobDefinitionJson.mapper().treeToValue(expected,SyncJobDefinition.class).equals(JobDefinitionJson.mapper().treeToValue(actual,SyncJobDefinition.class));}catch(Exception invalid){return false;}}
    private static String requiredDate(JsonNode node){if(node==null||!node.isTextual())throw new IllegalStateException("D027 frozen date required");return node.asText();}
    private static boolean hasSchema(Path path)throws Exception{
        var names=new java.util.HashSet<String>();try(var connection=DriverManager.getConnection("jdbc:sqlite:"+path.toUri().toASCIIString()+"?mode=ro");var statement=connection.createStatement();var rows=statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")){while(rows.next())names.add(rows.getString(1));}
        Set<String> required=Set.of("ledger_meta","sync_runs","sync_entries","sync_events");if(java.util.Collections.disjoint(names,required))return false;if(!names.containsAll(required))throw new IllegalStateException("Partial D027 ledger schema");return true;
    }
}
