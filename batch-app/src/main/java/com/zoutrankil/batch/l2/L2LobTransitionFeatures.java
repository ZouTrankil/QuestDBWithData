package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.zoutrankil.batch.l2.L2FeatureData.Deal;
import com.zoutrankil.batch.l2.L2FeatureData.Order;
import com.zoutrankil.batch.l2.L2FeatureData.Quote;
import static com.zoutrankil.batch.l2.L2MicrostructureFeatures.*;

/** Native P13 LOB transition approximations using all snapshots, as the research code does. */
public final class L2LobTransitionFeatures {
    private L2LobTransitionFeatures() {}
    public static Map<String,Object> compute(L2FeatureData data) {
        Map<String,Object> out=zeros("depth_recovery_5s","best_quote_depletion_rate",
            "add_cancel_execute_ratio_top1","depth_turnover_top5","book_pressure_decay","queue_depletion_speed");
        List<Quote> snapshots=L2NumpySort.byTime(data.quotes(),Quote::time);
        if(!snapshots.isEmpty()) {
            List<Quote> depthQuotes=new ArrayList<>();List<Double> top5Depth=new ArrayList<>(),pressure=new ArrayList<>(),changes=new ArrayList<>();
            long depletionValid=0,depleted=0;
            for(int i=0;i<snapshots.size();i++) {
                Quote q=snapshots.get(i);double bid=top5(q.bidVolume()),ask=top5(q.askVolume()),d5=bid+ask;
                top5Depth.add(d5);
                double bp=d5==0?Double.NaN:(bid-ask)/d5;
                if(!Double.isNaN(bp))pressure.add(bp);
                if(!Double.isNaN(depth(q)))depthQuotes.add(q);
                if(i>0) {
                    Quote prev=snapshots.get(i-1);double pb=prev.bidVolume()[0],pa=prev.askVolume()[0];
                    changes.add(Math.abs(d5-top5Depth.get(i-1)));
                    if(pb>0||pa>0) {depletionValid++;if((pb>0&&q.bidVolume()[0]<=pb*.2)||(pa>0&&q.askVolume()[0]<=pa*.2))depleted++;}
                }
            }
            if(depletionValid>0)out.put("best_quote_depletion_rate",(double)depleted/depletionValid);
            double md=mean(top5Depth);if(md>0)out.put("depth_turnover_top5",pandasMean(changes)/md);
            if(pressure.size()>=3)out.put("book_pressure_decay",pressureDecay(pressure));
            List<Double> recovery=new ArrayList<>(),speed=new ArrayList<>();
            List<Quote> futureDepth=L2NumpySort.byTime(depthQuotes,Quote::time);
            for(int i=1;i<depthQuotes.size();i++) {
                Quote q=depthQuotes.get(i),prev=depthQuotes.get(i-1);
                double pd=depth(prev),d=depth(q),drop=pd-d;
                if(drop>0) {
                    double seconds=(q.time()-prev.time())/1000.0;
                    speed.add(pd==0||seconds==0?Double.NaN:drop/pd/seconds);
                    if(snapshots.size()>=3&&pd>0) {
                        Quote future=forward(futureDepth,q.time()+5_000);
                        if(future!=null)recovery.add(Math.max(0,Math.min(1,(depth(future)-d)/drop)));
                    }
                }
            }
            out.put("depth_recovery_5s",mean(recovery));out.put("queue_depletion_speed",mean(speed));
        }
        double addVolume=0,cancelVolume=0,executeVolume=0;
        for(Order o:data.orders())if(o.nearTouch()) {
            double v=Double.isNaN(o.volume())?0:o.volume();if(o.isAdd())addVolume+=v;if(o.isCancel())cancelVolume+=v;
        }
        List<Quote> executionQuotes=L2NumpySort.byTime(snapshots,Quote::time);
        for(Deal d:data.deals()) {
            if(Double.isNaN(d.price()))continue;Quote q=backward(executionQuotes,d.time());
            if(q!=null&&q.bidPrice()[0]>0&&q.askPrice()[0]>0&&(d.price()>=q.askPrice()[0]||d.price()<=q.bidPrice()[0]))
                executeVolume+=Double.isNaN(d.volume())?0:d.volume();
        }
        if(cancelVolume+executeVolume>0)out.put("add_cancel_execute_ratio_top1",addVolume/(cancelVolume+executeVolume));
        return out;
    }
    private static double pressureDecay(List<Double> values) {
        // pandas constant-series autocorrelation is NaN and the builder returns zero decay.
        for(double x:values)if(!Double.isFinite(x))return 0;
        double first=values.get(0),last=values.get(values.size()-1);boolean constX=true,constY=true;
        for(int i=1;i<values.size();i++){constX&=values.get(i-1)==first;constY&=values.get(i)==last;}
        return constX||constY?0:Math.max(0,Math.min(2,1-lagCorrelation(values)));
    }
    private static double pandasMean(List<Double> values) {
        double sum=0;int n=0;for(double x:values)if(!Double.isNaN(x)){sum+=x;n++;}
        return n==0?Double.NaN:sum/n;
    }
}
