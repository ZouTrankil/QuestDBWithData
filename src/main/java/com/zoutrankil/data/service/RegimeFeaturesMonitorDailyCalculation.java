package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import java.time.*;
import java.util.*;
import static com.zoutrankil.data.service.MarketSentimentDailyCalculation.*;

/** Native implementation of the existing features_monitor_daily.py proxy-style product.
 * Percentile ranks are fitted on the explicit warmup/output window, as in its owner.
 * The size-bucket index names are proxies, not separately downloaded index returns.
 */
public final class RegimeFeaturesMonitorDailyCalculation {
    private RegimeFeaturesMonitorDailyCalculation() {}
    public static final String ALGORITHM_VERSION="java.regime_features_monitor_daily.v1_legacy_proxy_style";
    public static final List<String> COLUMNS=List.of("trade_date","month","hs300_ret_mtd","zz500_ret_mtd","all_a_ret_mtd","cs1000_ret_mtd",
            "small_large_ret_mtd","growth_value_ret_mtd","avg_up_down_ratio_5d","avg_limit_up_count_5d","avg_limit_down_count_5d",
            "avg_turnover_rate_5d","avg_pct_positive_ratio_5d","avg_pct_negative_ratio_5d","northbound_net_buy_mtd","margin_balance_change_mtd",
            "all_a_pe_ttm_percentile_latest","all_a_pb_percentile_latest","erp_latest","data_quality_flag","updated_at");
    public static final List<String> REQUIRED_CONTEXT_FIELDS=List.of("hs300_ret_mtd","zz500_ret_mtd","all_a_ret_mtd",
            "small_large_ret_mtd","growth_value_ret_mtd","avg_up_down_ratio_5d");
    public record Stock(String code,double close,double previous,double amount,double turnover,double totalMv,
                        double pb,double peTtm,double upLimit,double downLimit) {
        double ret(){return divide(close,previous)-1;}
        boolean up(){return close>previous;}
        boolean down(){return close<previous;}
        boolean limitUp(){return finite(upLimit)&&Math.abs(close-upLimit)<=1e-6;}
        boolean limitDown(){return finite(downLimit)&&Math.abs(close-downLimit)<=1e-6;}
    }
    public static final class Day {
        public final LocalDate date;
        public final Map<String,Double> numbers=new LinkedHashMap<>();
        public Day(LocalDate date){this.date=Objects.requireNonNull(date);}
        public double get(String field){return numbers.getOrDefault(field,Double.NaN);}
        public void put(String field,double value){numbers.put(field,finite(value)?value:Double.NaN);}
    }
    public record Valuation(double peMedian,double pbMedian) {}
    public static LocalDate warmupFrom(LocalDate from){
        var month=from.withDayOfMonth(1);var rolling=from.minusDays(20);return month.isBefore(rolling)?month:rolling;
    }
    public static Day aggregate(LocalDate date,List<Stock> stocks){
        if(stocks.isEmpty())throw new IllegalArgumentException("Nonempty tradable stock panel required");
        var result=new Day(date);double[] size=bucketMeans(stocks,false),value=bucketMeans(stocks,true);
        result.put("all_a_daily_ret",zeroMissing(mean(stocks.stream().mapToDouble(Stock::ret).toArray())));
        result.put("small",zeroMissing(size[0]));result.put("mid",zeroMissing(size[1]));result.put("large",zeroMissing(size[2]));
        result.put("value",zeroMissing(value[0]));result.put("growth",zeroMissing(value[2]));
        long up=stocks.stream().filter(Stock::up).count(),down=stocks.stream().filter(Stock::down).count();
        result.put("up_down_ratio",divide(up,down));
        result.put("limit_up_count",stocks.stream().filter(Stock::limitUp).count());
        result.put("limit_down_count",stocks.stream().filter(Stock::limitDown).count());
        result.put("avg_turnover_rate",mean(stocks.stream().mapToDouble(Stock::turnover).toArray()));
        result.put("pct_positive_ratio",up/(double)stocks.size());result.put("pct_negative_ratio",down/(double)stocks.size());
        return result;
    }
    /** rank(method=first) follows the prepared panel's ts_code order. qcut is right-closed. */
    private static double[] bucketMeans(List<Stock> stocks,boolean pb){
        var eligible=stocks.stream().filter(s->finite(s.ret())&&finite(pb?s.pb:s.totalMv))
                .sorted(Comparator.comparingDouble((Stock s)->pb?s.pb:s.totalMv).thenComparing(Stock::code)).toList();
        double[] result={Double.NaN,Double.NaN,Double.NaN};
        if(eligible.stream().map(s->pb?s.pb:s.totalMv).distinct().count()<3)return result;
        double[] totals=new double[3];int[] counts=new int[3];int n=eligible.size();
        for(int i=0;i<n;i++){int bucket=i<=(n-1)/3.0?0:i<=2*(n-1)/3.0?1:2;totals[bucket]+=eligible.get(i).ret();counts[bucket]++;}
        for(int i=0;i<3;i++)result[i]=divide(totals[i],counts[i]);return result;
    }
    public static void styleAndBreadth(List<Day> days){
        String month=null;double[] product={1,1,1,1,1,1};
        var dailyNames=List.of("all_a_daily_ret","large","mid","small","growth","value");
        var monthlyNames=List.of("all_a_ret_mtd","hs300_ret_mtd","zz500_ret_mtd","cs1000_ret_mtd","growth_ret_mtd","value_ret_mtd");
        var breadthNames=List.of("up_down_ratio","limit_up_count","limit_down_count","avg_turnover_rate","pct_positive_ratio","pct_negative_ratio");
        var rollingNames=List.of("avg_up_down_ratio_5d","avg_limit_up_count_5d","avg_limit_down_count_5d","avg_turnover_rate_5d","avg_pct_positive_ratio_5d","avg_pct_negative_ratio_5d");
        for(int i=0;i<days.size();i++){
            var day=days.get(i);String current=YearMonth.from(day.date).toString();
            if(!current.equals(month)){Arrays.fill(product,1);month=current;}
            for(int j=0;j<product.length;j++){product[j]*=1+zeroMissing(day.get(dailyNames.get(j)));day.put(monthlyNames.get(j),product[j]-1);}
            day.put("small_large_ret_mtd",day.get("cs1000_ret_mtd")-day.get("hs300_ret_mtd"));
            day.put("growth_value_ret_mtd",day.get("growth_ret_mtd")-day.get("value_ret_mtd"));
            for(int j=0;j<breadthNames.size();j++){double[] observations=new double[i-Math.max(0,i-4)+1];
                for(int k=0;k<observations.length;k++)observations[k]=days.get(Math.max(0,i-4)+k).get(breadthNames.get(j));
                day.put(rollingNames.get(j),mean(observations));}
        }
    }
    public static Map<LocalDate,Double> northbound(SortedMap<LocalDate,Double> source){
        var result=new TreeMap<LocalDate,Double>();YearMonth month=null;double total=0;
        for(var entry:source.entrySet()){var current=YearMonth.from(entry.getKey());if(!current.equals(month)){month=current;total=0;}
            total+=zeroMissing(entry.getValue())/100.0;result.put(entry.getKey(),total);}
        return result;
    }
    /** Excluded partial-market days neither establish a monthly baseline nor produce a change. */
    public static Map<LocalDate,Double> margin(SortedMap<LocalDate,Double> rawBalances,Set<LocalDate> excluded){
        var result=new TreeMap<LocalDate,Double>();YearMonth month=null;double first=Double.NaN;
        for(var entry:rawBalances.entrySet()){var current=YearMonth.from(entry.getKey());if(!current.equals(month)){month=current;first=Double.NaN;}
            double balance=excluded.contains(entry.getKey())?Double.NaN:entry.getValue()/1e8;
            if(!finite(first)&&finite(balance))first=balance;result.put(entry.getKey(),divide(balance,first)-1);}
        return result;
    }
    public static Valuation positiveMedians(double[] pe,double[] pb){
        return new Valuation(quantile(Arrays.stream(pe).filter(v->finite(v)&&v>0).toArray(),.5),
                quantile(Arrays.stream(pb).filter(v->finite(v)&&v>0).toArray(),.5));
    }
    /** pandas rank(pct=True) defaults to average tied ranks, on ALL valuation dates in this source window. */
    public static double[] percentileRanks(double[] values){
        double[] result=new double[values.length];Arrays.fill(result,Double.NaN);
        Integer[] indices=java.util.stream.IntStream.range(0,values.length).filter(i->finite(values[i])).boxed().toArray(Integer[]::new);
        Arrays.sort(indices,Comparator.comparingDouble(i->values[i]));
        for(int start=0;start<indices.length;){int end=start+1;while(end<indices.length&&values[indices[end]]==values[indices[start]])end++;
            double percentile=((start+1)+end)/2.0/indices.length;for(int i=start;i<end;i++)result[indices[i]]=percentile;start=end;}
        return result;
    }
    public static Map<LocalDate,Day> valuation(SortedMap<LocalDate,Valuation> source,Map<LocalDate,Double> gov10Y){
        var entries=new ArrayList<>(source.entrySet());
        var peRanks=percentileRanks(entries.stream().mapToDouble(e->e.getValue().peMedian).toArray());
        var pbRanks=percentileRanks(entries.stream().mapToDouble(e->e.getValue().pbMedian).toArray());
        var result=new TreeMap<LocalDate,Day>();double lastYield=Double.NaN;
        for(int i=0;i<entries.size();i++){
            var entry=entries.get(i);var day=new Day(entry.getKey());
            // Exact valuation-date merge, THEN forward fill. A non-trading yield observation
            // never enters the merged grid; no as-of/backward merge or pre-window bootstrap.
            double observed=gov10Y.getOrDefault(entry.getKey(),Double.NaN);if(finite(observed))lastYield=observed;
            day.put("all_a_pe_ttm_percentile_latest",peRanks[i]);day.put("all_a_pb_percentile_latest",pbRanks[i]);
            day.put("erp_latest",entry.getValue().peMedian>0&&finite(lastYield)?100.0/entry.getValue().peMedian-lastYield:Double.NaN);
            result.put(entry.getKey(),day);
        }
        return result;
    }
    public static SortedMap<LocalDate,Day> merge(List<Day> style,Map<LocalDate,Double> north,Map<LocalDate,Double> margin,Map<LocalDate,Day> valuation){
        var result=new TreeMap<LocalDate,Day>();for(var day:style)result.put(day.date,day);
        north.forEach((date,value)->result.computeIfAbsent(date,Day::new).put("northbound_net_buy_mtd",value));
        margin.forEach((date,value)->result.computeIfAbsent(date,Day::new).put("margin_balance_change_mtd",value));
        valuation.forEach((date,day)->result.computeIfAbsent(date,Day::new).numbers.putAll(day.numbers));return result;
    }
    public static RegimeFeaturesMonitorDailyRow row(Day day,Instant observed){
        return new RegimeFeaturesMonitorDailyRow(day.date.atStartOfDay().toInstant(ZoneOffset.UTC),day.date.toString().substring(0,7).replace("-",""),
                value(day,"hs300_ret_mtd"),value(day,"zz500_ret_mtd"),value(day,"all_a_ret_mtd"),value(day,"cs1000_ret_mtd"),
                value(day,"small_large_ret_mtd"),value(day,"growth_value_ret_mtd"),value(day,"avg_up_down_ratio_5d"),value(day,"avg_limit_up_count_5d"),
                value(day,"avg_limit_down_count_5d"),value(day,"avg_turnover_rate_5d"),value(day,"avg_pct_positive_ratio_5d"),value(day,"avg_pct_negative_ratio_5d"),
                value(day,"northbound_net_buy_mtd"),value(day,"margin_balance_change_mtd"),value(day,"all_a_pe_ttm_percentile_latest"),value(day,"all_a_pb_percentile_latest"),
                value(day,"erp_latest"),"proxy_style",observed);
    }
    private static double zeroMissing(double value){return finite(value)?value:0;}
    private static Double value(Day day,String field){double value=day.get(field);return finite(value)?value:null;}
}
