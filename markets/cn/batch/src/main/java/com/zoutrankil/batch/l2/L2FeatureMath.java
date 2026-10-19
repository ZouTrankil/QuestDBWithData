package com.zoutrankil.batch.l2;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Small numerical operations with the pandas/numpy defaults used by the reference. */
public final class L2FeatureMath {
    public static final long SECOND=1_000, MINUTE=60_000, HOUR=3_600_000;
    public static final long OPEN=9*HOUR+30*MINUTE, AM_END=11*HOUR+30*MINUTE,
            PM_START=13*HOUR, CONT_END=14*HOUR+57*MINUTE, CLOSE=15*HOUR;
    private L2FeatureMath(){}
    public static long time(int raw){return (raw/10_000_000L)*HOUR+(raw/100_000%100)*MINUTE+(raw/1_000%100)*SECOND+raw%1000;}
    public static boolean continuous(long time){return time>=OPEN&&time<=CONT_END;}
    public static boolean isTradingTime(long time){return time>=OPEN&&time<=AM_END||time>=PM_START&&time<=CONT_END;}
    public static double sum(double[] values){double result=0;for(double value:values)if(!Double.isNaN(value))result+=value;return result;}
    /** Match NumPy's eight-accumulator pairwise sum used by pandas Series.sum(). */
    public static double numpySum(double[] values){return numpySum(values,0,values.length);}
    private static double numpySum(double[] v,int start,int length){
        if(length<8){double result=-0.0;for(int i=0;i<length;i++)result+=Double.isNaN(v[start+i])?0:v[start+i];return result;}
        if(length>128){int half=length/2;half-=half%8;return numpySum(v,start,half)+numpySum(v,start+half,length-half);}
        double[] accumulators=new double[8];for(int i=0;i<8;i++)accumulators[i]=Double.isNaN(v[start+i])?0:v[start+i];
        int stop=length-length%8,offset=8;
        for(;offset<stop;offset+=8)for(int i=0;i<8;i++)accumulators[i]+=Double.isNaN(v[start+offset+i])?0:v[start+offset+i];
        double result=((accumulators[0]+accumulators[1])+(accumulators[2]+accumulators[3]))+((accumulators[4]+accumulators[5])+(accumulators[6]+accumulators[7]));
        for(;offset<length;offset++)result+=Double.isNaN(v[start+offset])?0:v[start+offset];return result;
    }
    public static double mean(double[] values){double sum=0;int n=0;for(double value:values)if(!Double.isNaN(value)){sum+=value;n++;}return n==0?Double.NaN:sum/n;}
    public static double std(double[] values,int ddof){double mean=mean(values),s=0;int n=0;for(double value:values)if(!Double.isNaN(value)){double d=value-mean;s+=d*d;n++;}return n<=ddof?Double.NaN:Math.sqrt(s/(n-ddof));}
    public static double median(double[] values){return quantile(values,0.5);}
    public static double quantile(double[] values,double probability){double[] v=Arrays.stream(values).filter(x->!Double.isNaN(x)).sorted().toArray();if(v.length==0)return Double.NaN;double p=(v.length-1)*probability;int lo=(int)Math.floor(p),hi=(int)Math.ceil(p);return v[lo]+(v[hi]-v[lo])*(p-lo);}
    public static double corr(double[] x,double[] y){double sx=0,sy=0;int n=0;for(int i=0;i<Math.min(x.length,y.length);i++)if(!Double.isNaN(x[i])&&!Double.isNaN(y[i])){sx+=x[i];sy+=y[i];n++;}if(n<2)return Double.NaN;double mx=sx/n,my=sy/n,xx=0,yy=0,xy=0;for(int i=0;i<Math.min(x.length,y.length);i++)if(!Double.isNaN(x[i])&&!Double.isNaN(y[i])){double a=x[i]-mx,b=y[i]-my;xx+=a*a;yy+=b*b;xy+=a*b;}return xx==0||yy==0?Double.NaN:xy/Math.sqrt(xx*yy);}
    public static double clip(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    public static double zero(double x){return Double.isFinite(x)?x:0.0;}
    public static <T> double[] values(List<T> rows,ToDoubleFunction<T> fn){return rows.stream().mapToDouble(fn).toArray();}
    public static L2FeatureData.Quote asof(List<L2FeatureData.Quote> rows,long time){int ix=backwardIndex(rows,time);return ix<0?null:rows.get(ix);}
    public static L2FeatureData.Quote forward(List<L2FeatureData.Quote> rows,long time){int ix=forwardIndex(rows,time);return ix==rows.size()?null:rows.get(ix);}
    public static int backwardIndex(List<L2FeatureData.Quote> rows,long time){int lo=0,hi=rows.size();while(lo<hi){int mid=(lo+hi)>>>1;if(rows.get(mid).time()<=time)lo=mid+1;else hi=mid;}return lo-1;}
    public static int forwardIndex(List<L2FeatureData.Quote> rows,long time){int lo=0,hi=rows.size();while(lo<hi){int mid=(lo+hi)>>>1;if(rows.get(mid).time()<time)lo=mid+1;else hi=mid;}return lo;}
}
