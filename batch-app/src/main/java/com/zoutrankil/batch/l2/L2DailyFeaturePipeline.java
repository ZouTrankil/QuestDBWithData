package com.zoutrankil.batch.l2;

import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** Native DFCF CSV -> Python-compatible P0-P13 -> typed l2_daily_features computation. */
public final class L2DailyFeaturePipeline {
    public static final String FEATURE_VERSION="v1.3", PARSER_VERSION="dfcf_csv_v1.2";
    private L2DailyFeaturePipeline(){}

    public static L2DailyFeatures processSymbolDate(Path symbolDirectory,String symbol,LocalDate date,
                                                   long maxBytesPerFile) throws IOException {
        return compute(DfcfCsvParser.readProductionDay(symbolDirectory,symbol,date,maxBytesPerFile));
    }
    public static L2DailyFeatures compute(DfcfCsvParser.ProductionDay day) throws IOException {
        L2FeatureData data=featureData(day);
        var wide=L2WideFeatures.compute(data);
        var features=new LinkedHashMap<String,Object>(wide.features());
        merge(features,L2SpoofingFeatures.compute(data));
        merge(features,L2QualityFeatures.compute(data,wide.wide()));
        merge(features,L2LifecycleFeatures.compute(data));
        merge(features,L2IntradayFeatures.compute(data));
        merge(features,L2TradeSignFeatures.compute(data));
        merge(features,L2LobTransitionFeatures.compute(data));
        merge(features,L2MicrostructureFeatures.compute(data));
        merge(features,L2GmmFeatures.compute(data));
        merge(features,L2IntensityFeatures.compute(data,wide.wide()));
        features.put("feature_version",FEATURE_VERSION);features.put("parser_version",PARSER_VERSION);
        var typed=new EnumMap<L2DailyFeatureField,Object>(L2DailyFeatureField.class);
        for(var field:L2DailyFeatureField.values())if(!field.identity())typed.put(field,features.remove(field.fieldName()));
        if(!features.isEmpty())throw new IOException("Daily feature builder emitted unknown fields: "+features.keySet());
        return new L2DailyFeatures(day.tradeDate(),day.symbol(),typed);
    }
    private static void merge(Map<String,Object> target,Map<String,Object> values){
        for(var entry:values.entrySet()){
            if(target.containsKey(entry.getKey()))throw new IllegalStateException("Duplicate daily feature owner: "+entry.getKey());
            target.put(entry.getKey(),entry.getValue());
        }
    }
    public static Map<String,Object> output(L2DailyFeatures row){
        var output=new LinkedHashMap<String,Object>();
        output.put("ts",row.tradeDate().toString().replace("-",""));output.put("symbol",row.symbol());
        for(var field:L2DailyFeatureField.values())if(!field.identity())output.put(field.fieldName(),row.features().get(field));
        return Collections.unmodifiableMap(output);
    }
    /** Convert canonical parser records and apply the Data-owned order-normalizer flags. */
    public static L2FeatureData featureData(DfcfCsvParser.ProductionDay day) throws IOException {
        var quotes=new ArrayList<L2FeatureData.Quote>(day.quotes().size());
        for(var q:day.quotes()){
            double[] bp=new double[10],ap=new double[10],bv=new double[10],av=new double[10];
            for(int i=0;i<10;i++){var level=q.levels().get(i);bp[i]=price(level.bidPriceCny());ap[i]=price(level.askPriceCny());bv[i]=number(level.bidVolume());av[i]=number(level.askVolume());}
            quotes.add(new L2FeatureData.Quote(time(day.tradeDate(),q.time()),q.time(),q.tickTimeDiff(),price(q.priceCny()),number(q.volume()),number(q.sourceTurnover()),
                    number(q.totalVolume()),number(q.sourceTotalTurnover()),number(q.totalBidVolume()),number(q.totalAskVolume()),price(q.weightedBidPriceCny()),price(q.weightedAskPriceCny()),bp,ap,bv,av));
        }
        var deals=new ArrayList<L2FeatureData.Deal>(day.deals().size());
        for(var d:day.deals()){
            int side=switch(d.side()){case BUY->0;case SELL->1;case BUY_CANCEL->-1;case SELL_CANCEL->-11;case UNKNOWN->throw new IOException("Unknown DEAL in production day");};
            deals.add(new L2FeatureData.Deal(time(day.tradeDate(),d.time()),price(d.priceCny()),number(d.volume()),side,d.dealId(),d.buyOrderId(),d.sellOrderId()));
        }
        var orders=new ArrayList<L2FeatureData.Order>(day.orders().size());
        for(var o:day.orders()){
            if(o.kuakeOrderType()==null)throw new IOException("Unknown ORDER in production day");
            long t=time(day.tradeDate(),o.time());double price=price(o.priceCny());int type=o.kuakeOrderType();
            orders.add(new L2FeatureData.Order(t,price,number(o.volume()),type,o.orderId(),false));
        }
        if(!quotes.isEmpty()){
            List<L2FeatureData.Quote> quoteKeys=L2NumpySort.byTime(quotes,L2FeatureData.Quote::time);
            List<L2FeatureData.Order> sortedOrders=L2NumpySort.byTime(orders,L2FeatureData.Order::time);
            orders=new ArrayList<>(sortedOrders.size());
            for(var order:sortedOrders){
                var q=L2FeatureMath.asof(quoteKeys,order.time());
                boolean near=q!=null&&q.bidPrice()[0]>0&&q.askPrice()[0]>0&&
                        (order.isBuy()&&order.price()>=q.bidPrice()[0]-0.01||order.isSell()&&order.price()<=q.askPrice()[0]+0.01);
                orders.add(new L2FeatureData.Order(order.time(),order.price(),order.volume(),order.type(),order.id(),near));
            }
        }
        return new L2FeatureData(day.symbol(),day.tradeDate(),deals,orders,quotes);
    }
    private static double number(BigDecimal value){return value==null?Double.NaN:value.doubleValue();}
    // The Python adapter divides vendor prices by 100 and Kuake divides them by 100 again.
    // Preserve both floating operations: a one-step /10000 changes near-touch comparisons.
    private static double price(BigDecimal value){return value==null?Double.NaN:value.movePointRight(4).doubleValue()/100.0/100.0;}
    private static long time(LocalDate date,int value) throws IOException {
        var ts=DfcfCsvParser.timestamp(date,value);
        if(ts==null)throw new IOException("Invalid timestamp cannot enter the daily feature pipeline: "+value);
        return Duration.between(date.atStartOfDay(),ts).toMillis();
    }
}
