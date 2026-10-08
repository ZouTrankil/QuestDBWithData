package com.zoutrankil.batch.l2;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Canonical, price-in-CNY input for the native daily L2 feature builders. */
public record L2FeatureData(String symbol, LocalDate date, List<Deal> deals,
                            List<Order> orders, List<Quote> quotes) {
    public L2FeatureData {
        Objects.requireNonNull(symbol); Objects.requireNonNull(date);
        deals=List.copyOf(deals); orders=List.copyOf(orders); quotes=List.copyOf(quotes);
    }
    /** time is milliseconds since midnight; side uses the Python Kuake codes. */
    public record Deal(long time,double price,double volume,int side,String dealId,String buyId,String sellId) {
        public double amount(){return price*volume;}
        public int direction(){return side==0?1:side==1?-1:0;}
        public boolean isTrade(){return side==0||side==1;}
    }
    public record Order(long time,double price,double volume,int type,String id,boolean nearTouch) {
        public boolean isAdd(){return type>=0&&type<=3||type>=10&&type<=13;}
        public boolean isCancel(){return type==-1||type==-11;}
        public boolean isBuy(){return type>=0&&type<=3||type==-1;}
        public boolean isSell(){return type>=10&&type<=13||type==-11;}
        public double amount(){return price*volume;}
    }
    /** The ten depth arrays use zero-based level indexes. */
    public record Quote(long time,int rawTime,double tickTimeDiff,double price,double volume,double turnover,
                        double totalVolume,double totalTurnover,double totalBidVolume,double totalAskVolume,
                        double weightedBidPrice,double weightedAskPrice,double[] bidPrice,double[] askPrice,
                        double[] bidVolume,double[] askVolume) {
        public double mid(){return (bidPrice[0]+askPrice[0])/2;}
        public double depth1(){return bidVolume[0]+askVolume[0];}
        public double depth5(){double n=0;for(int i=0;i<5;i++)n+=bidVolume[i]+askVolume[i];return n;}
    }
    /** P0/P1/P2 enrich the same clean-trade rows before later builders use them. */
    public static final class Wide {
        public final Deal deal;
        public final Quote quote;
        public final double aggression, interArrivalMs;
        public final boolean continuous;
        public boolean algo, wash;
        public Wide(Deal deal,Quote quote,double aggression,double interArrivalMs,boolean continuous){
            this.deal=deal;this.quote=quote;this.aggression=aggression;
            this.interArrivalMs=interArrivalMs;this.continuous=continuous;
        }
        public boolean clean(){return !wash;}
        public long window5(){return deal.time()/300_000*300_000;}
        public long window10(){return deal.time()/10_000*10_000;}
    }
}
