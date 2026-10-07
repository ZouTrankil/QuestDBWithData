package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.MarginDetailWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Receipt-backed continuous INCREMENTAL coverage with latest verified BACKFILL values overlaid. */
public final class MarginDetailCoverage {
    private static final int HISTORY_PAGE=100,MAX_HISTORY=10_000,MAX_ENTRIES=1_000;
    private MarginDetailCoverage(){}
    public record Receipt(Path path,String fingerprint,LocalDate date,int rows){public Receipt{Objects.requireNonNull(path);Objects.requireNonNull(fingerprint);Objects.requireNonNull(date);if(rows<1)throw new IllegalArgumentException("D029 verified source partition must contain rows");}}
    public record Coverage(LocalDate anchor,LocalDate through,Map<LocalDate,Receipt> receipts,long expectedRows,LocalDate firstRowDate,LocalDate lastRowDate,Instant verifiedAt){
        public Coverage{Objects.requireNonNull(anchor);Objects.requireNonNull(through);receipts=Map.copyOf(receipts);Objects.requireNonNull(verifiedAt);if(through.isBefore(anchor)||expectedRows<1||firstRowDate==null||lastRowDate==null||firstRowDate.isAfter(lastRowDate))throw new IllegalArgumentException("Invalid D029 checkpoint coverage");}}
    private record Interval(String runId,SyncJobDefinition.Mode mode,LocalDate from,LocalDate to,LocalDate anchor,LocalDate before,Instant at,Map<LocalDate,Receipt> receipts){}
    private record Timed(Instant at,String runId,Receipt receipt){}

