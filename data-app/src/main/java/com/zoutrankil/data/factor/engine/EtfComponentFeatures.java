package com.zoutrankil.data.factor.engine;

import java.util.*;

/** One ETF/date aggregation after frozen constituent joins; each field owns its valid-weight denominator. */
public final class EtfComponentFeatures {
    private EtfComponentFeatures() {}
    public record Stock(String symbol,double weight,double return1,double return5) {}
    public record L2(double weight,double inflow,double mfi,double obi1,double obi5,double ofi,double spread,double depth) {}
    public static Map<String,Double> compute(List<Stock> stocks,List<L2> l2,double[] fullMembershipWeights,boolean l2SourcePresent) {
        if(stocks.isEmpty())throw new IllegalArgumentException("No stock observations: omit this ETF/date instead");
        var output=new LinkedHashMap<String,Double>();double up=0,valid=0;var returns=new ArrayList<Double>();var symbols=new HashSet<String>();
        for(var stock:stocks) {
            weight(stock.weight());symbols.add(Objects.requireNonNull(stock.symbol()));
            if(!Double.isNaN(stock.return1())) { valid+=stock.weight();if(stock.return1()>0)up+=stock.weight(); }
            if(!Double.isNaN(stock.return5()))returns.add(stock.return5());
        }
        output.put("component_up_weight",up);output.put("component_valid_weight",valid);
        output.put("component_up_breadth_1d",positiveRatio(up,valid));
        output.put("component_median_return_5d",median(returns));
        double mean=returns.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),squared=0;
        for(double value:returns)squared+=(value-mean)*(value-mean);
        output.put("component_return_dispersion_5d",returns.size()<2?Double.NaN:Math.sqrt(squared/(returns.size()-1)));
        output.put("component_count",(double)symbols.size());
        var weights=fullMembershipWeights.clone();for(double value:weights)weight(value);Arrays.sort(weights);
        double top=0;for(int i=Math.max(0,weights.length-10);i<weights.length;i++)top+=weights[i];
        output.put("component_top10_weight",top);
        if(l2SourcePresent) {
            double[] sums=new double[6],denominators=new double[6];var mfi=new ArrayList<Double>();
            for(var row:l2) {
                weight(row.weight());double[] values={row.inflow(),row.obi1(),row.obi5(),row.ofi(),row.spread(),row.depth()};
                for(int i=0;i<values.length;i++)if(!Double.isNaN(values[i])) {
                    denominators[i]+=row.weight();double term=row.weight()*(i==0?(values[i]>0?1:0):values[i]);
                    if(!Double.isNaN(term))sums[i]+=term;
                }
                if(!Double.isNaN(row.mfi()))mfi.add(row.mfi());
            }
            String[] names={"component_l2_inflow_breadth","basket_obi1","basket_obi5","basket_ofi","basket_spread","basket_depth"};
            for(int i=0;i<names.length;i++)output.put(names[i],positiveRatio(sums[i],denominators[i]));
            output.put("component_l2_mfi_median",median(mfi));
        }
        return Collections.unmodifiableMap(output);
    }
    private static double positiveRatio(double numerator,double denominator) {return denominator>0?numerator/denominator:Double.NaN;}
    private static void weight(double value) {if(!Double.isFinite(value) || value<0)throw new IllegalArgumentException("Normalized nonnegative finite component weight required");}
    private static double median(List<Double> values) {
        values.sort(Double::compare);int n=values.size();
        return n==0?Double.NaN:n%2==1?values.get(n/2):(values.get(n/2-1)+values.get(n/2))/2;
    }
}
