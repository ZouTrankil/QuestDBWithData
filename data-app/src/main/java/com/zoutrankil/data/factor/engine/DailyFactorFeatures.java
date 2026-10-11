package com.zoutrankil.data.factor.engine;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import static com.zoutrankil.data.factor.engine.FactorSeries.*;

/**
 * Per-security daily feature kernel, matching factor_platform_daily_polars.py.
 * Input must contain the full warmup and strictly ordered, unique trading dates.
 * Cross-sectional preprocessing and publication are separate stages; this class
 * alone does not produce a publishable factor observation.
 */
public final class DailyFactorFeatures {
    private DailyFactorFeatures() {}

    public static Map<String, double[]> compute(LocalDate[] dates, Map<String, double[]> input) {
        Objects.requireNonNull(dates);
        for (int i = 0; i < dates.length; i++) {
            Objects.requireNonNull(dates[i]);
            if (i > 0 && !dates[i].isAfter(dates[i - 1]))
                throw new IllegalArgumentException("Dates must be unique and increasing for one security");
        }
        for (var entry : input.entrySet()) {
            if (entry.getValue().length != dates.length) throw new IllegalArgumentException("Misaligned " + entry.getKey());
            for (double value : entry.getValue())
                if (Double.isInfinite(value)) throw new IllegalArgumentException("Infinite input: " + entry.getKey());
        }
        var out = new LinkedHashMap<String, double[]>();
        double[] price = required(input, "close"), amount = required(input, "amount");
        double[] filled = forwardFill(price), turnover = required(input, "turnover_rate");
        out.put("price", price.clone());
        for (int horizon : new int[]{1, 3, 5, 20, 60, 120})
            out.put("ret_" + horizon, combine(filled, shift(filled, horizon), (a, b) -> a / b - 1));
        double[] ret = out.get("ret_1");
        out.put("ret", ret.clone());
        for (int[] spec : new int[][]{{20,10},{60,30},{120,60},{252,126}})
            out.put("vol_" + spec[0], rolling(ret, spec[0], spec[1], true));
        double[] downside = map(ret, v -> Math.min(v, 0));
        out.put("downside_vol_60", rolling(downside, 60, 30, true));
        out.put("downside_vol_252", rolling(downside, 252, 126, true));
        for (int[] spec : new int[][]{{3,1},{20,10},{60,30}})
            out.put("amount_mean_" + spec[0], rolling(amount, spec[0], spec[1], false));
        out.put("turnover_21", rolling(turnover, 21, 15, false));
        out.put("turnover_252", rolling(turnover, 252, 120, false));
        out.put("abturnover", combine(out.get("turnover_21"), out.get("turnover_252"), FactorSeries::ratio));
        out.put("liq_shock", combine(amount, out.get("amount_mean_20"), FactorSeries::ratio));
        for (String name : new String[]{"total_mv", "circ_mv"})
            out.put("log_" + name, map(required(input, name), v -> v > 0 ? Math.log(v) : Double.NaN));
        for (String[] pair : new String[][]{{"pb","book_to_price"},{"pe_ttm","earnings_yield"},{"ps_ttm","ps_yield"}})
            out.put(pair[1], map(required(input, pair[0]), v -> ratio(1, v)));
        for (int[] spec : new int[][]{{252,21},{126,21},{252,42}}) {
            String name = spec[0] == 126 ? "momentum_6_1" : spec[1] == 42 ? "momentum_12_2" : "momentum_12_1";
            out.put(name, combine(shift(price, spec[1]), shift(price, spec[0]), (a,b) -> a / b - 1));
        }
        out.put("momentum_12m", out.get("momentum_12_1").clone());
        double[] amihud = combine(ret, amount, (r,a) -> a > 0 ? Math.abs(r) / a : Double.NaN);
        out.put("amihud_20", rolling(amihud,20,10,false));
        double[] delta = combine(price, shift(price,1), (a,b) -> a-b);
        double[] gain = rolling(map(delta,v -> Math.max(v,0)),14,7,false);
        double[] loss = rolling(map(delta,v -> -Math.min(v,0)),14,7,false);
        out.put("rsi_strength",combine(gain,loss,(g,l) -> ((100-100/(1+g/(l+1e-12)))-50)/50));
        double[] ma5 = rolling(price,5,3,false), ma20 = rolling(price,20,10,false);
        out.put("ma_trend_strength",combine(ma5,ma20,(a,b) -> ratio(a,b)-1));
        out.put("price_acceleration",combine(out.get("ret_5"),out.get("ret_20"),(a,b) -> a-b));
        double[] ema12=ema(price,12), ema26=ema(price,26);
        double[] macd=combine(ema12,ema26,(a,b) -> a-b), signal=ema(macd,9);
        out.put("macd_strength",combine(combine(macd,signal,(a,b) -> a-b),ema26,FactorSeries::ratio));
        double[] std20=rolling(price,20,10,true);
        double[] lower=combine(ma20,std20,(a,b) -> a-2*b);
        out.put("bollinger_position",combine(combine(price,lower,(a,b) -> a-b),std20,
                (a,b) -> Math.min(1,Math.max(0,ratio(a,4*b)))));
        // The caller resolves the Python panel-wide volume/amount fallback before grouping.
        double[] volume=required(input,"effective_volume");
        double[] vm=combine(rolling(volume,5,3,false),rolling(volume,20,10,false),(a,b) -> ratio(a,b)-1);
        out.put("volume_momentum",vm);
        out.put("price_volume_divergence",combine(out.get("ret_20"),vm,(a,b) -> a-b));
        out.put("rotation_composite",combine(combine(out.get("rsi_strength"),out.get("ret_20"),(a,b) -> .3*a+.4*b),vm,(a,b) -> a+.3*b));
        if(input.containsKey("open")) out.put("open_price",input.get("open").clone());
        if(input.containsKey("up_limit") && input.containsKey("down_limit")) {
            double[] up=input.get("up_limit"),down=input.get("down_limit");
            out.put("distance_to_up_limit",combine(up,price,(a,b)->ratio(a,b)-1));
            out.put("distance_to_down_limit",combine(price,down,(a,b)->ratio(a,b)-1));
            if(input.containsKey("high"))
                out.put("limit_touch_up",combine(input.get("high"),up,(a,b)->a>=b*(1-1e-6)?1:0));
            if(input.containsKey("low"))
                out.put("limit_touch_down",combine(input.get("low"),down,(a,b)->a<=b*(1+1e-6)?1:0));
        }
        if(input.containsKey("is_st") && input.containsKey("is_suspended")) {
            double[] tradable=new double[dates.length];
            for(int i=0;i<dates.length;i++) tradable[i]=input.get("is_st")[i]==0
                    && input.get("is_suspended")[i]==0 && amount[i]>0 ? 1:0;
            out.put("is_tradable",tradable);
        }
        return Collections.unmodifiableMap(out);
    }

    private static double[] required(Map<String,double[]> input,String name) {
        double[] values=input.get(name);
        if(values==null) throw new IllegalArgumentException("Explicit input column required (NaN allowed): " + name);
        return values;
    }
}
