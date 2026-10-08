package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.zoutrankil.batch.l2.L2FeatureData.Deal;
import com.zoutrankil.batch.l2.L2FeatureData.Order;
import com.zoutrankil.batch.l2.L2FeatureData.Quote;

/** Native P6 calculations, including the Python builders' quote filtering and horizon joins. */
public final class L2MicrostructureFeatures {
    private L2MicrostructureFeatures() {}

    public static Map<String,Object> compute(L2FeatureData data) {
        Map<String,Object> out = zeros("mean_spread", "mean_obi_top5", "amihud_illiquidity",
            "obi_top1_mean", "obi_top5_std", "obi_top1_std", "depth_top1_mean",
            "depth_top5_mean", "depth_slope", "depth_convexity", "microprice_minus_mid_mean",
            "microprice_minus_mid_std", "effective_spread_mean", "realized_spread_1m",
            "impact_30s", "impact_5m", "orderbook_replenish_speed", "ofi_mean", "ofi_std",
            "ofi_persistence", "ofi_positive_ratio", "ofi_negative_ratio", "near_touch_cancel_add_ratio");
        List<Quote> snapshots = data.quotes();
        if (snapshots.isEmpty()) return out;
        List<Quote> valid = validQuotes(snapshots);
        List<Double> spreads = new ArrayList<>(), obi1 = new ArrayList<>(), obi5 = new ArrayList<>(),
            depth1 = new ArrayList<>(), depth5 = new ArrayList<>(), shifts = new ArrayList<>(),
            slopes = new ArrayList<>(), convexities = new ArrayList<>();
        for (Quote q : valid) {
            double bid = q.bidPrice()[0], ask = q.askPrice()[0];
            double bv = q.bidVolume()[0], av = q.askVolume()[0], depth = bv + av;
            double mid = (bid + ask) / 2.0;
            spreads.add((ask - bid) / bid);
            obi1.add(depth == 0 ? Double.NaN : (bv - av) / depth);
            depth1.add(depth);
            shifts.add(depth == 0 ? Double.NaN : ((ask * bv + bid * av) / depth - mid) / mid);
            double bd5 = top5(q.bidVolume()), ad5 = top5(q.askVolume()), d5 = bd5 + ad5;
            obi5.add(d5 == 0 ? Double.NaN : (bd5 - ad5) / d5);
            depth5.add(d5);
            double[] levels = new double[5];
            boolean finite = true;
            for (int i=0;i<5;i++) {
                levels[i] = (q.bidVolume()[i] + q.askVolume()[i]) / 2.0;
                finite &= Double.isFinite(levels[i]);
            }
            if (finite && levels[0] > 0) {
                slopes.add((-2*levels[0] - levels[1] + levels[3] + 2*levels[4]) / 10 / levels[0]);
                double curvature = 0;
                for (int i=0;i<3;i++) curvature += levels[i+2] - 2*levels[i+1] + levels[i];
                convexities.add(curvature / 3 / levels[0]);
            }
        }
        out.put("mean_spread", mean(spreads)); out.put("mean_obi_top5", mean(obi5));
        out.put("obi_top1_mean", mean(obi1)); out.put("obi_top1_std", std(obi1));
        out.put("obi_top5_std", std(obi5)); out.put("depth_top1_mean", mean(depth1));
        out.put("depth_top5_mean", mean(depth5)); out.put("depth_slope", mean(slopes));
        out.put("depth_convexity", mean(convexities));
        out.put("microprice_minus_mid_mean", mean(shifts)); out.put("microprice_minus_mid_std", std(shifts));

        List<Double> ofi = new ArrayList<>(snapshots.size());
        for (int i=0;i<snapshots.size();i++) {
            Quote q = snapshots.get(i);
            double value = 0;
            if (i > 0 && validQuote(q)) {
                Quote prev = snapshots.get(i-1);
                value = q.bidVolume()[0] - prev.bidVolume()[0] - (q.askVolume()[0] - prev.askVolume()[0]);
                if (Double.isNaN(value)) value = 0;
            }
            ofi.add(value);
        }
        out.put("ofi_mean", mean(ofi)); out.put("ofi_std", std(ofi));
        if (ofi.size() > 1) {
            out.put("ofi_persistence", lagCorrelation(ofi));
            int positive = 0, negative = 0, nonzero = 0;
            for (double x : ofi) if (x != 0) {nonzero++; if (x>0) positive++; if (x<0) negative++;}
            if (nonzero > 0) {
                out.put("ofi_positive_ratio", (double)positive/nonzero);
                out.put("ofi_negative_ratio", (double)negative/nonzero);
            }
        }
        long nearAdds=0, nearCancels=0;
        for (Order o : data.orders()) if (o.nearTouch()) {if(o.isAdd()) nearAdds++; if(o.isCancel()) nearCancels++;}
        if(nearAdds>0) out.put("near_touch_cancel_add_ratio", (double)nearCancels/nearAdds);

        double amount = 0, open = 0, close = 0;
        for (Deal d : data.deals()) {
            double a=d.amount(); if(Double.isFinite(a) && a>0) amount += a;
            if(Double.isFinite(d.price()) && d.price()>0) {if(open==0) open=d.price(); close=d.price();}
        }
        if(open==0) for(Quote q:snapshots) if(Double.isFinite(q.price()) && q.price()>0) {
            if(open==0) open=q.price(); close=q.price();
        }
        if(open>0 && amount>0) out.put("amihud_illiquidity", Math.abs(close-open)/open/(amount/1_000_000_000));
        List<Quote> backwardValid=L2NumpySort.byTime(valid,Quote::time);
        List<Quote> futureValid=L2NumpySort.byTime(validQuotes(L2NumpySort.byTime(snapshots,Quote::time)),Quote::time);
        List<Double> effective=new ArrayList<>(), realized=new ArrayList<>(), impact30=new ArrayList<>(), impact300=new ArrayList<>();
        for(Deal d:data.deals()) {
            Quote q=backward(backwardValid,d.time()); if(q==null) continue;
            double m=mid(q), direction=d.side()==0 ? 1.0 : -1.0;
            effective.add(2*Math.abs(d.price()-m)/m);
            Quote f30=forward(futureValid,d.time()+30_000), f60=forward(futureValid,d.time()+60_000), f300=forward(futureValid,d.time()+300_000);
            if(f30!=null) impact30.add(direction*(mid(f30)-m)/m);
            if(f60!=null) realized.add(2*direction*(d.price()-mid(f60))/m);
            if(f300!=null) impact300.add(direction*(mid(f300)-m)/m);
        }
        out.put("effective_spread_mean",mean(effective)); out.put("realized_spread_1m",mean(realized));
        out.put("impact_30s",mean(impact30)); out.put("impact_5m",mean(impact300));
        List<Quote> depthQuotes=new ArrayList<>();
        for(Quote q:valid) if(!Double.isNaN(depth(q))) depthQuotes.add(q);
        depthQuotes=L2NumpySort.byTime(depthQuotes,Quote::time);
        if(depthQuotes.size()>=3) {
            List<Double> recovery=new ArrayList<>();
            List<Quote> futureDepth=L2NumpySort.byTime(depthQuotes,Quote::time);
            for(int i=1;i<depthQuotes.size();i++) {
                Quote q=depthQuotes.get(i); double previous=depth(depthQuotes.get(i-1)), current=depth(q);
                if(current<previous && previous>0) {
                    Quote future=forward(futureDepth,q.time()+30_000);
                    if(future!=null) recovery.add((depth(future)-current)/previous);
                }
            }
            out.put("orderbook_replenish_speed",mean(recovery)/30.0);
        }
        return out;
    }

