package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native projection of Python OrderLifecycleBuilder (P10), including ID reuse aggregation. */
public final class L2LifecycleFeatures {
    private L2LifecycleFeatures() {}
    private record Key(String id,boolean buy) {}
    private static final class Submission {
        long first=Long.MAX_VALUE,cancel=Long.MAX_VALUE;double volume,price,cancelVolume;boolean buy;
    }
    private static final class Fill { long first=Long.MAX_VALUE;double volume,passive; }
    public static Map<String,Object> compute(L2FeatureData data) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("cancel_without_fill_ratio","partial_fill_ratio","median_cancel_time_ms",
                "median_first_fill_time_ms","passive_fill_ratio","large_order_fill_rate","partial_cancel_ratio","replace_like_ratio"))result.put(key,0.0);
        if(data.orders().isEmpty())return result;
        var submitted=new HashMap<String,Submission>();
        // Production input is time sorted: groupby first price/side is the earliest submission.
        for(var row:data.orders())if(row.isAdd()) {
            String id=L2QualityFeatures.normalizeId(row.id());if(id==null)continue;
            var state=submitted.get(id);
            if(state==null){state=new Submission();state.first=row.time();state.price=L2FeatureMath.zero(row.price());state.buy=row.isBuy();submitted.put(id,state);}
            else if(row.time()<state.first){state.first=row.time();state.price=L2FeatureMath.zero(row.price());state.buy=row.isBuy();}
            state.volume+=L2FeatureMath.zero(row.volume());
        }
        if(submitted.isEmpty())return result;
        for(var row:data.orders())if(row.isCancel()) {
            var state=submitted.get(L2QualityFeatures.normalizeId(row.id()));
            if(state!=null){state.cancel=Math.min(state.cancel,row.time());state.cancelVolume+=L2FeatureMath.zero(row.volume());}
        }
        var fills=new HashMap<Key,Fill>();
        for(var row:data.deals())if(row.isTrade()) {
            fill(fills,new Key(L2QualityFeatures.normalizeId(row.buyId()),true),row.time(),row.volume(),row.side()==1);
            fill(fills,new Key(L2QualityFeatures.normalizeId(row.sellId()),false),row.time(),row.volume(),row.side()==0);
        }
        int cancelWithout=0,partialFill=0,partialCancel=0;
        double executed=0,passive=0,largeSubmitted=0,largeFilled=0;
        var cancelLatency=new ArrayList<Double>();var fillLatency=new ArrayList<Double>();
        for(var entry:submitted.entrySet()) {
            var order=entry.getValue();var fill=fills.get(new Key(entry.getKey(),order.buy));
            double volume=Math.max(0,order.volume),execution=fill==null?0:fill.volume,capped=Math.min(volume,execution);
            boolean hasFill=capped>0,hasCancel=order.cancel!=Long.MAX_VALUE;
            if(hasCancel&&!hasFill)cancelWithout++;
            if(hasCancel&&hasFill&&capped<volume)partialFill++;
            if(order.cancelVolume>0&&order.cancelVolume<volume)partialCancel++;
            if(hasCancel&&order.cancel>=order.first)cancelLatency.add((double)(order.cancel-order.first));
            if(fill!=null&&fill.first>=order.first)fillLatency.add((double)(fill.first-order.first));
            executed+=execution;passive+=fill==null?0:fill.passive;
            if(order.volume*order.price>=1_000_000){largeSubmitted+=volume;largeFilled+=capped;}
        }
        double count=submitted.size();
        result.put("cancel_without_fill_ratio",cancelWithout/count);result.put("partial_fill_ratio",partialFill/count);
        result.put("partial_cancel_ratio",partialCancel/count);
        if(!cancelLatency.isEmpty())result.put("median_cancel_time_ms",L2FeatureMath.median(cancelLatency.stream().mapToDouble(Double::doubleValue).toArray()));
        if(!fillLatency.isEmpty())result.put("median_first_fill_time_ms",L2FeatureMath.median(fillLatency.stream().mapToDouble(Double::doubleValue).toArray()));
        if(executed>0)result.put("passive_fill_ratio",passive/executed);
        if(largeSubmitted>0)result.put("large_order_fill_rate",largeFilled/largeSubmitted);
        result.put("replace_like_ratio",replacementRatio(data.orders()));
        return result;
    }
    private static void fill(Map<Key,Fill> fills,Key key,long time,double volume,boolean passive) {
        if(key.id()==null)return;var fill=fills.computeIfAbsent(key,ignored->new Fill());
        fill.first=Math.min(fill.first,time);fill.volume+=L2FeatureMath.zero(volume);
        if(passive)fill.passive+=L2FeatureMath.zero(volume);
    }
    private static double replacementRatio(List<L2FeatureData.Order> orders) {
        long matches=0,count=0;
        for(boolean buy:new boolean[]{true,false}) {
            var adds=L2NumpySort.byTime(orders.stream().filter(row->row.isAdd()&&row.isBuy()==buy).toList(),L2FeatureData.Order::time);
            var cancels=L2NumpySort.byTime(orders.stream().filter(row->row.isCancel()&&row.isBuy()==buy).toList(),L2FeatureData.Order::time);
            if(adds.isEmpty()||cancels.isEmpty())continue;
            int index=0;
            for(var cancel:cancels) {
                count++;while(index<adds.size()&&adds.get(index).time()<cancel.time())index++;
                if(index<adds.size()) {
                    var add=adds.get(index);
                    if(add.time()-cancel.time()<=1000&&Math.abs(add.price()-cancel.price())<=0.02)matches++;
                }
            }
        }
        return count==0?0:matches/(double)count;
    }
}
