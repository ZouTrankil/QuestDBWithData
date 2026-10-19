package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.zoutrankil.batch.l2.L2FeatureData.Deal;
import com.zoutrankil.batch.l2.L2FeatureData.Quote;
import static com.zoutrankil.batch.l2.L2MicrostructureFeatures.*;

/** Native P12 vendor-label validation with the same inside-spread tick-rule fallback. */
public final class L2TradeSignFeatures {
    private L2TradeSignFeatures() {}
    public static Map<String,Object> compute(L2FeatureData data) {
        Map<String,Object> out=zeros("aggressor_label_match_ratio","mid_cross_match_ratio",
            "tick_rule_fallback_ratio","unclassified_trade_ratio");
        List<Quote> quotes=data.quotes(); if(data.deals().isEmpty()||quotes.isEmpty())return out;
        // The pandas builder filters first, then uses its default datetime quicksort.
        // Tie order determines the tick sign carried through same-millisecond trades.
        List<Deal> eligible=new ArrayList<>();
        for(Deal d:data.deals())if((d.side()==0||d.side()==1)&&!Double.isNaN(d.price()))eligible.add(d);
        List<Deal> deals=L2NumpySort.byTime(eligible,Deal::time);
        quotes=L2NumpySort.byTime(quotes,Quote::time);
        long total=0,classified=0,matched=0,fallback=0,fallbackClassified=0,fallbackMatched=0;
        double previousPrice=Double.NaN,tick=Double.NaN;
        for(Deal d:deals) {
            total++;
            if(!Double.isNaN(previousPrice)) {double diff=d.price()-previousPrice;if(diff!=0&&!Double.isNaN(diff))tick=Math.signum(diff);}
            previousPrice=d.price();
            Quote q=backward(quotes,d.time());double implied=Double.NaN;boolean usedTick=false;
            if(q!=null&&validQuote(q)) {
                if(d.price()>=q.askPrice()[0])implied=1;
                else if(d.price()<=q.bidPrice()[0])implied=-1;
                else {usedTick=true;implied=tick;}
            }
            if(usedTick)fallback++;
            if(implied==1||implied==-1) {
                classified++;boolean match=(d.side()==0?1.0:-1.0)==implied;if(match)matched++;
                if(usedTick) {fallbackClassified++;if(match)fallbackMatched++;}
            }
        }
        if(total==0)return out;
        if(classified>0)out.put("aggressor_label_match_ratio",(double)matched/classified);
        if(fallbackClassified>0)out.put("mid_cross_match_ratio",(double)fallbackMatched/fallbackClassified);
        out.put("tick_rule_fallback_ratio",(double)fallback/total);
        out.put("unclassified_trade_ratio",(double)(total-classified)/total);return out;
    }
}
