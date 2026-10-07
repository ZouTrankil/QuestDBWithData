package com.zoutrankil.batch.l2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native projection of Python SpoofingFeatureBuilder (P3). */
public final class L2SpoofingFeatures {
    private L2SpoofingFeatures() {}
    public static Map<String,Object> compute(L2FeatureData data) {
        var result=new LinkedHashMap<String,Object>();
        result.put("spoof_count",0);result.put("fake_pressure_count",0);result.put("fake_support_count",0);
        result.put("mean_sell_otr",0.0);result.put("mean_buy_otr",0.0);result.put("mean_cancel_distance_ticks",0.0);
        var cancels=data.orders().stream().filter(L2FeatureData.Order::isCancel).toList();
        if(data.orders().isEmpty()||cancels.isEmpty()||data.deals().isEmpty())return result;
        // [buy cancel, sell cancel, buy trade, sell trade] for the outer minute/side join.
        var minutes=new TreeMap<Long,double[]>();
        for(var row:cancels)minutes.computeIfAbsent(row.time()/60_000,ignored->new double[4])[row.isBuy()?0:1]+=row.volume();
        for(var row:data.deals())if(row.isTrade())minutes.computeIfAbsent(row.time()/60_000,ignored->new double[4])[row.side()==0?2:3]+=row.volume();
        double sumBuy=0,sumSell=0;int nBuy=0,nSell=0,pressure=0,support=0;
        for(var values:minutes.values()) {
            double buy=values[2]==0?Double.NaN:values[0]/values[2],sell=values[3]==0?Double.NaN:values[1]/values[3];
            if(!Double.isNaN(buy)){sumBuy+=buy;nBuy++;}if(!Double.isNaN(sell)){sumSell+=sell;nSell++;}
            // pivot_table excludes rows with only NA values, then fills remaining absent sides with 0.
            if(Double.isNaN(buy)&&Double.isNaN(sell))continue;
            if(Double.isNaN(buy))buy=0;if(Double.isNaN(sell))sell=0;
            if(sell>5&&buy<2)pressure++;else if(buy>5&&sell<2)support++;
        }
        result.put("mean_buy_otr",nBuy==0?0.0:sumBuy/nBuy);result.put("mean_sell_otr",nSell==0?0.0:sumSell/nSell);
        result.put("fake_pressure_count",pressure);result.put("fake_support_count",support);result.put("spoof_count",pressure+support);
        double distance=0;int valid=0;
        var sortedQuotes=L2NumpySort.byTime(data.quotes(),L2FeatureData.Quote::time);
        for(var row:cancels)if(row.price()!=0&&!Double.isNaN(row.price())) {
            var quote=L2FeatureMath.asof(sortedQuotes,row.time());if(quote==null)continue;
            double dist=(row.isBuy()?quote.bidPrice()[0]-row.price():row.price()-quote.askPrice()[0])/0.01;
            if(Double.isFinite(dist)){distance+=dist;valid++;}
        }
        if(valid>0)result.put("mean_cancel_distance_ticks",distance/valid);
        return result;
    }
}
