package com.zoutrankil.data.factor.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import static com.zoutrankil.data.factor.engine.FactorSeries.*;

/** Market-return inputs are aligned to each security's own observed date sequence. */
public final class DailyMarketRiskFeatures {
    private DailyMarketRiskFeatures() {}

    public static Map<String,double[]> compute(double[] returns,double[] marketReturns) {
        if(returns.length!=marketReturns.length) throw new IllegalArgumentException("Misaligned market returns");
        double[] stockMean=rolling(returns,252,60,false);
        double[] marketMean=rolling(marketReturns,252,60,false);
        double[] productMean=rolling(combine(returns,marketReturns,(a,b)->a*b),252,60,false);
        double[] marketSquareMean=rolling(map(marketReturns,a->a*a),252,60,false);
        double[] variance=combine(marketSquareMean,marketMean,(a,b)->a-b*b);
        double[] covariance=combine(productMean,combine(stockMean,marketMean,(a,b)->a*b),(a,b)->a-b);
        double[] beta=combine(covariance,variance,FactorSeries::ratio);
        double[] residual=combine(returns,combine(beta,marketReturns,(a,b)->a*b),(a,b)->a-b);
        var output=new LinkedHashMap<String,double[]>();
        output.put("market_return",marketReturns.clone());
        output.put("market_beta_252",beta);
        output.put("residual_vol_252",rolling(residual,252,60,true));
        return Map.copyOf(output);
    }
}
