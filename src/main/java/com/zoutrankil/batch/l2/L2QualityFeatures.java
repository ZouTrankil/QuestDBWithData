package com.zoutrankil.batch.l2;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native projection of Python DataQualityFeatureBuilder (P9). */
public final class L2QualityFeatures {
    private L2QualityFeatures() {}
    public static Map<String,Object> compute(L2FeatureData data,List<L2FeatureData.Wide> wide) {
        var result=new LinkedHashMap<String,Object>();
        result.put("has_deal",!data.deals().isEmpty());result.put("has_order",!data.orders().isEmpty());
        result.put("has_snapshot",!data.quotes().isEmpty());result.put("deal_records",data.deals().size());
        result.put("order_records",data.orders().size());result.put("snapshot_records",data.quotes().size());
        for(String name:List.of("continuous_auction_ratio","crossed_quote_ratio","locked_quote_ratio",
                "missing_top5_quote_ratio","order_link_coverage","deal_order_match_ratio",
                "nonnegative_depth_ratio","valid_spread_ratio","orphan_execution_ratio"))result.put(name,0.0);
        if(!wide.isEmpty())result.put("continuous_auction_ratio",wide.stream().filter(row->row.continuous).count()/(double)wide.size());
        var orderIds=new HashSet<String>();int linked=0;
        for(var row:data.orders()){String id=normalizeId(row.id());if(id!=null){orderIds.add(id);linked++;}}
        if(!data.orders().isEmpty())result.put("order_link_coverage",linked/(double)data.orders().size());
        long tradeCount=0,matched=0;
        for(var row:data.deals())if(row.isTrade()) {
            tradeCount++;
            if(orderIds.contains(normalizeId(row.buyId()))||orderIds.contains(normalizeId(row.sellId())))matched++;
        }
        if(tradeCount>0){result.put("deal_order_match_ratio",matched/(double)tradeCount);result.put("orphan_execution_ratio",(tradeCount-matched)/(double)tradeCount);}
        if(data.quotes().isEmpty())return result;
        int crossed=0,locked=0,valid=0,missing=0,nonnegative=0;
        for(var row:data.quotes()) {
            double bid=row.bidPrice()[0],ask=row.askPrice()[0];
            if(bid>0&&ask>0){if(bid>ask)crossed++;if(bid==ask)locked++;if(ask>bid)valid++;}
            boolean missingPrice=false,validDepth=true;
            for(int i=0;i<5;i++) {
                if(Double.isNaN(row.bidPrice()[i])||Double.isNaN(row.askPrice()[i])||row.bidPrice()[i]<=0||row.askPrice()[i]<=0)missingPrice=true;
                if(Double.isNaN(row.bidVolume()[i])||Double.isNaN(row.askVolume()[i])||row.bidVolume()[i]<0||row.askVolume()[i]<0)validDepth=false;
            }
            if(missingPrice)missing++;if(validDepth)nonnegative++;
        }
        double count=data.quotes().size();
        result.put("crossed_quote_ratio",crossed/count);result.put("locked_quote_ratio",locked/count);
        result.put("valid_spread_ratio",valid/count);result.put("missing_top5_quote_ratio",missing/count);
        result.put("nonnegative_depth_ratio",nonnegative/count);
        return result;
    }
    static String normalizeId(String value) {
        if(value==null)return null;String text=value.strip();
        if(text.endsWith(".0"))text=text.substring(0,text.length()-2);
        return text.isEmpty()?null:text;
    }
}
