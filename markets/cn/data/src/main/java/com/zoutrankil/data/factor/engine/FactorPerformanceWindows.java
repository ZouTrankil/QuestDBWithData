package com.zoutrankil.data.factor.engine;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import java.time.*;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Shared history is grouped once, then projected into the four Python performance tail windows. */
public final class FactorPerformanceWindows {
    private FactorPerformanceWindows() {}
    public record Definition(String factorId,String version) {}
    public record Identity(String universeId,String universeVersion,String factorId,String definitionVersion,
                           String labelDefinition,Integer horizon,String evaluationProfile) {}
    public record Window(Identity identity,LocalDate asOf,int windowDays,LocalDate windowStartDate,
                         LocalDate windowEndDate,boolean completeWindow,boolean pureCompleteWindow,
                         Map<String,Object> statistics) {}
    public static List<Window> compute(List<FactorDailyMetricRow> rows,LocalDate asOf,
            Map<Definition,String> directionByFactor) {
        Objects.requireNonNull(asOf);Objects.requireNonNull(directionByFactor);
        if(rows==null||rows.size()>1_000_000)throw new IllegalArgumentException("Bounded daily metric history required");
        var groups=new LinkedHashMap<Identity,TreeMap<LocalDate,FactorDailyMetricRow>>();
        for(var row:rows) {
            if(row.tradeDate()==null||row.maturityAt()==null)continue;
            var day=date(row.tradeDate());
            if(day.isAfter(asOf)||date(row.maturityAt()).isAfter(asOf))continue;
            var id=new Identity(row.universeId(),row.universeVersion(),row.factorId(),row.definitionVersion(),
                    row.labelDefinition(),row.horizon(),row.evaluationProfile());
            if(groups.computeIfAbsent(id,ignored->new TreeMap<>()).putIfAbsent(day,row)!=null)
                throw new IllegalArgumentException("Duplicate daily metric key in history: "+id+"/"+day);
        }
        var result=new ArrayList<Window>();
        for(var entry:groups.entrySet()) {
            var history=new ArrayList<>(entry.getValue().values());
            String direction=directionByFactor.get(new Definition(entry.getKey().factorId(),entry.getKey().definitionVersion()));
            if(direction==null||!Set.of("positive","negative","context").contains(direction))
                throw new IllegalArgumentException("Versioned factor direction required: "+entry.getKey());
            double sign="negative".equals(direction)?-1:1;
            var membership=history.stream().map(FactorDailyMetricRow::membershipAsOf).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).map(FactorPerformanceWindows::date).map(LocalDate::toString).orElse("");
            for(int size:new int[]{5,20,60,252}) {
                var sample=history.subList(Math.max(0,history.size()-size),history.size());
                var raw=summary(sample,r->number(r.rankIc()));
                var adjusted=summary(sample,r->sign*number(r.rankIc()));
                var pure=summary(sample,r->number(r.pureRankIc()));
                var spread=summary(sample,r->number(r.topMinusBottom()));
                var pureSpread=summary(sample,r->number(r.pureTopMinusBottom()));
                var values=new LinkedHashMap<String,Object>();
                values.put("directionPrior",direction);values.put("membershipAsOf",membership);
                String control="";
                for(var row:sample)if(row.controlDefinitionVersion()!=null)control=row.controlDefinitionVersion();
                values.put("controlDefinitionVersion",control);
                values.put("historicalIc",raw.mean());values.put("directionAdjustedIc",adjusted.mean());
                values.put("pureRankIc",pure.mean());values.put("observationDays",adjusted.observations());
                values.put("pureObservationDays",pure.observations());values.put("icir",adjusted.icir());
                values.put("pureIcir",pure.icir());values.put("hacTStat",adjusted.hacTStat());values.put("pureHacTStat",pure.hacTStat());
                values.put("icWinRate",adjusted.positiveRate());values.put("pureIcWinRate",pure.positiveRate());
                values.put("cumulativeIc",adjusted.cumulative());values.put("pureCumulativeIc",pure.cumulative());
                values.put("cumulativeLongShort",spread.cumulative());values.put("pureCumulativeLongShort",pureSpread.cumulative());
                values.put("longShortSameDirection",spread.positiveRate());values.put("pureLongShortSameDirection",pureSpread.positiveRate());
                values.put("monotonicSameDirection",summary(sample,r->number(r.decileMonotonicity())).positiveRate());
                values.put("pureMonotonicSameDirection",summary(sample,r->number(r.pureDecileMonotonicity())).positiveRate());
                var autocorrelation=summary(sample,r->number(r.factorAutocorrelation())).mean();
                values.put("factorAutocorrelation",autocorrelation);values.put("rankTurnoverProxy",autocorrelation==null?null:1-autocorrelation);
                values.put("averageCoverage",summary(sample,r->number(r.coverage())).mean());
                values.put("averageSampleCount",summary(sample,r->number(r.sampleCount())).mean());
                var deciles=new ArrayList<Map<String,Object>>();
                for(int bucket=1;bucket<=10;bucket++) {
                    final int index=bucket;
                    var decile=new LinkedHashMap<String,Object>();decile.put("group",bucket);
                    decile.put("meanReturn",summary(sample,r->decile(r,index)).mean());
                    deciles.add(Collections.unmodifiableMap(decile));
                }
                values.put("deciles",List.copyOf(deciles));
                result.add(new Window(entry.getKey(),asOf,size,date(sample.getFirst().tradeDate()),date(sample.getLast().tradeDate()),
                        adjusted.observations()>=size,pure.observations()>=size,Collections.unmodifiableMap(values)));
            }
        }
        return List.copyOf(result);
    }
    private static FactorPerformanceStatistics.Summary summary(List<FactorDailyMetricRow> rows,ToDoubleFunction<FactorDailyMetricRow> value) {
        return FactorPerformanceStatistics.summarize(rows.stream().mapToDouble(value).toArray());
    }
    private static double number(Number value) { return value==null?Double.NaN:value.doubleValue(); }
    private static double decile(FactorDailyMetricRow row,int bucket) {
        return number(switch(bucket) {
            case 1->row.decile1MeanReturn();case 2->row.decile2MeanReturn();case 3->row.decile3MeanReturn();
            case 4->row.decile4MeanReturn();case 5->row.decile5MeanReturn();case 6->row.decile6MeanReturn();
            case 7->row.decile7MeanReturn();case 8->row.decile8MeanReturn();case 9->row.decile9MeanReturn();
            case 10->row.decile10MeanReturn();default->throw new IllegalArgumentException("Decile must be 1..10");
        });
    }
    private static LocalDate date(Instant value) { return value.atZone(ZoneOffset.UTC).toLocalDate(); }
}
