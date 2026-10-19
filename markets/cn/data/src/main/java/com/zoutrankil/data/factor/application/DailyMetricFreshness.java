package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** Python factor_performance_freshness policy; callers supply the authored matrix and calendar. */
public final class DailyMetricFreshness {
    private DailyMetricFreshness() {}
    public record Key(String universeId,String universeVersion,String factorId,String definitionVersion) {
        public Key {
            for(String value:Arrays.asList(universeId,universeVersion,factorId,definitionVersion))
                if(value==null || value.isBlank())throw new IllegalArgumentException("Explicit metric identity required");
        }
    }
    public record Report(LocalDate dataAsOf,LocalDate windowEndDate,Integer lagTradingDays,
            String status,String coverageStatus,int expectedKeyCount,int readyKeyCount,
            int missingKeyCount,int duplicateKeyCount,int nonReadyKeyCount,int notApplicableKeyCount) {}

    public static Report assess(List<FactorDailyMetricRow> rows,List<Key> expected,
            LocalDate asOf,LocalDate windowEnd,LocalDate calendarAsOf,Collection<LocalDate> openDates) {
        Objects.requireNonNull(rows);Objects.requireNonNull(expected);Objects.requireNonNull(openDates);
        if(new HashSet<>(expected).size()!=expected.size())
            throw new IllegalArgumentException("Duplicate expected metric keys");
        LocalDate latest=rows.stream().map(DailyMetricFreshness::date).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        LocalDate end=windowEnd==null?latest:windowEnd;
        int ready=0,missing=0,duplicate=0,nonReady=0,constant=0;
        var groups=new HashMap<List<String>,List<FactorDailyMetricRow>>();
        if(latest!=null)for(var row:rows)if(latest.equals(date(row))) {
            // Null identities remain unmatched, as empty strings do in the Python projection.
            var key=Arrays.asList(row.universeId(),row.universeVersion(),row.factorId(),row.definitionVersion());
            groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(row);
        }
        for(var key:expected) {
            var found=groups.get(List.of(key.universeId(),key.universeVersion(),key.factorId(),key.definitionVersion()));
            if(found==null)missing++;
            else if(found.size()!=1)duplicate++;
            else {
                var row=found.getFirst();
                String quality=Objects.toString(row.dataQualityStatus(),"").toLowerCase(Locale.ROOT);
                if(quality.equals("ready"))ready++;
                else {
                    nonReady++;
                    // Python normalizes quality before checking constant evidence.
                    if(quality.equals("constant_factor") && DailyMetricPublication.constantEvidence(row))constant++;
                }
            }
        }
        String coverage=expected.isEmpty()||latest==null?"unavailable":ready==expected.size()?"ready"
                :ready>0||constant>0?"partial":"unavailable";
        String status="unavailable";Integer lag=null;
        if(latest!=null && end!=null && asOf!=null && !expected.isEmpty()
                && ready+constant==expected.size()) {
            if(!asOf.isAfter(end)){lag=0;status="fresh";}
            else if(calendarAsOf!=null && !calendarAsOf.isBefore(asOf)) {
                lag=Math.toIntExact(openDates.stream().filter(Objects::nonNull).distinct()
                        .filter(day->day.isAfter(end)&&!day.isAfter(asOf)).count());
                status=lag<=2?"fresh":"stale";
            }
        }
        return new Report(latest,end,lag,status,coverage,expected.size(),ready,missing,duplicate,nonReady,constant);
    }
    private static LocalDate date(FactorDailyMetricRow row) {
        return row.tradeDate()==null?null:row.tradeDate().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
