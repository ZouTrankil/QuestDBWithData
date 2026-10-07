package com.zoutrankil.data.service;
import com.zoutrankil.data.repository.MoneyflowDcWritePort;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed continuous INCREMENTAL coverage; physical MAX alone never advances the checkpoint. */
public final class MoneyflowDcCoverage {
    private static final int HISTORY_PAGE=100,MAX_HISTORY=10_000;private MoneyflowDcCoverage(){}
    public record Receipt(Path path,String fingerprint,LocalDate date,int rows){public Receipt{Objects.requireNonNull(path);Objects.requireNonNull(fingerprint);Objects.requireNonNull(date);if(rows<0)throw new IllegalArgumentException("Nonnegative moneyflow_dc receipt row count required");}}
    public record Coverage(LocalDate anchor,LocalDate through,Map<LocalDate,Receipt> receipts,long expectedRows,LocalDate firstRowDate,LocalDate lastRowDate,Instant verifiedAt){
        public Coverage{Objects.requireNonNull(anchor);Objects.requireNonNull(through);receipts=Map.copyOf(receipts);Objects.requireNonNull(verifiedAt);if(through.isBefore(anchor)||expectedRows<0||(firstRowDate==null)!=(lastRowDate==null)||expectedRows==0!=(firstRowDate==null))throw new IllegalArgumentException("Invalid moneyflow_dc checkpoint");}}
    private record Interval(String run,SyncJobDefinition.Mode mode,LocalDate anchor,LocalDate from,LocalDate to,Instant at,Map<LocalDate,Receipt> receipts){}
    private record Timed(Instant at,String run,Receipt receipt){}
    public static Optional<Coverage> checkpoint(Path ledgerPath,String targetId,MoneyflowDcTradingDates calendar)throws Exception{
        if(!Files.isRegularFile(ledgerPath)||!hasLedger(ledgerPath))return Optional.empty();var ledger=SyncRunLedger.openReadOnly(ledgerPath);var intervals=new ArrayList<Interval>();String after=null;int seen=0;
        while(true){var runs=ledger.history("data.moneyflow_dc",after,HISTORY_PAGE);for(var summary:runs){if(++seen>MAX_HISTORY)throw new IllegalStateException("moneyflow_dc checkpoint history exceeds 10000 runs");if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())||!targetId.equals(summary.targetId())||summary.jobVersion()!=MoneyflowDcSyncJobOwner.DEFINITION.version())continue;
                var run=ledger.getRun(summary.id());if(!run.jobId().equals("data.moneyflow_dc")||!run.targetId().equals(targetId)||run.jobVersion()!=MoneyflowDcSyncJobOwner.DEFINITION.version())continue;JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());SyncJobDefinition def=JobDefinitionJson.mapper().treeToValue(frozen.path("definition"),SyncJobDefinition.class);
                if(!def.equals(MoneyflowDcSyncJobOwner.DEFINITION)||!Set.of("INCREMENTAL","BACKFILL").contains(frozen.path("mode").asText()))continue;var mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());JsonNode p=frozen.path("parameters");if(!targetId.equals(p.path("targetId").asText())||!p.path("checkpointAnchor").isTextual())throw new IllegalStateException("Verified D026 run has malformed frozen identity/anchor");
                LocalDate anchor=LocalDate.parse(p.path("checkpointAnchor").asText()),from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());long days=ChronoUnit.DAYS.between(from,to)+1;if(from.isBefore(anchor)||to.isBefore(anchor)||days<1||days>MoneyflowDcSyncJobOwner.MAX_WINDOW_DAYS)throw new IllegalStateException("Invalid verified D026 date interval");
                List<LocalDate> dates=calendar.read(from,to);if(!encodeDates(dates).equals(p.path("trade_dates").asText()))throw new IllegalStateException("Verified D026 calendar sequence differs from exchange calendar");var receipts=readReceipts(ledger,ledgerPath,summary.id(),dates,summary.state());intervals.add(new Interval(summary.id(),mode,anchor,from,to,Instant.parse(summary.updatedAt()),receipts));}
            if(runs.size()<HISTORY_PAGE)break;after=runs.getLast().id();}
        return merge(intervals);
    }
    public static void validateExistingTarget(Coverage c,MoneyflowDcWritePort.TargetRange actual,MoneyflowDcWritePort writer)throws Exception{var actualDates=writer.readExistingDates();if(c==null){if(!actual.empty()||!actualDates.isEmpty())throw new IllegalStateException("Nonempty D026 target has no same-identity receipt-backed checkpoint");return;}
        var explained=c.receipts().values().stream().filter(r->r.rows()>0).map(Receipt::date).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(actual.rows()!=c.expectedRows()||!Objects.equals(actual.min(),c.firstRowDate())||!Objects.equals(actual.max(),c.lastRowDate())||!explained.equals(Set.copyOf(actualDates)))throw new IllegalStateException("D026 physical range/count/date inventory differs from receipt-backed coverage; reconcile explicitly");
        for(var entry:c.receipts().entrySet()){var receipt=entry.getValue();var expected=MoneyflowDcSource.reopen(receipt.path(),receipt.fingerprint(),entry.getKey()).rows();var observed=writer.readDate(entry.getKey());if(!sameRows(expected,observed))throw new IllegalStateException("D026 physical values differ from the latest verified source receipt on "+entry.getKey());}}
    private static boolean sameRows(List<MoneyflowDc> expected,List<MoneyflowDc> actual){if(expected.size()!=actual.size())return false;var left=new HashMap<MoneyflowDcKey,byte[]>();var right=new HashMap<MoneyflowDcKey,byte[]>();for(var row:expected)if(left.putIfAbsent(row.key(),MoneyflowDcWritePort.CODEC.canonicalBytes(row))!=null)return false;for(var row:actual)if(right.putIfAbsent(row.key(),MoneyflowDcWritePort.CODEC.canonicalBytes(row))!=null)return false;return left.keySet().equals(right.keySet())&&left.keySet().stream().allMatch(key->Arrays.equals(left.get(key),right.get(key)));}
    private static Map<LocalDate,Receipt> readReceipts(SyncRunLedger ledger,Path ledgerPath,String runId,List<LocalDate> dates,SyncRunState runState)throws Exception{
        var expected=new HashSet<>(dates);var found=new HashMap<LocalDate,Receipt>();String after=null;int scanned=0,total=0;
        while(true){var entries=ledger.entries(runId,after,1000);for(var entry:entries){if(++scanned>1000)throw new IllegalStateException("D026 run exceeds bounded ledger entries");if(entry.kind()!=SyncRunLedger.Kind.SLICE)continue;if(entry.state()!=SyncRunState.VERIFIED&&entry.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("Verified D026 run contains unfinished slice");
                var events=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(events.size()!=1)throw new IllegalStateException("D026 slice must have one immutable source fetch event");JsonNode event=JobDefinitionJson.mapper().readTree(events.getFirst().payloadJson());String cursor=event.path("cursor").asText();if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D026 source cursor must be trade date YYYYMMDD");LocalDate date=LocalDate.parse(cursor,DateTimeFormatter.BASIC_ISO_DATE);if(!expected.contains(date))throw new IllegalStateException("D026 source receipt date outside frozen open-date set");
                Path path=Path.of(event.path("responseEvidence").asText()).toAbsolutePath().normalize();Path root=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();if(!path.startsWith(root)||!Files.isRegularFile(path)||!path.toRealPath().startsWith(root)||Files.size(path)>MoneyflowDcSource.MAX_EVIDENCE_BYTES)throw new IllegalStateException("D026 source receipt absent/oversized/outside frozen run directory");
                String fingerprint=event.path("sourceFingerprint").asText();var page=MoneyflowDcSource.reopen(path,fingerprint,date);if(page.rows().size()!=event.path("returnedRows").asInt(-1)||(page.rows().isEmpty())!=(entry.state()==SyncRunState.VERIFIED_EMPTY)||found.putIfAbsent(date,new Receipt(path,fingerprint,date,page.rows().size()))!=null)throw new IllegalStateException("D026 source receipt row/state/date mismatch");total=Math.addExact(total,page.rows().size());}
            if(entries.size()<1000)break;after=entries.getLast().id();}
        if(!found.keySet().equals(expected)||((runState==SyncRunState.VERIFIED_EMPTY)!=(total==0)))throw new IllegalStateException("D026 run lacks exact verified daily source receipts");return Map.copyOf(found);
    }
    private static Optional<Coverage> merge(List<Interval> intervals){if(intervals.isEmpty())return Optional.empty();var grouped=new HashMap<LocalDate,List<Interval>>();intervals.forEach(i->grouped.computeIfAbsent(i.anchor(),k->new ArrayList<>()).add(i));var candidates=new ArrayList<Coverage>();
        for(var group:grouped.entrySet()){LocalDate anchor=group.getKey(),through=anchor.minusDays(1);boolean started=false;Instant latest=Instant.MIN;var incremental=group.getValue().stream().filter(i->i.mode()==SyncJobDefinition.Mode.INCREMENTAL).sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to).thenComparing(Interval::at).thenComparing(Interval::run)).toList();
            for(var i:incremental){if(!started){if(!i.from().equals(anchor))continue;started=true;}if(i.from().isAfter(through.plusDays(1)))break;if(i.to().isAfter(through))through=i.to();if(i.at().isAfter(latest))latest=i.at();}
            if(started&&!through.isBefore(anchor)){LocalDate selectedThrough=through;var selectedTimed=new HashMap<LocalDate,Timed>();for(var i:group.getValue())if(!i.from().isAfter(selectedThrough)){if(i.at().isAfter(latest))latest=i.at();i.receipts().forEach((date,receipt)->{if(date.isBefore(anchor)||date.isAfter(selectedThrough))return;Timed old=selectedTimed.get(date);if(old==null||i.at().isAfter(old.at())||i.at().equals(old.at())&&i.run().compareTo(old.run())>0)selectedTimed.put(date,new Timed(i.at(),i.run(),receipt));});}
                long count=0;LocalDate first=null,last=null;var selected=new HashMap<LocalDate,Receipt>();for(var e:selectedTimed.entrySet()){Receipt r=e.getValue().receipt();selected.put(e.getKey(),r);count=Math.addExact(count,r.rows());if(r.rows()>0){if(first==null||r.date().isBefore(first))first=r.date();if(last==null||r.date().isAfter(last))last=r.date();}}candidates.add(new Coverage(anchor,through,selected,count,first,last,latest));}}
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::verifiedAt).thenComparing(Coverage::anchor,Comparator.reverseOrder()));}
    public static String encodeDates(List<LocalDate> dates){if(dates.size()>5||dates.stream().distinct().count()!=dates.size()||!dates.equals(dates.stream().sorted().toList()))throw new IllegalArgumentException("D026 requires unique ascending bounded dates");return dates.isEmpty()?"NONE":String.join(",",dates.stream().map(d->d.format(DateTimeFormatter.BASIC_ISO_DATE)).toList());}
    private static boolean hasLedger(Path path)throws Exception{
        var names = SqliteLedgerSchema.tableNames(path.toAbsolutePath().normalize());
        var required = Set.of("ledger_meta","sync_runs","sync_entries","sync_events");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new IllegalStateException("Partial D026 ledger schema");
        return true;
    }
}
