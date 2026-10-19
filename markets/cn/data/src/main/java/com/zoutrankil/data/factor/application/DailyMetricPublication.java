package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Python daily_publication_report and complete_metric_checkpoint semantics. */
public final class DailyMetricPublication {
    private DailyMetricPublication() {}
    public record Key(String universeId,String factorId) {}
    public record Report(int expectedKeys,int readyKeys,int unavailableKeys,int notApplicableKeys,
            Map<String,Integer> unavailableReasons,String status,boolean reusableCheckpoint) {
        public Report { unavailableReasons=Map.copyOf(unavailableReasons); }
    }
    public static Report inspect(LocalDate signal,List<FactorDailyMetricRow> rows,Set<Key> expected,boolean requireEffective) {
        return inspect(Objects.requireNonNull(signal),rows,expected,requireEffective,false);
    }
    /** Missing declared keys may be persisted, but never form a reusable checkpoint. */
    public static Report inspect(LocalDate signal,List<FactorDailyMetricRow> rows,Set<Key> expected,
            boolean requireEffective,boolean allowMissing) {
        if(expected.isEmpty() || expected.size()>10000 || rows.size()>10000)throw new IllegalArgumentException("Explicit bounded metric matrix required");
        var actual=new HashSet<Key>();var reasons=new TreeMap<String,Integer>();
        int ready=0,constant=0;boolean checkpoint=true;
        for(var row:rows) {
            if(row.tradeDate()==null || !row.tradeDate().equals(signal.atStartOfDay().toInstant(ZoneOffset.UTC))
                    || !actual.add(new Key(row.universeId(),row.factorId())))
                throw new IllegalArgumentException("Duplicate or wrong-date metric key");
            boolean usable="ready".equals(row.dataQualityStatus()) && (!requireEffective || present(row.pureRankIc()));
            boolean diagnostic=constant(row);
            if(usable)ready++;
            else reasons.merge(requireEffective && "ready".equals(row.dataQualityStatus())
                    ? "missing_effective_metric":Objects.requireNonNull(row.dataQualityStatus()),1,Integer::sum);
            if(diagnostic)constant++;
            checkpoint&=(usable || diagnostic) && row.evaluationSampleHash()!=null
                    && row.evaluationSampleHash().matches("[a-f0-9]{64}");
        }
        if(!expected.containsAll(actual) || (!allowMissing && !actual.equals(expected)))
            throw new IllegalArgumentException("Daily metric declared key set mismatch");
        int missing=expected.size()-actual.size();
        if(missing>0) { reasons.merge("missing_metric",missing,Integer::sum);checkpoint=false; }
        return new Report(expected.size(),ready,expected.size()-ready,constant,reasons,
                ready==expected.size()?"ready":ready==0?"unavailable":"partial",checkpoint);
    }
    private static boolean present(Double value) { return value!=null && !Double.isNaN(value); }
    static boolean constant(FactorDailyMetricRow row) {
        return "constant_factor".equals(row.dataQualityStatus()) && constantEvidence(row);
    }
    static boolean constantEvidence(FactorDailyMetricRow row) {
        if(row.sampleCount()==null || row.sampleCount()<3
                || row.coverage()==null || row.coverage()!=1.0)return false;
        Double[] outputs={row.rankIc(),row.topMinusBottom(),row.decileMonotonicity(),row.pureRankIc(),
                row.pureTopMinusBottom(),row.pureDecileMonotonicity(),row.decile1MeanReturn(),row.decile2MeanReturn(),
                row.decile3MeanReturn(),row.decile4MeanReturn(),row.decile5MeanReturn(),row.decile6MeanReturn(),
                row.decile7MeanReturn(),row.decile8MeanReturn(),row.decile9MeanReturn(),row.decile10MeanReturn()};
        return Arrays.stream(outputs).noneMatch(DailyMetricPublication::present);
    }
}
