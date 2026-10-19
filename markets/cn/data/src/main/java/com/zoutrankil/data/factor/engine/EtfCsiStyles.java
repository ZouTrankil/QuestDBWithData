package com.zoutrankil.data.factor.engine;

import java.util.*;

/** Eight CSI reference styles for one stock/date cross-section after temporal and PIT joins. */
public final class EtfCsiStyles {
    private EtfCsiStyles() {}
    public static Map<String,double[]> compute(int size,Map<String,double[]> input) {
        if(size<0)throw new IllegalArgumentException("Nonnegative section size required");
        for(var values:input.values())if(values.length!=size)throw new IllegalArgumentException("Aligned CSI inputs required");
        var missing=new double[size];Arrays.fill(missing,Double.NaN);
        java.util.function.Function<String,double[]> get=name->input.getOrDefault(name,missing);
        var raw=new LinkedHashMap<String,double[]>();
        raw.put("book",FactorSeries.map(get.apply("pb"),x->x>0?1/x:Double.NaN));
        raw.put("earnings",FactorSeries.map(get.apply("pe_ttm"),x->x>0?1/x:Double.NaN));
        raw.put("dividend",FactorSeries.map(get.apply("dv_ttm"),x->x/100));
        raw.put("cashflow",FactorSeries.combine(get.apply("n_cashflow_act"),get.apply("total_mv"),(a,b)->b>0?a/(b*10000):Double.NaN));
        raw.put("growth_revenue",get.apply("q_gr_yoy"));raw.put("growth_profit",get.apply("q_profit_yoy"));
        raw.put("roe",get.apply("roe"));raw.put("gross_margin",get.apply("grossprofit_margin"));raw.put("operating_cash",get.apply("ocf_to_or"));
        raw.put("leverage",FactorSeries.map(get.apply("debt_to_assets"),x->-x));
        raw.put("low_vol",FactorSeries.map(get.apply("volatility_252"),x->-x));
        raw.put("momentum",FactorSeries.combine(get.apply("return_252"),get.apply("volatility_252"),(a,b)->FactorSeries.ratio(a,b*Math.sqrt(5))));
        raw.put("amount",FactorSeries.map(get.apply("amount_20"),x->Math.log1p(Math.max(0,x))));
        raw.put("turnover",FactorSeries.map(get.apply("turnover_20"),x->Math.log1p(Math.max(0,x))));
        var normalized=new LinkedHashMap<String,double[]>();raw.forEach((name,values)->normalized.put(name,EtfCsiCrossSection.normalize(values)));
        var result=new LinkedHashMap<String,double[]>();
        result.put("csi_value_reference",average(size,normalized,2,"book","earnings","dividend","cashflow"));
        result.put("csi_growth_reference",average(size,normalized,1,"growth_revenue","growth_profit"));
        result.put("csi_dividend_reference",normalized.get("dividend"));
        result.put("csi_quality_reference",average(size,normalized,2,"roe","gross_margin","operating_cash","leverage"));
        result.put("csi_low_vol_reference",normalized.get("low_vol"));result.put("csi_momentum_reference",normalized.get("momentum"));
        result.put("csi_cashflow_reference",normalized.get("cashflow"));
        result.put("csi_liquidity_reference",average(size,normalized,1,"amount","turnover"));
        return Collections.unmodifiableMap(result);
    }
    private static double[] average(int size,Map<String,double[]> normalized,int minimum,String... names) {
        double[] result=new double[size];
        for(int row=0;row<size;row++) {
            double sum=0;int count=0;
            for(String name:names){double value=normalized.get(name)[row];if(!Double.isNaN(value)){sum+=value;count++;}}
            result[row]=count<minimum?Double.NaN:sum/count;
        }
        return result;
    }
}