    public static Optional<Coverage> checkpoint(Path ledgerPath,String targetId,MarginDetailTradingDates calendar)throws Exception{
        var intervals=verifiedIntervals(ledgerPath,targetId,calendar);var grouped=new HashMap<LocalDate,List<Interval>>();
        intervals.stream().filter(i->i.mode()==SyncJobDefinition.Mode.INCREMENTAL).forEach(i->grouped.computeIfAbsent(i.anchor(),k->new ArrayList<>()).add(i));var candidates=new ArrayList<Coverage>();
        for(var group:grouped.entrySet()){LocalDate anchor=group.getKey(),through=anchor.minusDays(1);boolean started=false;Instant latest=Instant.MIN;
            var ordered=group.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to).thenComparing(Interval::at).thenComparing(Interval::runId)).toList();
            for(var interval:ordered){if(!started){if(!interval.from().equals(anchor))continue;started=true;}if(interval.from().isAfter(through.plusDays(1)))break;if(interval.to().isAfter(through))through=interval.to();if(interval.at().isAfter(latest))latest=interval.at();}
            if(!started||through.isBefore(anchor))continue;LocalDate selectedThrough=through;var newest=new HashMap<LocalDate,Timed>();
            for(var interval:intervals){if(!Objects.equals(interval.anchor(),anchor)||interval.from().isBefore(anchor)||interval.to().isAfter(selectedThrough))continue;
                if(interval.at().isAfter(latest))latest=interval.at();for(var entry:interval.receipts().entrySet()){LocalDate date=entry.getKey();if(date.isBefore(anchor)||date.isAfter(selectedThrough))continue;Timed old=newest.get(date);
                    if(old==null||interval.at().isAfter(old.at())||interval.at().equals(old.at())&&interval.runId().compareTo(old.runId())>0)newest.put(date,new Timed(interval.at(),interval.runId(),entry.getValue()));}}
            var selected=new HashMap<LocalDate,Receipt>();long count=0;LocalDate first=null,last=null;for(var entry:newest.entrySet()){Receipt receipt=entry.getValue().receipt();selected.put(entry.getKey(),receipt);count=Math.addExact(count,receipt.rows());
                if(first==null||receipt.date().isBefore(first))first=receipt.date();if(last==null||receipt.date().isAfter(last))last=receipt.date();}
            if(first!=null)candidates.add(new Coverage(anchor,through,selected,count,first,last,latest));
        }
        return candidates.stream().max(Comparator.comparing(Coverage::through).thenComparing(Coverage::verifiedAt).thenComparing(Coverage::anchor,Comparator.reverseOrder()));
    }

    public static void validateExistingTarget(Coverage coverage,MarginDetailWritePort.TargetRange physical,MarginDetailWritePort writer)throws Exception{
        List<LocalDate> dates=writer.readExistingDates();if(coverage==null){if(!physical.empty()||!dates.isEmpty())throw new IllegalStateException("Nonempty D029 target has no same-identity receipt-backed incremental checkpoint");return;}
        var expectedDates=coverage.receipts().values().stream().filter(r->r.rows()>0).map(Receipt::date).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(physical.rows()!=coverage.expectedRows()||!Objects.equals(physical.min(),coverage.firstRowDate())||!Objects.equals(physical.max(),coverage.lastRowDate())||!expectedDates.equals(Set.copyOf(dates)))
            throw new IllegalStateException("D029 physical count/range/date inventory differs from its receipt-backed coverage");
        for(var entry:coverage.receipts().entrySet()){var receipt=entry.getValue();var expected=MarginDetailSource.reopen(receipt.path(),receipt.fingerprint(),entry.getKey()).rows();var actual=writer.readDate(entry.getKey());if(!sameRows(expected,actual))throw new IllegalStateException("D029 physical values differ from latest verified receipt on "+entry.getKey());}
    }

    private static List<Interval> verifiedIntervals(Path ledgerPath,String targetId,MarginDetailTradingDates calendar)throws Exception{
        Objects.requireNonNull(ledgerPath);Objects.requireNonNull(targetId);if(!Files.isRegularFile(ledgerPath)||!hasLedger(ledgerPath))return List.of();
        var ledger=SyncRunLedger.openReadOnly(ledgerPath);JsonNode expected=JobDefinitionJson.mapper().valueToTree(MarginDetailSyncJobOwner.DEFINITION);var found=new ArrayList<Interval>();String after=null;int scanned=0;
        while(true){var summaries=ledger.history(MarginDetailSyncJobOwner.DEFINITION.jobId(),after,HISTORY_PAGE);for(var summary:summaries){if(++scanned>MAX_HISTORY)throw new IllegalStateException("D029 checkpoint history exceeds 10000 runs");
                if(summary.state()!=SyncRunState.VERIFIED||!targetId.equals(summary.targetId())||summary.jobVersion()!=MarginDetailSyncJobOwner.DEFINITION.version())continue;
                var run=ledger.getRun(summary.id());if(!run.jobId().equals(MarginDetailSyncJobOwner.DEFINITION.jobId())||!run.targetId().equals(targetId)||run.jobVersion()!=MarginDetailSyncJobOwner.DEFINITION.version())continue;
                JsonNode frozen=JobDefinitionJson.mapper().readTree(run.frozenJson());if(!sameDefinition(expected,frozen.path("definition")))continue;SyncJobDefinition.Mode mode;
                try{mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());}catch(RuntimeException invalid){continue;}if(!Set.of(SyncJobDefinition.Mode.INCREMENTAL,SyncJobDefinition.Mode.BACKFILL).contains(mode))continue;
                JsonNode params=frozen.path("parameters");if(!targetId.equals(params.path("targetId").asText()))throw new IllegalStateException("Verified D029 run target differs from frozen request");
                LocalDate from=LocalDate.parse(frozen.path("from").asText()),to=LocalDate.parse(frozen.path("to").asText());long days=ChronoUnit.DAYS.between(from,to)+1;if(days<1||days>MarginDetailSyncJobOwner.MAX_WINDOW_DAYS||to.isAfter(LocalDate.parse(frozen.path("logicalDate").asText())))throw new IllegalStateException("Verified D029 run exceeds its finite frozen date interval");
                LocalDate anchor=requiredDate(params,"checkpointAnchor"),before=optionalDate(params,"checkpointBefore");if(from.isBefore(anchor))throw new IllegalStateException("Verified D029 interval precedes frozen checkpoint anchor");
                if(mode==SyncJobDefinition.Mode.INCREMENTAL&&before!=null&&!from.equals(max(anchor,before.minusDays(MarginDetailSyncJobOwner.REVISION_DAYS))))throw new IllegalStateException("Verified D029 incremental overlap differs from its frozen checkpoint");
                boolean repair=mode==SyncJobDefinition.Mode.BACKFILL&&before==null;
                if(mode==SyncJobDefinition.Mode.BACKFILL&&(repair?!from.equals(anchor):to.isAfter(before)))throw new IllegalStateException("Verified D029 BACKFILL lacks its repair anchor or bounded prior checkpoint");
                List<LocalDate> dates=calendar.read(from,to);if(!encodeDates(dates).equals(params.path("trade_dates").asText()))throw new IllegalStateException("D029 frozen trade-date list differs from complete SSE calendar");
                var receipts=readReceipts(ledger,ledgerPath,summary.id(),dates);verifyManifest(ledgerPath,summary.id(),targetId,mode,from,to,dates,receipts);
                // Legacy-table repairs prove only their bounded source interval; they do not bootstrap global incremental coverage.
                if(!repair)found.add(new Interval(summary.id(),mode,from,to,anchor,before,Instant.parse(summary.updatedAt()),receipts));}
            if(summaries.size()<HISTORY_PAGE)break;after=summaries.getLast().id();}
        return List.copyOf(found);
    }

    private static Map<LocalDate,Receipt> readReceipts(SyncRunLedger ledger,Path ledgerPath,String runId,List<LocalDate> dates)throws Exception{
        var expectedDates=new HashSet<>(dates);var found=new HashMap<LocalDate,Receipt>();String after=null;int entriesSeen=0,total=0;
        while(true){var entries=ledger.entries(runId,after,MAX_ENTRIES);for(var entry:entries){if(++entriesSeen>MAX_ENTRIES)throw new IllegalStateException("D029 run ledger entry bound exceeded");if(entry.kind()!=SyncRunLedger.Kind.SLICE)continue;
                if(entry.state()!=SyncRunState.VERIFIED)throw new IllegalStateException("Verified D029 run contains an unfinished/empty source slice");var fetched=ledger.events(entry.id(),-1,100).stream().filter(e->e.state()==SyncRunState.FETCHED).toList();if(fetched.size()!=1)throw new IllegalStateException("D029 slice must have one immutable FETCHED event");
                JsonNode event=JobDefinitionJson.mapper().readTree(fetched.getFirst().payloadJson());String cursor=event.path("cursor").asText();if(!cursor.matches("[0-9]{8}"))throw new IllegalStateException("D029 source cursor must be YYYYMMDD");LocalDate date=LocalDate.parse(cursor,DateTimeFormatter.BASIC_ISO_DATE);
                if(!expectedDates.contains(date))throw new IllegalStateException("D029 receipt date falls outside frozen trading sessions");String fingerprint=event.path("sourceFingerprint").asText(),raw=event.path("responseEvidence").asText();Path path=Path.of(raw).toAbsolutePath().normalize();
                Path runRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath();if(!path.startsWith(runRoot)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||!path.toRealPath().startsWith(runRoot)||Files.size(path)>MarginDetailSource.MAX_EVIDENCE_BYTES)throw new IllegalStateException("D029 raw source receipt is absent, oversized or outside its run evidence folder");
                var page=MarginDetailSource.reopen(path,fingerprint,date);if(page.rows().size()!=event.path("returnedRows").asInt(-1)||found.putIfAbsent(date,new Receipt(path,fingerprint,date,page.rows().size()))!=null)throw new IllegalStateException("D029 source event row/date/duplicate receipt mismatch");total=Math.addExact(total,page.rows().size());}
            if(entries.size()<MAX_ENTRIES)break;after=entries.getLast().id();}
        if(!found.keySet().equals(expectedDates)||total<1||total>MarginDetailSyncJobOwner.DEFINITION.budget().maxRows())throw new IllegalStateException("D029 run lacks exact nonempty source receipts for all frozen open dates");return Map.copyOf(found);
    }

    private static void verifyManifest(Path ledgerPath,String runId,String targetId,SyncJobDefinition.Mode mode,LocalDate from,LocalDate to,List<LocalDate> dates,Map<LocalDate,Receipt> receipts)throws Exception{
        Path runRoot=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath(),manifest=runRoot.resolve("complete-window.json");
        if(Files.isSymbolicLink(manifest)||!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)||Files.size(manifest)>1024*1024)throw new IllegalStateException("D029 completion manifest is absent/oversized");
        JsonNode proof=JobDefinitionJson.mapper().readTree(Files.readAllBytes(manifest));if(!"margin_detail".equals(proof.path("dataset").asText())||!"margin_detail".equals(proof.path("endpoint").asText())
                ||!targetId.equals(proof.path("targetId").asText())||!mode.name().equals(proof.path("mode").asText())||!from.toString().equals(proof.path("fromInclusive").asText())
                ||!to.toString().equals(proof.path("toInclusive").asText())||!proof.path("sourceComplete").asBoolean(false)||!proof.path("complete").asBoolean(false)
                ||!JobDefinitionJson.mapper().valueToTree(dates).equals(proof.path("tradeDates"))||proof.path("sourceReceipts").size()!=dates.size())throw new IllegalStateException("D029 completion manifest differs from frozen run/date sequence");
        long total=0;for(int i=0;i<dates.size();i++){var date=dates.get(i);Receipt receipt=receipts.get(date);JsonNode item=proof.path("sourceReceipts").get(i);if(!date.toString().equals(item.path("tradeDate").asText())
                    ||receipt.rows()!=item.path("rows").asInt(-1)||!receipt.fingerprint().equals(item.path("sourceFingerprint").asText())||!receipt.path().toString().equals(Path.of(item.path("responseEvidence").asText()).toAbsolutePath().normalize().toString()))
                    throw new IllegalStateException("D029 manifest receipt list differs from immutable FETCHED events");total+=receipt.rows();}
        if(total!=proof.path("sourceRows").asLong(-1)||total!=proof.path("returnedRows").asLong(-1))throw new IllegalStateException("D029 manifest totals differ from exact source receipts");
    }

    private static boolean sameRows(List<MarginDetail> expected,List<MarginDetail> actual){if(expected.size()!=actual.size())return false;var left=new HashMap<MarginDetailKey,byte[]>();var right=new HashMap<MarginDetailKey,byte[]>();for(var row:expected)if(left.putIfAbsent(row.key(),MarginDetailWritePort.CODEC.canonicalBytes(row))!=null)return false;for(var row:actual)if(right.putIfAbsent(row.key(),MarginDetailWritePort.CODEC.canonicalBytes(row))!=null)return false;return left.keySet().equals(right.keySet())&&left.keySet().stream().allMatch(key->Arrays.equals(left.get(key),right.get(key)));}
    public static String encodeDates(List<LocalDate> dates){return dates.stream().map(d->d.format(DateTimeFormatter.BASIC_ISO_DATE)).collect(java.util.stream.Collectors.joining(","));}
    private static LocalDate max(LocalDate a,LocalDate b){return a.isAfter(b)?a:b;}
    private static LocalDate requiredDate(JsonNode params,String field){JsonNode value=params.path(field);if(!value.isTextual())throw new IllegalStateException("D029 frozen "+field+" date required");return LocalDate.parse(value.asText());}
    private static LocalDate optionalDate(JsonNode params,String field){JsonNode value=params.get(field);return value==null||value.isNull()?null:LocalDate.parse(value.asText());}
    private static boolean sameDefinition(JsonNode expected,JsonNode actual){try{return JobDefinitionJson.mapper().treeToValue(expected,SyncJobDefinition.class).equals(JobDefinitionJson.mapper().treeToValue(actual,SyncJobDefinition.class));}catch(Exception invalid){return false;}}
    private static boolean hasLedger(Path path)throws Exception{try(var c=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath().normalize().toUri().toASCIIString()+"?mode=ro");var s=c.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")){var names=new HashSet<String>();while(r.next())names.add(r.getString(1));Set<String> needed=Set.of("ledger_meta","sync_runs","sync_entries","sync_events");if(Collections.disjoint(names,needed))return false;if(!names.containsAll(needed))throw new IllegalStateException("Partial D029 ledger schema");return true;}}
}
