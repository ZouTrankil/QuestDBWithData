package com.zoutrankil.data.calendar.application;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.repository.SqliteLedgerSchema;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/** Rebuild checkpoint candidates from immutable VERIFIED runs, never from target MAX(date). */
public final class ExchangeCalendarCoverage {
    private ExchangeCalendarCoverage() {}
    /** A schedule-only SQLite file has no run history yet; partial run schemas must fail closed. */
    public static boolean hasHistorySchema(Path path) throws SQLException {
        var names = SqliteLedgerSchema.tableNames(path);
        var required = Set.of("ledger_meta","sync_runs","sync_entries");
        if (java.util.Collections.disjoint(names, required)) return false;
        if (!names.containsAll(required)) throw new SQLException("Partial sync-run ledger schema");
        return true;
    }
    public record Interval(String exchange,LocalDate from,LocalDate to) {
        public Interval {
            if(!Set.of("SSE","SZSE").contains(exchange) || from==null || to==null || from.isAfter(to))
                throw new IllegalArgumentException("Invalid verified calendar interval");
        }
    }
    public static Map<String,LocalDate> contiguous(List<Interval> intervals,List<String> exchanges,LocalDate anchor) {
        var result=new LinkedHashMap<String,LocalDate>();
        for(String exchange:exchanges) {
            LocalDate next=anchor;
            var ordered=intervals.stream().filter(i->i.exchange().equals(exchange)).sorted(Comparator.comparing(Interval::from)).toList();
            for(var interval:ordered) {
                if(interval.from().isAfter(next)) break;
                if(!interval.to().isBefore(next)) next=interval.to().plusDays(1);
            }
            if(next.isAfter(anchor)) result.put(exchange,next.minusDays(1));
        }
        return Map.copyOf(result);
    }
    /** Callers must additionally read the actual target coverage before using these candidates. */
    public static Map<String,LocalDate> load(SyncRunLedger ledger,String targetId,List<String> exchanges,LocalDate anchor)
            throws Exception {
        var intervals=new ArrayList<Interval>();String after=null;int seen=0;
        var json=new ObjectMapper();
        while(true) {
            var page=ledger.history("data.exchange_calendar",after,100);
            for(var summary:page) {
                if(++seen>10000) throw new IllegalStateException("Calendar checkpoint history exceeds bounded scan; archive required");
                if(summary.state()!=SyncRunState.VERIFIED || !summary.targetId().equals(targetId) || summary.jobVersion()!=1) continue;
                var run=ledger.getRun(summary.id());var frozen=json.readTree(run.frozenJson());
                if(!frozen.at("/definition/datasetId").asText().equals("exchange_calendar")
                        || frozen.at("/definition/datasetVersion").asInt()!=1)
                    throw new IllegalStateException("Verified calendar snapshot does not match expected definition");
                LocalDate from=LocalDate.parse(frozen.get("from").textValue());
                LocalDate to=LocalDate.parse(frozen.get("to").textValue());
                for(var exchange:frozen.at("/parameters/exchanges")) intervals.add(new Interval(exchange.textValue(),from,to));
            }
            if(page.size()<100) break;
            after=page.getLast().id();
        }
        return contiguous(intervals,exchanges,anchor);
    }
}
