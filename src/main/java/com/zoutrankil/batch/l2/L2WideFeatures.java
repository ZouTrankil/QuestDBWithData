package com.zoutrankil.batch.l2;

import java.util.*;
import static com.zoutrankil.batch.l2.L2FeatureMath.*;
import static com.zoutrankil.batch.l2.L2FeatureData.*;

/** P0/P1/P2/P4/P5, in the same enrichment order as KuakeSyncPipeline. */
public final class L2WideFeatures {
    public record Result(List<Wide> wide,Map<String,Object> features){}
    private record EntropyKey(long window,int direction){}
    private L2WideFeatures(){}
    public static Result compute(L2FeatureData data){
        List<Wide> wide=new ArrayList<>();
        long[] previous={-1,-1};
        List<Deal> trades=L2NumpySort.byTime(data.deals().stream().filter(Deal::isTrade).toList(),Deal::time);
        List<Quote> sortedQuotes=L2NumpySort.byTime(data.quotes(),Quote::time);
        for(Deal deal:trades){
            Quote quote=asof(sortedQuotes,deal.time());
            double aggression=Double.NaN;
            if(quote!=null&&quote.bidPrice()[0]>0&&quote.askPrice()[0]>0&&quote.askPrice()[0]>quote.bidPrice()[0])
                aggression=clip((deal.price()-quote.bidPrice()[0])/(quote.askPrice()[0]-quote.bidPrice()[0]),0,1);
            int direction=deal.side();
            double gap=previous[direction]<0?Double.NaN:deal.time()-previous[direction];
            previous[direction]=deal.time();
            wide.add(new Wide(deal,quote,aggression,gap,continuous(deal.time())));
        }
        var out=new LinkedHashMap<String,Object>();
        double ag=mean(wide.stream().mapToDouble(w->w.aggression).filter(Double::isFinite).toArray());
        if(Double.isNaN(ag)){
            double total=wide.stream().mapToDouble(w->w.deal.amount()).sum();
            double buy=wide.stream().filter(w->w.deal.side()==0).mapToDouble(w->w.deal.amount()).sum();
            ag=wide.isEmpty()?0:total>0?buy/total:0.5;
        }
        out.put("mean_rel_aggro",zero(ag));
        out.put("median_inter_arrival_ms",zero(median(wide.stream().mapToDouble(w->w.interArrivalMs).toArray())));
        entropy(wide,out);wash(wide,data.quotes(),out);flow(wide,out);funds(wide,out);
        return new Result(List.copyOf(wide),out);
    }
    private static void entropy(List<Wide> wide,Map<String,Object> out){
        var groups=new LinkedHashMap<EntropyKey,List<Wide>>();
        for(Wide row:wide)if(row.continuous&&!Double.isNaN(row.interArrivalMs))
            groups.computeIfAbsent(new EntropyKey(row.window5(),row.deal.direction()),ignored->new ArrayList<>()).add(row);
        double[] edges={0,1,5,10,50,100,500,1000,Double.POSITIVE_INFINITY};
        var algoKeys=new HashSet<EntropyKey>();
        int total=0,algo=0;double algoEntropy=0,retailEntropy=0,algoAmount=0,retailAmount=0;
        for(var entry:groups.entrySet()){
            if(entry.getValue().size()<3)continue;
            long[] counts=new long[8];long n=0;
            for(Wide row:entry.getValue())for(int i=0;i<8;i++)if(row.interArrivalMs>=edges[i]&&row.interArrivalMs<edges[i+1]){counts[i]++;n++;break;}
            if(n==0)continue;
            double h=0;for(long c:counts)if(c>0){double p=(double)c/n;h-=p*Math.log(p)/Math.log(2);}
            double amount=entry.getValue().stream().mapToDouble(w->w.deal.amount()).sum();
            total++;
            if(h<1.5){algo++;algoEntropy+=h;algoAmount+=amount;algoKeys.add(entry.getKey());}
            else{retailEntropy+=h;retailAmount+=amount;}
        }
        for(Wide row:wide)row.algo=algoKeys.contains(new EntropyKey(row.window5(),row.deal.direction()));
        out.put("algo_windows",(long)algo);out.put("total_windows",(long)total);
        out.put("mean_algo_entropy",algo==0?0:algoEntropy/algo);
        out.put("mean_retail_entropy",total==algo?0:retailEntropy/(total-algo));
        out.put("algo_total_amount",algoAmount);out.put("retail_total_amount",retailAmount);
    }
    private static void wash(List<Wide> wide,List<Quote> quotes,Map<String,Object> out){
        var volume=new HashMap<Long,Double>();
        for(Wide row:wide)if(row.continuous)volume.merge(row.window10(),row.deal.volume(),Double::sum);
        var depths=new HashMap<Long,double[]>();
        for(Quote quote:quotes){long key=quote.time()/10_000*10_000;double d=quote.depth1();double[] range=depths.get(key);if(range==null)depths.put(key,new double[]{d,d});else range[1]=d;}
        double median=median(volume.values().stream().mapToDouble(Double::doubleValue).toArray());
        var washed=new HashSet<Long>();
        for(var entry:volume.entrySet()){
            double[] depth=depths.get(entry.getKey());double v=entry.getValue();
            if(depth!=null&&v!=0&&Math.abs(depth[1]-depth[0])/v<1e-6&&v>median)washed.add(entry.getKey());
        }
        double amount=0,washAmount=0;
        for(Wide row:wide){row.wash=row.continuous&&washed.contains(row.window10());amount+=row.deal.amount();if(row.wash)washAmount+=row.deal.amount();}
        out.put("wash_trade_ratio",amount>0?washAmount/amount:0.0);out.put("wash_amount",washAmount);out.put("clean_amount",amount-washAmount);
    }
    private static void flow(List<Wide> wide,Map<String,Object> out){
        var groups=new TreeMap<Long,List<Wide>>();
        double netTotal=0;
        for(Wide row:wide)if(row.clean()){netTotal+=row.deal.amount()*row.deal.direction();groups.computeIfAbsent(row.window5(),ignored->new ArrayList<>()).add(row);}
        long[] q=new long[4];double q2=0;var skews=new ArrayList<Double>();
        for(List<Wide> rows:groups.values()){
            double net=0,amount=0,vol=0;
            for(Wide row:rows){net+=row.deal.amount()*row.deal.direction();amount+=row.deal.amount();vol+=row.deal.volume();}
            // At constant auction prices the standard deviation can be near machine precision;
            // preserve pandas' pairwise monetary sum so the VWAP numerator has the same rounding.
            amount=numpySum(rows.stream().mapToDouble(w->w.deal.amount()).toArray());
            vol=numpySum(rows.stream().mapToDouble(w->w.deal.volume()).toArray());
            double delta=rows.getLast().deal.price()-rows.getFirst().deal.price();
            if(net>0){if(delta>0)q[0]++;else if(delta<=0){q[1]++;q2+=net;}}
            else if(net<0){if(delta<0)q[2]++;else if(delta>=0)q[3]++;}
            double[] price=rows.stream().mapToDouble(w->w.deal.price()).toArray();double std=std(price,1);
            if(std!=0)skews.add(std>0?(amount/vol-median(price))/std:0.0);
        }
        out.put("total_net_flow",netTotal);out.put("q2_accumulation",q2);
        out.put("mean_vwap_skew",zero(mean(skews.stream().mapToDouble(Double::doubleValue).toArray())));
        for(int i=0;i<4;i++)out.put("q"+(i+1)+"_count",q[i]);
    }
    private static void funds(List<Wide> wide,Map<String,Object> out){
        double buy=0,sell=0,retailBuy=0,retailSell=0,midBuy=0,midSell=0,open=0,close=0;
        int clean=0,large=0;var amounts=new ArrayList<Double>();
        for(Wide row:wide)if(row.clean()){
            Deal deal=row.deal;double amount=deal.amount();clean++;amounts.add(amount);
            boolean big=amount>=1_000_000||deal.volume()>=100_000;
            if(big){large++;if(deal.side()==0)buy+=amount;else sell+=amount;}
            else if(deal.side()==0)retailBuy+=amount;else retailSell+=amount;
            if(amount>=200_000&&amount<1_000_000){if(deal.side()==0)midBuy+=amount;else midSell+=amount;}
            if(deal.time()<OPEN)open+=amount*deal.direction();
            if(deal.time()>CONT_END)close+=amount*deal.direction();
        }
        double[] sorted=amounts.stream().mapToDouble(Double::doubleValue).filter(v->!Double.isNaN(v)).sorted().toArray();
        double total=sum(sorted),weighted=0;
        for(int i=0;i<sorted.length;i++)weighted+=(2.0*(i+1)-sorted.length-1)*sorted[i];
        double gini=sorted.length>1&&total>0?weighted/(sorted.length*total):0;
        out.put("main_net_inflow",buy-sell);out.put("retail_funds_net_inflow",retailBuy-retailSell);
        out.put("main_funds_buy_amount",buy);out.put("main_funds_sell_amount",sell);
        out.put("mid_tier_net_inflow",midBuy-midSell);out.put("trade_size_gini",gini);
        out.put("open_auction_net_inflow",open);out.put("close_auction_net_inflow",close);
        out.put("total_records",(long)wide.size());out.put("clean_records",(long)clean);out.put("large_order_records",(long)large);
    }
}
