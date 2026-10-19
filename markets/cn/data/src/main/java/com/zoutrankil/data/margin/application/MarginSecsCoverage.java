package com.zoutrankil.data.margin.application;

import com.zoutrankil.data.margin.domain.MarginSecsState;
import com.zoutrankil.data.margin.domain.MarginSecsRows;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D030 checkpoint and existing-row validation backed by immutable raw date receipts. */
public final class MarginSecsCoverage {
    private static final int HISTORY_PAGE=100,MAX_HISTORY=10_000,MAX_ENTRIES=10_000;
    private MarginSecsCoverage(){}
    public record Checkpoint(LocalDate anchor,LocalDate through,Instant latestVerifiedAt){
        public Checkpoint{Objects.requireNonNull(anchor);Objects.requireNonNull(through);Objects.requireNonNull(latestVerifiedAt);if(through.isBefore(anchor))throw new IllegalArgumentException("Invalid D030 checkpoint");}
    }
    private record Slice(LocalDate date,SyncJobRunner.Page<MarginSecs> page){}
    private record Interval(String runId,SyncJobDefinition.Mode mode,SyncRunState state,LocalDate from,LocalDate to,
            LocalDate anchor,Instant verifiedAt,List<Slice> slices){}

    public static Checkpoint checkpoint(Path ledgerPath,String targetId)throws Exception{
        var intervals=verifiedIntervals(ledgerPath,targetId,true);var grouped=new HashMap<LocalDate,List<Interval>>();
        for(var interval:intervals)if(interval.mode()==SyncJobDefinition.Mode.INCREMENTAL&&terminal(interval.state())){
            if(interval.anchor()==null)throw new IllegalStateException("D030 incremental run lacks a frozen anchor");grouped.computeIfAbsent(interval.anchor(),k->new ArrayList<>()).add(interval);}
        var candidates=new ArrayList<Checkpoint>();
        for(var entry:grouped.entrySet()){
            LocalDate anchor=entry.getKey(),through=anchor.minusDays(1);Instant latest=Instant.MIN;boolean began=false;
            var ordered=entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to).thenComparing(Interval::verifiedAt)).toList();
            for(var run:ordered){if(!began){if(!run.from().equals(anchor))continue;began=true;}
                if(run.from().isAfter(through.plusDays(1)))break;if(run.to().isAfter(through))through=run.to();if(run.verifiedAt().isAfter(latest))latest=run.verifiedAt();}
            if(began&&!through.isBefore(anchor))candidates.add(new Checkpoint(anchor,through,latest));
        }
        return candidates.stream().max(Comparator.comparing(Checkpoint::through).thenComparing(Checkpoint::latestVerifiedAt)
                .thenComparing(Checkpoint::anchor,Comparator.reverseOrder())).orElse(null);
    }

    /** Overlay latest verified source by session date; reject all target rows outside that evidence. */
    public static int validateTarget(Path ledgerPath,String targetId,MarginSecsState.Snapshot target)throws Exception{
        var intervals=verifiedIntervals(ledgerPath,targetId,true);
        if(target.rows().isEmpty()&&intervals.isEmpty())return 0;
        var latest=new HashMap<LocalDate,Slice>();var latestAt=new HashMap<LocalDate,Instant>();var latestRun=new HashMap<LocalDate,String>();
        for(var interval:intervals)for(var slice:interval.slices()){
            Instant at=latestAt.get(slice.date());String run=latestRun.get(slice.date());
            if(at==null||interval.verifiedAt().isAfter(at)||interval.verifiedAt().equals(at)&&interval.runId().compareTo(run)>0){
                latest.put(slice.date(),slice);latestAt.put(slice.date(),interval.verifiedAt());latestRun.put(slice.date(),interval.runId());}
        }
        if(latest.isEmpty()&&!target.rows().isEmpty())throw new IllegalStateException("D030 target rows have no same-target receipt-backed session coverage");
        var physical=new HashMap<LocalDate,List<MarginSecs>>();for(var row:target.rows())physical.computeIfAbsent(row.tradeDate(),k->new ArrayList<>()).add(row);
        for(var entry:physical.entrySet())if(!latest.containsKey(entry.getKey()))throw new IllegalStateException("D030 physical row is outside verified SSE session coverage: "+entry.getKey());
        for(var entry:latest.entrySet()){
            var expected=entry.getValue().page().rows();var actual=physical.getOrDefault(entry.getKey(),List.of());
            if(!MarginSecsRows.sameRows(expected,actual))throw new IllegalStateException("D030 target rows differ from latest source receipt for "+entry.getKey());
        }
        return target.rows().size();
    }

    private static List<Interval> verifiedIntervals(Path ledgerPath,String targetId,boolean includePartial)throws Exception{
        Objects.requireNonNull(ledgerPath);Objects.requireNonNull(targetId);if(!Files.isRegularFile(ledgerPath)||!hasSchema(ledgerPath))return List.of();
        Path evidenceRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").normalize();if(Files.exists(evidenceRoot))evidenceRoot=evidenceRoot.toRealPath();
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);var json=JobDefinitionJson.mapper();var found=new ArrayList<Interval>();String after=null;int scanned=0;
        while(true){var summaries=ledger.history(MarginSecsSyncJobOwner.DEFINITION.jobId(),after,HISTORY_PAGE);
            for(var summary:summaries){if(++scanned>MAX_HISTORY)throw new IllegalStateException("D030 ledger history exceeds its bound");
                if(!targetId.equals(summary.targetId())||summary.jobVersion()!=MarginSecsSyncJobOwner.DEFINITION.version()
                        ||!(terminal(summary.state())||includePartial&&summary.state()==SyncRunState.PARTIAL))continue;
                var run=ledger.getRun(summary.id());if(!targetId.equals(run.targetId())||!MarginSecsSyncJobOwner.DEFINITION.jobId().equals(run.jobId())
                        ||run.jobVersion()!=MarginSecsSyncJobOwner.DEFINITION.version())continue;
                JsonNode frozen=json.readTree(run.frozenJson());if(!MarginSecsSyncJobOwner.DEFINITION.equals(json.treeToValue(frozen.path("definition"),SyncJobDefinition.class)))continue;
                SyncJobDefinition.Mode mode;try{mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());}catch(RuntimeException bad){throw new IllegalStateException("Invalid D030 frozen mode",bad);}
                LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
                long days=ChronoUnit.DAYS.between(from,to)+1;if(days<1||days>MarginSecsSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(LocalDate.parse(run.logicalDate())))throw new IllegalStateException("Invalid frozen D030 range");
                var p=frozen.path("parameters");if(!targetId.equals(p.path("targetId").asText())||!targetId.equals(p.path("physicalTargetId").asText()))continue;
                List<String> calendar=split(p.path("calendarDays").asText()),tradeDates=split(p.path("tradeDates").asText());
                var open=MarginSecsTradingDates.validateFrozen(from,to,calendar,p.path("calendarFingerprint").asText(""),tradeDates);
                LocalDate anchor=null;if(mode==SyncJobDefinition.Mode.INCREMENTAL){anchor=LocalDate.parse(p.path("checkpointAnchor").asText());if(from.isBefore(anchor))throw new IllegalStateException("D030 incremental precedes anchor");
                    if(p.path("checkpointBefore").isMissingNode()||p.path("checkpointBefore").isNull()){
                        if(!from.equals(anchor))throw new IllegalStateException("D030 bootstrap run does not begin at its explicit anchor");
                    }else{LocalDate before=LocalDate.parse(p.path("checkpointBefore").asText());LocalDate expected=before.minusDays(MarginSecsSyncJobOwner.REVISION_DAYS-1L);if(expected.isBefore(anchor))expected=anchor;
                        if(before.isBefore(anchor)||before.isAfter(to)||!from.equals(expected))throw new IllegalStateException("D030 incremental receipt has invalid checkpoint overlap");}
                }
                var slices=verifiedSlices(ledger,summary.id(),summary.state(),from,to,p,evidenceRoot);
                if(terminal(summary.state())){if(slices.size()!=tradeDates.size())throw new IllegalStateException("D030 terminal run lacks all frozen session slices");
                    for(int i=0;i<slices.size();i++)if(!slices.get(i).date().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE).equals(tradeDates.get(i)))throw new IllegalStateException("D030 slices do not match frozen session list");}
                found.add(new Interval(summary.id(),mode,summary.state(),from,to,anchor,Instant.parse(summary.updatedAt()),slices));
            }
            if(summaries.size()<HISTORY_PAGE)break;after=summaries.getLast().id();
        }
        return List.copyOf(found);
    }

    private static List<Slice> verifiedSlices(SyncRunLedger ledger,String runId,SyncRunState runState,LocalDate from,LocalDate to,
            JsonNode parameters,Path evidenceRoot)throws Exception{
        var entries=ledger.entries(runId,null,MAX_ENTRIES);if(entries.size()>=MAX_ENTRIES)throw new IllegalStateException("D030 run child bound exceeded");
        var sliceEntries=entries.stream().filter(e->e.kind()==SyncRunLedger.Kind.SLICE&&(e.state()==SyncRunState.VERIFIED||e.state()==SyncRunState.VERIFIED_EMPTY)).toList();
        if(sliceEntries.isEmpty()&&runState!=SyncRunState.PARTIAL)throw new IllegalStateException("D030 terminal run has no verified daily slices");
        var result=new ArrayList<Slice>();var seen=new HashSet<LocalDate>();
        for(var entry:sliceEntries){var events=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(events.size()!=1)throw new IllegalStateException("D030 slice requires one FETCHED event");
            JsonNode event=JobDefinitionJson.mapper().readTree(events.getFirst().payloadJson());String cursor=event.path("cursor").asText("");LocalDate date;
            try{date=LocalDate.parse(cursor,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);}catch(RuntimeException bad){throw new IllegalStateException("D030 source cursor must name one exact trade date",bad);}
            if(date.isBefore(from)||date.isAfter(to)||!seen.add(date))throw new IllegalStateException("D030 slice date outside range or duplicate");
            String fp=event.path("sourceFingerprint").asText(""),raw=event.path("responseEvidence").asText("");if(!fp.matches("[0-9a-f]{64}")||raw.isBlank())throw new IllegalStateException("D030 FETCHED event lacks source receipt reference");
            Path receipt=Path.of(raw).toAbsolutePath().normalize().toRealPath();if(!receipt.startsWith(evidenceRoot))throw new IllegalStateException("D030 source receipt escapes ledger evidence root");
            var page=MarginSecsSource.reopen(receipt,fp,date);if(page.rows().size()!=event.path("returnedRows").asInt(-1)
                    ||(entry.state()==SyncRunState.VERIFIED_EMPTY)!=(page.rows().isEmpty()))throw new IllegalStateException("D030 slice state/rows differ from raw receipt");
            result.add(new Slice(date,page));
        }
        return result.stream().sorted(Comparator.comparing(Slice::date)).toList();
    }
    private static boolean terminal(SyncRunState state){return state==SyncRunState.VERIFIED||state==SyncRunState.VERIFIED_EMPTY;}
    private static List<String> split(String value){return value==null||value.isBlank()?List.of():List.of(value.split(",",-1));}
    private static boolean hasSchema(Path path)throws Exception{
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta","sync_runs","sync_entries","sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D030 ledger schema");
        return true;
    }
}
