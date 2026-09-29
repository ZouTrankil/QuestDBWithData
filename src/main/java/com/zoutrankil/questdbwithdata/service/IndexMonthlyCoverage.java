package com.zoutrankil.questdbwithdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.IndexMonthlyWritePort;
import com.zoutrankil.questdbwithdata.repository.SyncRunLedger;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Same-target/code receipt-backed checkpoint and reconciliation; history page order is not treated as chronology. */
public final class IndexMonthlyCoverage {
    private static final int HISTORY_PAGE=100,MAX_HISTORY=10_000,MAX_CHILDREN=100,MAX_COVERED_DAYS=20_000;
    private IndexMonthlyCoverage(){}
    public record Checkpoint(LocalDate anchor,LocalDate through,Instant latestVerifiedAt){
        public Checkpoint{Objects.requireNonNull(anchor);Objects.requireNonNull(through);Objects.requireNonNull(latestVerifiedAt);if(through.isBefore(anchor))throw new IllegalArgumentException("Invalid D022 checkpoint");}
    }
    private record Interval(String runId,SyncJobDefinition.Mode mode,LocalDate anchor,LocalDate from,LocalDate to,
            Instant verifiedAt,SyncJobRunner.Page<IndexMonthly> page){}
    public static Optional<Checkpoint> checkpoint(Path ledgerPath,String targetId,String providerCode)throws Exception {
        String code=IndexMonthlySource.requireIndex(providerCode).providerCode();
        var intervals=verifiedIntervals(ledgerPath,targetId,code,EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL));
        var grouped=new HashMap<LocalDate,List<Interval>>();for(var i:intervals){if(i.anchor()==null)throw new IllegalStateException("D022 incremental receipt lacks bootstrap anchor");grouped.computeIfAbsent(i.anchor(),x->new ArrayList<>()).add(i);}
        var candidates=new ArrayList<Checkpoint>();
        for(var entry:grouped.entrySet()){
            LocalDate anchor=entry.getKey(),through=anchor.minusDays(1);boolean started=false;Instant latest=Instant.MIN;
            var ordered=entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to).thenComparing(Interval::verifiedAt).thenComparing(Interval::runId)).toList();
            for(var i:ordered){if(i.page().rows().isEmpty())continue;if(!started){if(!i.from().equals(anchor))continue;started=true;}
                if(i.from().isAfter(through.plusDays(1)))break;if(i.to().isAfter(through))through=i.to();if(i.verifiedAt().isAfter(latest))latest=i.verifiedAt();}
            if(started&&!through.isBefore(anchor))candidates.add(new Checkpoint(anchor,through,latest));
        }
        return candidates.stream().max(Comparator.comparing(Checkpoint::through).thenComparing(Checkpoint::latestVerifiedAt).thenComparing(Checkpoint::anchor,Comparator.reverseOrder()));
    }
    /** Compare every existing row of this code with the most recently verified nonempty receipt covering that month-day. */
    public static int validateExistingTarget(Path ledgerPath,String targetId,String code,IndexMonthlyWritePort writer)throws Exception {
        String canonical=IndexMonthlySource.requireIndex(code).providerCode();var actual=writer.readExistingRows(canonical);
        var intervals=verifiedIntervals(ledgerPath,targetId,canonical,EnumSet.of(SyncJobDefinition.Mode.INCREMENTAL,SyncJobDefinition.Mode.BACKFILL,SyncJobDefinition.Mode.RECONCILE))
                .stream().filter(i->!i.page().rows().isEmpty()).toList();
        if(actual.isEmpty()&&intervals.isEmpty())return 0;
        if(!actual.isEmpty()&&intervals.isEmpty())throw new IllegalStateException("D022 physical rows lack same-target verified source receipts");
        var newest=new HashMap<LocalDate,Interval>();
        for(var i:intervals){long days=ChronoUnit.DAYS.between(i.from(),i.to())+1;if(days<1||days>IndexMonthlySource.MAX_WINDOW_DAYS)throw new IllegalStateException("D022 receipt exceeds source window bound");
            for(LocalDate day=i.from();!day.isAfter(i.to());day=day.plusDays(1)){var prior=newest.get(day);
                if(prior==null||i.verifiedAt().isAfter(prior.verifiedAt())||i.verifiedAt().equals(prior.verifiedAt())&&i.runId().compareTo(prior.runId())>0)newest.put(day,i);
                if(newest.size()>MAX_COVERED_DAYS)throw new IllegalStateException("D022 verified interval coverage exceeds 20000 calendar days");}}
        var expected=new HashMap<LocalDate,List<IndexMonthly>>();for(var e:newest.entrySet())expected.put(e.getKey(),e.getValue().page().rows().stream().filter(r->r.tradeDate().equals(e.getKey())).toList());
        var actualByDate=new HashMap<LocalDate,List<IndexMonthly>>();for(var row:actual)actualByDate.computeIfAbsent(row.tradeDate(),x->new ArrayList<>()).add(row);
        if(!expected.keySet().containsAll(actualByDate.keySet()))throw new IllegalStateException("D022 target has an uncovered provider-code/date key");
        for(var e:expected.entrySet())if(!sameRows(e.getValue(),actualByDate.getOrDefault(e.getKey(),List.of())))throw new IllegalStateException("D022 actual target differs from newest verified receipt for "+e.getKey());
        return actual.size();
    }
    private static List<Interval> verifiedIntervals(Path path,String targetId,String code,Set<SyncJobDefinition.Mode> modes)throws Exception {
        Objects.requireNonNull(path);Objects.requireNonNull(targetId);if(!Files.isRegularFile(path)||!hasHistorySchema(path))return List.of();
        var ledger=SyncRunLedger.openReadOnly(path);var json=JobDefinitionJson.mapper();JsonNode expected=json.valueToTree(IndexMonthlySyncJobOwner.DEFINITION);
        var found=new ArrayList<Interval>();String after=null;int seen=0;
        while(true){var summaries=ledger.history(IndexMonthlySyncJobOwner.DEFINITION.jobId(),after,HISTORY_PAGE);
            for(var summary:summaries){if(++seen>MAX_HISTORY)throw new IllegalStateException("D022 ledger history exceeds bounded scan");
                if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())||!targetId.equals(summary.targetId())||summary.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version())continue;
                var run=ledger.getRun(summary.id());if(!targetId.equals(run.targetId())||!IndexMonthlySyncJobOwner.DEFINITION.jobId().equals(run.jobId())||run.jobVersion()!=IndexMonthlySyncJobOwner.DEFINITION.version())continue;
                JsonNode frozen=json.readTree(run.frozenJson());if(!sameDefinition(expected,frozen.path("definition")))continue;
                SyncJobDefinition.Mode mode;try{mode=SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText());}catch(RuntimeException bad){throw new IllegalStateException("Invalid D022 frozen mode",bad);}
                JsonNode parameters=frozen.path("parameters");if(!modes.contains(mode)||!targetId.equals(parameters.path("targetId").asText())||!code.equals(parameters.path("tsCode").asText()))continue;
                LocalDate from=date(frozen.path("from").asText()),to=date(frozen.path("to").asText());if(from.isAfter(to)||ChronoUnit.DAYS.between(from,to)+1>IndexMonthlySource.MAX_WINDOW_DAYS)throw new IllegalStateException("Invalid D022 frozen window");
                LocalDate anchor=null;if(mode==SyncJobDefinition.Mode.INCREMENTAL){JsonNode node=parameters.get("checkpointAnchor");if(node==null||!node.isTextual())throw new IllegalStateException("D022 incremental receipt lacks its anchor");anchor=date(node.asText());if(from.isBefore(anchor)||to.isBefore(anchor))throw new IllegalStateException("D022 incremental window precedes anchor");}
                Instant observedAt;try{observedAt=Instant.parse(parameters.path("observedAt").asText());}catch(RuntimeException bad){throw new IllegalStateException("D022 run lacks frozen observedAt",bad);}
                found.add(readSlice(ledger,summary.id(),summary.state(),code,mode,anchor,from,to,observedAt,Instant.parse(summary.updatedAt())));
            }
            if(summaries.size()<HISTORY_PAGE)break;after=summaries.getLast().id();
        }
        return List.copyOf(found);
    }
    private static Interval readSlice(SyncRunLedger ledger,String runId,SyncRunState runState,String code,SyncJobDefinition.Mode mode,
            LocalDate anchor,LocalDate from,LocalDate to,Instant observedAt,Instant verifiedAt)throws Exception {
        var slices=new ArrayList<SyncRunLedger.Entry>();String after=null;int seen=0;while(true){var entries=ledger.entries(runId,after,MAX_CHILDREN);
            for(var entry:entries){if(++seen>MAX_CHILDREN)throw new IllegalStateException("D022 run exceeds child budget");if(entry.kind()==SyncRunLedger.Kind.SLICE)slices.add(entry);}
            if(entries.size()<MAX_CHILDREN)break;after=entries.getLast().id();}
        if(slices.size()!=1)throw new IllegalStateException("D022 run must have exactly one code/window slice");var slice=slices.getFirst();
        if(slice.state()!=SyncRunState.VERIFIED&&slice.state()!=SyncRunState.VERIFIED_EMPTY)throw new IllegalStateException("D022 run includes nonverified slice");
        JsonNode fetched=null;for(var event:ledger.events(slice.id(),-1,100))if(event.state()==SyncRunState.FETCHED){if(fetched!=null)throw new IllegalStateException("Duplicate D022 fetched receipt event");fetched=JobDefinitionJson.mapper().readTree(event.payloadJson());}
        if(fetched==null||!code.equals(fetched.path("cursor").asText()))throw new IllegalStateException("D022 slice source identity differs");
        String path=fetched.path("responseEvidence").asText(""),fingerprint=fetched.path("sourceFingerprint").asText("");if(path.isBlank()||!fingerprint.matches("[0-9a-f]{64}"))throw new IllegalStateException("Invalid D022 receipt reference");
        var page=IndexMonthlySource.reopen(Path.of(path),fingerprint,code,from,to,observedAt);
        if(page.rows().isEmpty()!=(slice.state()==SyncRunState.VERIFIED_EMPTY)||page.rows().isEmpty()!=(runState==SyncRunState.VERIFIED_EMPTY))throw new IllegalStateException("D022 empty status differs from source receipt");
        return new Interval(runId,mode,anchor,from,to,verifiedAt,page);
    }
    private static boolean sameRows(List<IndexMonthly> expected,List<IndexMonthly> actual){if(expected.size()!=actual.size())return false;
        var left=new HashMap<IndexMonthlyKey,byte[]>();var right=new HashMap<IndexMonthlyKey,byte[]>();for(var row:expected)if(left.putIfAbsent(row.key(),IndexMonthlyWritePort.CODEC.canonicalBytes(row))!=null)return false;
        for(var row:actual)if(right.putIfAbsent(row.key(),IndexMonthlyWritePort.CODEC.canonicalBytes(row))!=null)return false;
        return left.keySet().equals(right.keySet())&&left.keySet().stream().allMatch(k->Arrays.equals(left.get(k),right.get(k)));}
    private static boolean sameDefinition(JsonNode expected,JsonNode actual){try{var json=JobDefinitionJson.mapper();return json.treeToValue(expected,SyncJobDefinition.class).equals(json.treeToValue(actual,SyncJobDefinition.class));}catch(com.fasterxml.jackson.core.JsonProcessingException bad){return false;}}
    private static LocalDate date(String value){try{if(value==null||!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(value);}catch(RuntimeException bad){throw new IllegalStateException("Invalid D022 frozen date",bad);}}
    private static boolean hasHistorySchema(Path path)throws Exception{var names=new HashSet<String>();try(var c=DriverManager.getConnection("jdbc:sqlite:"+path.toUri().toASCIIString()+"?mode=ro");var s=c.createStatement();var rows=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")){while(rows.next())names.add(rows.getString(1));}
        Set<String> required=Set.of("ledger_meta","sync_runs","sync_entries","sync_events");if(Collections.disjoint(names,required))return false;if(!names.containsAll(required))throw new IllegalStateException("Partial D022 sync ledger schema");return true;}
}