    static Map<String,Object> zeros(String... keys) {
        Map<String,Object> out=new LinkedHashMap<>(); for(String key:keys) out.put(key,0.0); return out;
    }
    static boolean validQuote(Quote q) {return q.bidPrice()[0]>0 && q.askPrice()[0]>0 && q.askPrice()[0]>q.bidPrice()[0];}
    static List<Quote> validQuotes(List<Quote> snapshots) {
        List<Quote> out=new ArrayList<>(); for(Quote q:snapshots) if(validQuote(q)) out.add(q); return out;
    }
    static double top5(double[] levels) {double total=0; for(int i=0;i<Math.min(5,levels.length);i++) if(!Double.isNaN(levels[i])) total+=levels[i]; return total;}
    static double mid(Quote q) {return (q.bidPrice()[0]+q.askPrice()[0])/2.0;}
    static double depth(Quote q) {return q.bidVolume()[0]+q.askVolume()[0];}
    static Quote backward(List<Quote> rows,long time) {
        int lo=0,hi=rows.size(); while(lo<hi) {int m=(lo+hi)>>>1;if(rows.get(m).time()<=time) lo=m+1;else hi=m;}
        return lo==0 ? null : rows.get(lo-1);
    }
    static Quote forward(List<Quote> rows,long time) {
        int lo=0,hi=rows.size(); while(lo<hi) {int m=(lo+hi)>>>1;if(rows.get(m).time()<time) lo=m+1;else hi=m;}
        return lo==rows.size() ? null : rows.get(lo);
    }
    static double mean(List<Double> values) {double total=0;int n=0;for(double x:values) if(Double.isFinite(x)) {total+=x;n++;} return n==0 ? 0:total/n;}
    static double std(List<Double> values) {double m=mean(values),ss=0;int n=0;for(double x:values) if(Double.isFinite(x)) {ss+=(x-m)*(x-m);n++;}return n==0 ? 0:Math.sqrt(ss/n);}
    static double lagCorrelation(List<Double> values) {
        double sx=0,sy=0;int n=0;
        for(int i=1;i<values.size();i++) {double x=values.get(i-1),y=values.get(i);if(Double.isInfinite(x)||Double.isInfinite(y))return 0;if(Double.isFinite(x)&&Double.isFinite(y)){sx+=x;sy+=y;n++;}}
        if(n==0)return 0;double mx=sx/n,my=sy/n,xx=0,yy=0,xy=0;
        for(int i=1;i<values.size();i++) {double x=values.get(i-1),y=values.get(i);if(Double.isFinite(x)&&Double.isFinite(y)){x-=mx;y-=my;xx+=x*x;yy+=y*y;xy+=x*y;}}
        return xx==0||yy==0 ? 0:xy/Math.sqrt(xx*yy);
    }
}
