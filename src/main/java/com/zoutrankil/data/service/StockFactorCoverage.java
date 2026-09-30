package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;

/** Checkpoint coverage is derived only from completed incremental runs for one target and route. */
public final class StockFactorCoverage {
    private static final int PAGE_SIZE=100;
    private static final int MAX_HISTORY=10_000;
    private StockFactorCoverage() {}

    public record Coverage(LocalDate anchor,LocalDate through,List<String> runIds) {
        public Coverage {
            Objects.requireNonNull(anchor);Objects.requireNonNull(through);runIds=List.copyOf(runIds);
            if(through.isBefore(anchor) || runIds.isEmpty()) throw new IllegalArgumentException("Verified stock-factor coverage required");
        }
    }
    private record Interval(String runId,LocalDate anchor,LocalDate from,LocalDate to) {}

    /**
     * Finds the greatest contiguous checkpoint among fully verified incremental runs with the same
     * target identity, bootstrap anchor, and exact full-market or single-code source route.
     */
    public static Optional<Coverage> checkpoint(Path ledgerPath,String targetId,String tsCode) throws Exception {
        Objects.requireNonNull(ledgerPath);Objects.requireNonNull(targetId);
        if(!Files.isRegularFile(ledgerPath) || !hasHistorySchema(ledgerPath)) return Optional.empty();
        SyncRunLedger ledger=SyncRunLedger.openReadOnly(ledgerPath);
        ObjectMapper json=JobDefinitionJson.mapper();
        var definition=StockFactorSyncJobOwner.DEFINITION;
        var intervals=new ArrayList<Interval>();
        String after=null;int seen=0;
        while(true) {
            var page=ledger.history("data.stk_factor",after,PAGE_SIZE);
            for(var summary:page) {
                if(++seen>MAX_HISTORY) throw new IllegalStateException("stk_factor checkpoint history exceeds bounded scan");
                if(!Set.of(SyncRunState.VERIFIED,SyncRunState.VERIFIED_EMPTY).contains(summary.state())
                        || !targetId.equals(summary.targetId()) || summary.jobVersion()!=definition.version()) continue;
                var run=ledger.getRun(summary.id());
                JsonNode frozen=json.readTree(run.frozenJson());
                if(!frozen.at("/definition/jobId").asText().equals("data.stk_factor")
                        || frozen.at("/definition/version").asInt()!=definition.version()
                        || !frozen.at("/definition/datasetId").asText().equals("stk_factor")
                        || frozen.at("/definition/datasetVersion").asInt()!=definition.datasetVersion()
                        || !frozen.path("mode").asText().equals("INCREMENTAL")) continue;
                JsonNode parameters=frozen.path("parameters");
                JsonNode frozenTarget=parameters.get("targetId");
                // A run created before target identity was bound into the frozen request cannot establish a checkpoint.
                if(frozenTarget==null || !frozenTarget.isTextual() || !targetId.equals(frozenTarget.textValue())) continue;
                JsonNode code=parameters.get("tsCode");
                if(tsCode==null ? code!=null : code==null || !code.isTextual() || !tsCode.equals(code.textValue())) continue;
                JsonNode anchorNode=parameters.get("checkpointAnchor");
                // Runs created before the checkpoint contract was introduced cannot establish it.
                if(anchorNode==null) continue;
                if(!anchorNode.isTextual()) throw new IllegalStateException("Verified stk_factor checkpoint anchor is malformed");
                JsonNode fromNode=frozen.get("from"),toNode=frozen.get("to");
                if(fromNode==null || !fromNode.isTextual() || toNode==null || !toNode.isTextual())
                    throw new IllegalStateException("Verified stk_factor run has no frozen bounded interval");
                LocalDate anchor=LocalDate.parse(anchorNode.textValue());
                LocalDate from=LocalDate.parse(fromNode.textValue()),to=LocalDate.parse(toNode.textValue());
                long days=java.time.temporal.ChronoUnit.DAYS.between(from,to)+1;
                if(from.isAfter(to) || days<1 || days>StockFactorSyncJobOwner.MAX_WINDOW_DAYS || to.isBefore(anchor))
                    throw new IllegalStateException("Verified stk_factor run has invalid checkpoint coverage");
                intervals.add(new Interval(summary.id(),anchor,from,to));
            }
            if(page.size()<PAGE_SIZE) break;
            after=page.getLast().id();
        }
        if(intervals.isEmpty()) return Optional.empty();

        var byAnchor=new TreeMap<LocalDate,List<Interval>>();
        intervals.forEach(i->byAnchor.computeIfAbsent(i.anchor(),ignored->new ArrayList<>()).add(i));
        var candidates=new ArrayList<Coverage>();
        for(var entry:byAnchor.entrySet()) {
            LocalDate anchor=entry.getKey(),through=anchor.minusDays(1);
            var ordered=entry.getValue().stream().sorted(Comparator.comparing(Interval::from).thenComparing(Interval::to)).toList();
            var runs=new ArrayList<String>();
            for(var interval:ordered) {
                if(interval.to().isBefore(anchor)) continue;
                if(interval.from().isAfter(through.plusDays(1))) break;
                if(interval.to().isAfter(through)) {
                    through=interval.to();runs.add(interval.runId());
                }
            }
            if(!runs.isEmpty() && !through.isBefore(anchor)) candidates.add(new Coverage(anchor,through,runs));
        }
        // Multiple explicit bootstraps can coexist; use the chain with the farthest verified end.
        // Equal ends prefer the earlier anchor, which proves more contiguous scheduled coverage.
        return candidates.stream().max(Comparator.comparing(Coverage::through)
                .thenComparing(Coverage::anchor,Comparator.reverseOrder()));
    }

    private static boolean hasHistorySchema(Path path) throws Exception {
        var names=new HashSet<String>();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+path.toUri().toASCIIString()+"?mode=ro");
            var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while(rows.next()) names.add(rows.getString(1));
        }
        var required=Set.of("ledger_meta","sync_runs","sync_entries");
        if(Collections.disjoint(names,required)) return false;
        if(!names.containsAll(required)) throw new IllegalStateException("Partial stk_factor sync-run ledger schema");
        return true;
    }
}
