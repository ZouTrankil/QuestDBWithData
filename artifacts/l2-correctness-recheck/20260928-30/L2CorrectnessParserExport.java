package com.zoutrankil.batch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.zip.GZIPOutputStream;

public final class L2CorrectnessParserExport {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),output=Path.of(args[1]);
        for(String dateText:args[2].split(",")) for(String symbol:new String[]{"000001.SZ","600000.SH","300750.SZ","688981.SH","510300.SZ","159915.SZ","588000.SZ"}) {
            var day=DfcfCsvParser.readProductionDay(root.resolve(dateText).resolve(symbol),symbol,LocalDate.parse(dateText.substring(0,4)+"-"+dateText.substring(4,6)+"-"+dateText.substring(6,8)),1_000_000_000L);
            Path target=output.resolve(dateText).resolve(symbol);Files.createDirectories(target);
            try(var out=writer(target.resolve("deal.csv.gz"))) {
                out.write("DealTime,Side,Price,Volume,BuyID,SellID,DealID\n");
                for(var row:day.deals())write(out,row.time(),side(row.side()),row.priceCny(),row.volume(),row.buyOrderId(),row.sellOrderId(),row.dealId());
            }
            try(var out=writer(target.resolve("order.csv.gz"))) {
                out.write("OrderTime,OrderType,Price,Volume,OrderID\n");
                for(var row:day.orders())write(out,row.time(),row.kuakeOrderType(),row.priceCny(),row.volume(),row.orderId());
            }
            try(var out=writer(target.resolve("snapshot.csv.gz"))) {
                out.write("TickTime,TickTimeDiff,Price,Volume,Turnover,TotalVolume,TotalTurnover,TotalBidVolume,TotalAskVolume,WeightBidPrice,WeightAskPrice");
                for(int i=1;i<=10;i++)out.write(",BidPrice"+i+",BidVolume"+i+",AskPrice"+i+",AskVolume"+i);out.write("\n");
                for(var row:day.quotes()) {
                    Object[] values=new Object[51];int i=0;
                    values[i++]=row.time();values[i++]=row.tickTimeDiff();values[i++]=row.priceCny();values[i++]=row.volume();values[i++]=row.sourceTurnover();values[i++]=row.totalVolume();values[i++]=row.sourceTotalTurnover();values[i++]=row.totalBidVolume();values[i++]=row.totalAskVolume();values[i++]=row.weightedBidPriceCny();values[i++]=row.weightedAskPriceCny();
                    for(var level:row.levels()){values[i++]=level.bidPriceCny();values[i++]=level.bidVolume();values[i++]=level.askPriceCny();values[i++]=level.askVolume();}
                    write(out,values);
                }
            }
            System.out.println(dateText+" "+symbol+" "+day.symbol()+" "+day.deals().size()+" "+day.orders().size()+" "+day.quotes().size());
        }
    }
    private static BufferedWriter writer(Path path) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)),StandardCharsets.UTF_8),65536);
    }
    private static void write(BufferedWriter out,Object... values)throws IOException {
        for(int i=0;i<values.length;i++){if(i>0)out.write(',');if(values[i]!=null)out.write(values[i].toString());}out.write('\n');
    }
    private static int side(DfcfCsvParser.DealSide side) {
        return switch(side){case BUY->0;case SELL->1;case BUY_CANCEL->-1;case SELL_CANCEL->-11;case UNKNOWN->throw new IllegalStateException();};
    }
}
