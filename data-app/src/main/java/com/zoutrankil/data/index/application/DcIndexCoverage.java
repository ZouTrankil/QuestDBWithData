package com.zoutrankil.data.index.application;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;


import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed contiguous date coverage; checkpoint never trusts MAX(trade_date) alone. */
public final class DcIndexCoverage {
    private static final int HISTORY_PAGE=100,MAX_HISTORY=10_000;
    public record Receipt(Path path,String fingerprint,LocalDate date){}
    public record Coverage(LocalDate anchor,LocalDate through,Map<LocalDate,Receipt> receipts,Instant verifiedAt){
        public Coverage{Objects.requireNonNull(anchor);Objects.requireNonNull(through);receipts=Map.copyOf(receipts);Objects.requireNonNull(verifiedAt);
            if(through.isBefore(anchor))throw new IllegalArgumentException("Invalid D023 checkpoint interval");}}
    private record Interval(String runId,SyncJobDefinition.Mode mode,LocalDate anchor,LocalDate checkpointBefore,
            LocalDate from,LocalDate to,Instant at,Map<LocalDate,Receipt> receipts){}
    private record Timed(Instant at,String runId,Receipt receipt){}
    private DcIndexCoverage(){}

    static boolean hasLedger(Path path)throws Exception{
        var names = SqliteLedgerSchema.tableNames(path.toAbsolutePath().normalize());
        var required = Set.of("ledger_meta","sync_runs","sync_entries","sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D023 sync ledger schema");
        return true;
    }
    public static Optional<Coverage> checkpoint(Path path,String targetId,DcIndexTradingDates calendar)throws Exception{
        Objects.requireNonNull(path);Objects.requireNonNull(targetId);Objects.requireNonNull(calendar);if(!Files.isRegularFile(path)||!hasLedger(path))return Optional.empty();
        var ledger=SyncRunLedger.openReadOnly(path);var json=JobDefinitionJson.mapper();var intervals=new ArrayList<Interval>();String after=null;int seen=0;
        while(true){var runs=ledger.history("data.dc_index",after,HISTORY_PAGE);for(var summary:runs){if(++seen>MAX_HISTORY)throw new IllegalStateException("D023 checkpoint history exceeds bound");
                if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())||!targetId.equals(summary.targetId())||summary.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version())continue;
                var run=ledger.getRun(summary.id());if(!"data.dc_index".equals(run.jobId())||!targetId.equals(run.targetId())||run.jobVersion()!=DcIndexSyncJobOwner.DEFINITION.version())continue;
                JsonNode frozen=json.readTree(run.frozenJson());SyncJobDefinition definition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class);
                if(!DcIndexSyncJobOwner.DEFINITION.equals(definition))continue;
                SyncJobDefinition.Mode mode;try{mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());}catch(IllegalArgumentException invalid){continue;}
                if(mode!=SyncJobDefinition.Mode.INCREMENTAL&&mode!=SyncJobDefinition.Mode.BACKFILL)continue;
                JsonNode p=frozen.path("parameters");if(!targetId.equals(p.path("targetId").asText()))continue;
                if(!p.path("checkpointAnchor").isTextual())throw new IllegalStateException("Verified D023 run lacks its frozen checkpoint anchor");
                LocalDate anchor=LocalDate.parse(p.path("checkpointAnchor").asText()),from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());
                LocalDate checkpointBefore=p.path("checkpointBefore").isTextual()?LocalDate.parse(p.path("checkpointBefore").asText()):null;
                long days=ChronoUnit.DAYS.between(from,to)+1;if(from.isBefore(anchor)||to.isBefore(anchor)||days<1||days>DcIndexSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalStateException("Invalid verified D023 interval");
                if(mode==SyncJobDefinition.Mode.INCREMENTAL&&checkpointBefore!=null&&(!from.equals(clampOverlap(checkpointBefore,anchor))||checkpointBefore.isBefore(anchor)||checkpointBefore.isAfter(to)))
                    throw new IllegalStateException("Invalid verified D023 incremental checkpoint overlap");
                if(mode==SyncJobDefinition.Mode.BACKFILL&&(checkpointBefore==null||checkpointBefore.isBefore(anchor)||from.isBefore(anchor)||to.isAfter(checkpointBefore)))
                    throw new IllegalStateException("Invalid verified D023 backfill overlay bounds");
                var dates=calendar.read(from,to);if(!DcIndexSyncAdapter.encodeDates(dates).equals(p.path("trade_dates").asText()))throw new IllegalStateException("D023 frozen date sequence differs from D001 calendar");
                var receipts=readReceipts(ledger,path,summary.id(),dates,summary.state());intervals.add(new Interval(summary.id(),mode,anchor,checkpointBefore,from,to,Instant.parse(summary.updatedAt()),receipts));}
            if(runs.size()<HISTORY_PAGE)break;after=runs.getLast().id();}
        return merge(intervals);
    }
    private static Map<LocalDate,Receipt> readReceipts(SyncRunLedger ledger,Path ledgerPath,String runId,List<LocalDate> dates,SyncRunState runState)throws Exception{
        var expected=new HashSet<>(dates);var found=new HashMap<LocalDate,Receipt>();String after=null;int scanned=0,rows=0;
        while(true){var entries=ledger.entries(runId,after,1000);for(var entry:entries){if(++scanned>1000)throw new IllegalStateException("D023 run has too many daily slices");if(entry.kind()!=SyncRunLedger.Kind.SLICE)continue;
                if(entry.state()!=SyncRunState.VERIFIED&&entry.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("Verified D023 run contains incomplete slice");
                var events=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(events.size()!=1)throw new IllegalStateException("D023 slice lacks one FETCHED event");
                JsonNode fetch=JobDefinitionJson.mapper().readTree(events.getFirst().payloadJson());String cursor=fetch.path("cursor").asText();
                if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D023 FETCHED cursor must be basic trade_date");LocalDate date=LocalDate.parse(cursor,DateTimeFormatter.BASIC_ISO_DATE);
                if(!expected.contains(date))throw new IllegalStateException("D023 source receipt date outside frozen open-date set");
                Path evidence=Path.of(fetch.path("responseEvidence").asText()).toAbsolutePath().normalize();String hash=fetch.path("sourceFingerprint").asText();
                Path root=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").toRealPath();if(!evidence.startsWith(root)||!Files.isRegularFile(evidence)||!evidence.toRealPath().startsWith(root)||Files.size(evidence)>DcIndexSource.MAX_EVIDENCE_BYTES)throw new IllegalStateException("D023 receipt escapes bounded evidence root");
                var page=DcIndexSource.reopen(evidence,hash,date);if(page.rows().isEmpty()!=(entry.state()==SyncRunState.VERIFIED_EMPTY))throw new IllegalStateException("D023 slice empty status differs from immutable source receipt");
                rows=Math.addExact(rows,page.rows().size());if(found.putIfAbsent(date,new Receipt(evidence,hash,date))!=null)throw new IllegalStateException("Duplicate D023 trade-date receipt");}
            if(entries.size()<1000)break;after=entries.getLast().id();}
        if(!found.keySet().equals(expected))throw new IllegalStateException("D023 verified run lacks exact daily source receipts");
        if((runState==SyncRunState.VERIFIED_EMPTY)!=(rows==0))throw new IllegalStateException("D023 run state disagrees with all daily source receipts");return Map.copyOf(found);
    }
    private static Optional<Coverage> merge(List<Interval> intervals){if(intervals.isEmpty())return Optional.empty();var groups=new HashMap<LocalDate,List<Interval>>();intervals.forEach(i->groups.computeIfAbsent(i.anchor(),k->new ArrayList<>()).add(i));var out=new ArrayList<Coverage>();
        for(var group:groups.entrySet()){LocalDate anchor=group.getKey();var incrementals=group.getValue().stream().filter(i->i.mode()==SyncJobDefinition.Mode.INCREMENTAL).toList();
            LocalDate through=contiguousThrough(incrementals,anchor,Instant.MAX);if(through.isBefore(anchor))continue;Instant newest=Instant.MIN;var chosen=new HashMap<LocalDate,Timed>();
            for(var i:incrementals){if(i.from().isAfter(through)||i.to().isBefore(anchor))continue;if(i.at().isAfter(newest))newest=i.at();putReceipts(chosen,i,anchor,through);}
            for(var overlay:group.getValue()){if(overlay.mode()!=SyncJobDefinition.Mode.BACKFILL||overlay.checkpointBefore()==null
                        ||overlay.from().isBefore(anchor)||overlay.to().isAfter(overlay.checkpointBefore())||overlay.checkpointBefore().isAfter(through))continue;
                LocalDate priorThrough=contiguousThrough(incrementals,anchor,overlay.at());if(priorThrough.isBefore(overlay.checkpointBefore()))continue;
                if(overlay.at().isAfter(newest))newest=overlay.at();putReceipts(chosen,overlay,anchor,through);}
            var receipts=new HashMap<LocalDate,Receipt>();chosen.forEach((d,t)->receipts.put(d,t.receipt()));out.add(new Coverage(anchor,through,receipts,newest));}
        return out.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::verifiedAt).thenComparing(Coverage::anchor,Comparator.reverseOrder()));}
    private static LocalDate contiguousThrough(List<Interval> intervals,LocalDate anchor,Instant cutoff){LocalDate through=anchor.minusDays(1);boolean started=false;
        var ordered=intervals.stream().filter(i->!i.at().isAfter(cutoff)).sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to).thenComparing(Interval::at).thenComparing(Interval::runId)).toList();
        for(var i:ordered){if(!started){if(!i.from().equals(anchor))continue;started=true;}if(i.from().isAfter(through.plusDays(1)))break;if(i.to().isAfter(through))through=i.to();}
        return through;}
    private static void putReceipts(Map<LocalDate,Timed> chosen,Interval interval,LocalDate anchor,LocalDate through){
        interval.receipts().forEach((date,receipt)->{if(date.isBefore(anchor)||date.isAfter(through))return;Timed old=chosen.get(date);
            if(old==null||interval.at().isAfter(old.at())||interval.at().equals(old.at())&&interval.runId().compareTo(old.runId())>0)
                chosen.put(date,new Timed(interval.at(),interval.runId(),receipt));});}
    private static LocalDate clampOverlap(LocalDate before,LocalDate anchor){LocalDate overlap=before.minusDays(DcIndexSyncJobOwner.REVISION_DAYS);return overlap.isBefore(anchor)?anchor:overlap;}
    public static void validateExistingTarget(Coverage coverage,DcIndexTradingDates calendar,DcIndexWriteSession port)throws Exception{
        var actualDates=new HashSet<>(port.readExistingDates());if(coverage==null){if(!actualDates.isEmpty())throw new IllegalStateException("D023 target has rows but no same-logical-target receipt-backed checkpoint");return;}
        if(ChronoUnit.DAYS.between(coverage.anchor(),coverage.through())+1>10_000)throw new IllegalStateException("D023 checkpoint range exceeds reconciliation limit");
        var expectedDates=new HashSet<LocalDate>();LocalDate from=coverage.anchor();while(!from.isAfter(coverage.through())){LocalDate to=from.plusDays(365);if(to.isAfter(coverage.through()))to=coverage.through();expectedDates.addAll(calendar.read(from,to));from=to.plusDays(1);}
        if(!coverage.receipts().keySet().equals(expectedDates))throw new IllegalStateException("D023 checkpoint lacks a complete exchange-calendar receipt chain");var nonempty=new HashSet<LocalDate>();
        for(var e:coverage.receipts().entrySet()){var expected=DcIndexSource.reopen(e.getValue().path(),e.getValue().fingerprint(),e.getKey()).rows();var actual=port.readDate(e.getKey());if(!same(expected,actual))throw new IllegalStateException("D023 target differs from latest source receipt at "+e.getKey());if(!expected.isEmpty())nonempty.add(e.getKey());}
        if(!actualDates.equals(nonempty))throw new IllegalStateException("D023 target has unexplained or missing physical dates");
    }
    static boolean same(List<DcIndex> a,List<DcIndex> b)throws Exception{return Arrays.equals(DcIndexRows.canonical(a),DcIndexRows.canonical(b));}
}
