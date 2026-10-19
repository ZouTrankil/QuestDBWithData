package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.zoutrankil.batch.l2.L2FeatureData.Deal;
import com.zoutrankil.batch.l2.L2FeatureData.Quote;
import static com.zoutrankil.batch.l2.L2MicrostructureFeatures.*;

/** Native P11 intraday segment summaries; segment endpoints match the pandas builder. */
public final class L2IntradayFeatures {
    private L2IntradayFeatures() {}
    private static final long OPEN=34_200_000, TEN=36_000_000, MORNING_END=41_400_000,
        AFTERNOON=46_800_000, CLOSE_START=52_200_000, CLOSE=54_000_000;

    public static Map<String,Object> compute(L2FeatureData data) {
        Map<String,Object> out=zeros("open_30m_spread_mean","close_30m_obi_mean","midday_liquidity_drop",
            "afternoon_ofi_reversal","open_close_vol_ratio","close_30m_impact_mean");
        List<Double> openSpread=new ArrayList<>(),closeObi=new ArrayList<>(),openDepth=new ArrayList<>(),
            middayDepth=new ArrayList<>(),morningOfi=new ArrayList<>(),afternoonOfi=new ArrayList<>();
        List<Quote> quotes=L2NumpySort.byTime(data.quotes(),Quote::time);
        List<Quote> valid=L2NumpySort.byTime(validQuotes(quotes),Quote::time);
        for(int i=0;i<quotes.size();i++) {
            Quote q=quotes.get(i);long t=q.time();boolean good=validQuote(q);
            double bv=top5(q.bidVolume()),av=top5(q.askVolume()),d=bv+av;
            double ofi=0;
            if(i>0 && good) {
                Quote prev=quotes.get(i-1);
                ofi=q.bidVolume()[0]-prev.bidVolume()[0]-(q.askVolume()[0]-prev.askVolume()[0]);
                if(Double.isNaN(ofi))ofi=0;
            }
            if(t>=OPEN&&t<TEN&&good) {openSpread.add((q.askPrice()[0]-q.bidPrice()[0])/q.bidPrice()[0]);openDepth.add(d);}
            if(t>=CLOSE_START&&t<=CLOSE&&good) closeObi.add(d==0?Double.NaN:(bv-av)/d);
            if(t>=TEN&&t<CLOSE_START&&good) middayDepth.add(d);
            if(t>=OPEN&&t<=MORNING_END) morningOfi.add(ofi);
            if(t>=AFTERNOON&&t<=CLOSE) afternoonOfi.add(ofi);
        }
        out.put("open_30m_spread_mean",mean(openSpread));out.put("close_30m_obi_mean",mean(closeObi));
        double od=mean(openDepth),md=mean(middayDepth),mo=mean(morningOfi),ao=mean(afternoonOfi);
        if(od>0&&md>0)out.put("midday_liquidity_drop",(od-md)/od);
        if(mo!=0&&ao!=0)out.put("afternoon_ofi_reversal",Math.signum(mo)!=Math.signum(ao)?1.0:0.0);
        double openVolume=0,closeVolume=0;List<Double> impact=new ArrayList<>();
        for(Deal d:data.deals()) {
            long t=d.time();double volume=Double.isNaN(d.volume())?0:d.volume();
            if(t>=OPEN&&t<TEN)openVolume+=volume;
            if(t>=CLOSE_START&&t<=CLOSE) {
                closeVolume+=volume;
                if(Double.isNaN(d.price()))continue;
                Quote q=backward(valid,t),future=forward(valid,t+30_000);
                if(q!=null&&future!=null)impact.add((d.side()==0?1.0:-1.0)*(mid(future)-mid(q))/mid(q));
            }
        }
        if(closeVolume>0)out.put("open_close_vol_ratio",openVolume/closeVolume);
        out.put("close_30m_impact_mean",mean(impact));return out;
    }
}
