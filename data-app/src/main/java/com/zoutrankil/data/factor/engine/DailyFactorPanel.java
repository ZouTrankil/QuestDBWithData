package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One frozen market panel feeds every pool. Arrays are borrowed read-only during computation. */
public final class DailyFactorPanel {
    private DailyFactorPanel() {}
    public record Security(String symbol, LocalDate[] dates, Map<String,double[]> columns, LocalDate listingDate) {}
    public record Features(String symbol, LocalDate[] dates, Map<String,double[]> columns) {}
    private static final class Moment {
        int count; double mean, m2;
        void add(double value) {
            if (!Double.isFinite(value)) return;
            count++;double delta=value-mean;mean+=delta/count;m2+=delta*(value-mean);
        }
        double average() { return count==0?Double.NaN:mean; }
        double std() { return count<2?Double.NaN:Math.sqrt(Math.max(0,m2/(count-1))); }
    }
    /** PIT-only consumers need current raw keys/market value, without rolling daily feature computation. */
    public static List<Features> selectRaw(List<Security> securities,LocalDate from,LocalDate through) {
        if(from==null || through==null || through.isBefore(from))throw new IllegalArgumentException("Ordered output interval required");
        var result=new java.util.ArrayList<Features>();var symbols=new java.util.HashSet<String>();
        for(var source:securities) {
            if(source.symbol()==null || !symbols.add(source.symbol()))throw new IllegalArgumentException("Unique securities required");
            var dates=source.dates();
            for(int i=0;i<dates.length;i++)if(dates[i]==null || i>0 && !dates[i].isAfter(dates[i-1]))
                throw new IllegalArgumentException("Ordered unique dates required");
            int begin=0,end=dates.length;
            while(begin<end && dates[begin].isBefore(from))begin++;
            while(end>begin && dates[end-1].isAfter(through))end--;
            if(begin==end)continue;
            var columns=new LinkedHashMap<String,double[]>();
            for(var column:source.columns().entrySet()) {
                if(column.getValue().length!=dates.length)throw new IllegalArgumentException("Misaligned raw input");
                double[] values=Arrays.copyOfRange(column.getValue(),begin,end);
                for(double value:values)if(Double.isInfinite(value))throw new IllegalArgumentException("Infinite raw input");
                columns.put(column.getKey(),values);
            }
            result.add(new Features(source.symbol(),Arrays.copyOfRange(dates,begin,end),Collections.unmodifiableMap(columns)));
        }
        return List.copyOf(result);
    }
    public static List<Features> compute(List<Security> securities) {
        return compute(securities,LocalDate.MIN,LocalDate.MAX);
    }
    /** Warmup remains in each security kernel, but only requested dates survive into shared pool panels. */
    public static List<Features> compute(List<Security> securities,LocalDate from,LocalDate through) {
        if(from==null || through==null || through.isBefore(from))throw new IllegalArgumentException("Ordered output interval required");
        if(securities.isEmpty()) return List.of();
        var seen=new java.util.HashSet<String>();
        boolean useVolume=false;
        for(var security:securities) {
            if(security.symbol()==null || !seen.add(security.symbol()))
                throw new IllegalArgumentException("Unique panel securities required");
            double[] volume=security.columns().get("vol");
            if(volume!=null && Arrays.stream(volume).anyMatch(Double::isFinite))useVolume=true;
        }
        var result=new java.util.ArrayList<Features>(securities.size());
        var sizes=new HashMap<LocalDate,Moment>();
        var market=new HashMap<LocalDate,Moment>();
        for(var security:securities) {
            // Cross-sectional dependencies need only returns and size, not every historical feature column.
            var dates=security.dates();var price=security.columns().get("close");var mv=security.columns().get("total_mv");
            if(price==null || mv==null || price.length!=dates.length || mv.length!=dates.length)
                throw new IllegalArgumentException("Aligned price and size columns required");
            double previous=Double.NaN;
            for(int i=0;i<dates.length;i++) {
                if(dates[i]==null || i>0 && !dates[i].isAfter(dates[i-1]))throw new IllegalArgumentException("Ordered unique dates required");
                double current=Double.isNaN(price[i])?previous:price[i];
                market.computeIfAbsent(dates[i],key->new Moment()).add(current/previous-1);
                sizes.computeIfAbsent(dates[i],key->new Moment()).add(mv[i]>0?Math.log(mv[i]):Double.NaN);
                previous=current;
            }
        }
        var nonlinearMeans=new HashMap<LocalDate,Moment>();
        for(var source:securities) {
            int begin=0,end=source.dates().length;
            while(begin<end && source.dates()[begin].isBefore(from))begin++;
            while(end>begin && source.dates()[end-1].isAfter(through))end--;
            if(begin==end)continue;
            var security=source;
            var input=new HashMap<>(security.columns());
            double[] volume=input.get(useVolume?"vol":"amount");
            if(volume==null) { volume=new double[security.dates().length];Arrays.fill(volume,Double.NaN); }
            input.put("effective_volume",volume);
            var features=new LinkedHashMap<>(input);
            features.putAll(DailyFactorFeatures.compute(security.dates(),input));
            int n=security.dates().length;
            double[] nonlinear=new double[n],marketReturns=new double[n],listingDays=new double[n];
            LocalDate listing=security.listingDate();
            for(int i=0;i<n;i++) {
                LocalDate date=security.dates()[i];var size=sizes.get(date);double std=size.std();
                double z=std>0?(features.get("log_total_mv")[i]-size.average())/std:Double.NaN;
                nonlinear[i]=z*z*z;
                if(i>=begin && i<end)nonlinearMeans.computeIfAbsent(date,key->new Moment()).add(nonlinear[i]);
                marketReturns[i]=market.get(date).average();
                listingDays[i]=listing==null?Double.NaN:ChronoUnit.DAYS.between(listing,date);
            }
            features.put("nonlinear_size",nonlinear);
            features.put("listing_days",listingDays);
            features.putAll(DailyMarketRiskFeatures.compute(features.get("ret"),marketReturns));
            var selected=new LinkedHashMap<String,double[]>();
            for(var column:features.entrySet())selected.put(column.getKey(),Arrays.copyOfRange(column.getValue(),begin,end));
            result.add(new Features(security.symbol(),Arrays.copyOfRange(security.dates(),begin,end),selected));
        }
        for(var security:result) {
            double[] nonlinear=security.columns().get("nonlinear_size");
            for(int i=0;i<nonlinear.length;i++)nonlinear[i]-=nonlinearMeans.get(security.dates()[i]).average();
        }
        // Maps are immutable; source arrays are borrowed and feature arrays are owned here.
        // Keep the source snapshot frozen while downstream stages read this panel.
        return result.stream().map(row->new Features(row.symbol(),row.dates(),Collections.unmodifiableMap(row.columns()))).toList();
    }
}
