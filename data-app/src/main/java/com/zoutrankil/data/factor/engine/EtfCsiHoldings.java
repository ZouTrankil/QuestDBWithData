package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.*;

/** CSI versions choose the latest announcement, then latest report within that announcement. */
public final class EtfCsiHoldings {
    private record Key(String etf,LocalDate announced) {}
    private final Map<String,NavigableMap<LocalDate,List<EtfConstituentWeights.Member>>> versions;
    private final LocalDate asOf;
    public EtfCsiHoldings(List<EtfConstituentWeights.Input> source,Set<String> validEtfs,LocalDate asOf) {
        this.asOf=Objects.requireNonNull(asOf);var reports=new HashMap<Key,LocalDate>();
        for(var row:source)if(eligible(row,validEtfs,asOf))reports.merge(new Key(row.etf(),row.announced()),row.periodEnd(),
                (a,b)->a.isAfter(b)?a:b);
        var groups=new HashMap<Key,List<EtfConstituentWeights.Input>>();
        for(var row:source)if(eligible(row,validEtfs,asOf)) {
            var key=new Key(row.etf(),row.announced());
            if(row.periodEnd().equals(reports.get(key)))groups.computeIfAbsent(key,ignored->new ArrayList<>()).add(row);
        }
        var indexed=new TreeMap<String,NavigableMap<LocalDate,List<EtfConstituentWeights.Member>>>();
        groups.forEach((key,rows)->{
            var members=new ArrayList<>(EtfConstituentWeights.normalize(rows,Set.of(key.etf())));
            members.sort(Comparator.comparing(EtfConstituentWeights.Member::stock));
            indexed.computeIfAbsent(key.etf(),ignored->new TreeMap<>()).put(key.announced(),List.copyOf(members));
        });
        indexed.replaceAll((etf,history)->Collections.unmodifiableNavigableMap(history));
        versions=Collections.unmodifiableMap(indexed);
    }
    public List<EtfConstituentWeights.Member> at(LocalDate date) {
        Objects.requireNonNull(date);
        if(date.isAfter(asOf))throw new IllegalArgumentException("CSI date exceeds holdings snapshot cutoff");
        var result=new ArrayList<EtfConstituentWeights.Member>();
        versions.forEach((etf,history)->{var version=history.floorEntry(date);if(version!=null)result.addAll(version.getValue());});
        return List.copyOf(result);
    }
    public record Refresh(LocalDate latestAnnouncementDate,LocalDate latestReportEndDate,int coveredEtfs,
            int snapshotVersions,LocalDate latestTradeDate,Long staleDays,String state) {}
    public Refresh refresh(LocalDate latestTradeDate) {
        Objects.requireNonNull(latestTradeDate);
        if(latestTradeDate.isAfter(asOf))throw new IllegalArgumentException("CSI refresh exceeds snapshot cutoff");
        LocalDate announced=null,period=null;int count=0,covered=0;
        for(var history:versions.values()) {
            boolean populated=false;
            for(var members:history.values())if(!members.isEmpty()) {
                populated=true;count++;
                for(var member:members) {
                    if(announced==null || member.announced().isAfter(announced))announced=member.announced();
                    if(period==null || member.periodEnd().isAfter(period))period=member.periodEnd();
                }
            }
            if(populated)covered++;
        }
        if(announced==null)return new Refresh(null,null,0,0,latestTradeDate,null,"empty");
        long stale=Math.max(0,java.time.temporal.ChronoUnit.DAYS.between(announced,latestTradeDate));
        return new Refresh(announced,period,covered,count,latestTradeDate,stale,stale>120?"stale":"ready");
    }
    private static boolean eligible(EtfConstituentWeights.Input row,Set<String> valid,LocalDate asOf) {
        return valid.contains(row.etf()) && row.announced()!=null && row.periodEnd()!=null && !row.announced().isAfter(asOf);
    }
}
