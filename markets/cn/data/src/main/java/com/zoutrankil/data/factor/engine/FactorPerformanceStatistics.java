package com.zoutrankil.data.factor.engine;

import java.util.Arrays;

/** Numeric primitives for factor_performance._window_row; no database or publication side effects. */
public final class FactorPerformanceStatistics {
    private FactorPerformanceStatistics() {}
    public record Summary(int observations,Double mean,Double sampleStandardDeviation,
                          Double icir,Double hacTStat,Double positiveRate,Double cumulative) {}

    /** NaN is absent. Infinite inputs are invalid formal evidence rather than usable observations. */
    public static Summary summarize(double[] values) {
        if(values==null || values.length>10000)throw new IllegalArgumentException("Bounded metric series required");
        for(double value:values)if(Double.isInfinite(value))throw new IllegalArgumentException("Infinite daily metric");
        double[] clean=Arrays.stream(values).filter(Double::isFinite).toArray();
        int count=clean.length;
        // pandas sum of an empty numeric series is zero; mean/win rate remain missing.
        if(count==0)return new Summary(0,null,null,null,null,null,0.0);
        double sum=sum(clean),mean=sum/count;int positive=0;
        double[] centered=new double[count];
        for(int i=0;i<count;i++) { centered[i]=clean[i]-mean;if(clean[i]>0)positive++; }
        double square=dot(centered,0,centered,0,count);
        double standardDeviation=count>1?Math.sqrt(square/(count-1)):Double.NaN;
        Double icir=standardDeviation>0?finite(mean/standardDeviation):null;
        Double hac=null;
        if(count>=3) {
            int lag=Math.min(count-1,Math.max(1,(int)(4*Math.pow(count/100.0,2.0/9.0))));
            double variance=square/count;
            for(int offset=1;offset<=lag;offset++) {
                double covariance=dot(centered,offset,centered,0,count-offset)/count;
                variance+=2*(1-offset/(lag+1.0))*covariance;
            }
            if(variance>0)hac=finite(mean/Math.sqrt(variance/count));
        }
        return new Summary(count,finite(mean),finite(standardDeviation),icir,hac,
                positive/(double)count,finite(sum));
    }
    private static Double finite(double value) { return Double.isFinite(value)?value:null; }
    private static double dot(double[] a,int startA,double[] b,int startB,int count) {
        double total=0,correction=0;
        for(int i=0;i<count;i++) {
            double term=a[startA+i]*b[startB+i]-correction;
            double next=total+term;correction=(next-total)-term;total=next;
        }
        return total;
    }
    private static double sum(double[] values) {
        double total=0,correction=0;
        for(double value:values) {
            double term=value-correction,next=total+term;
            correction=(next-total)-term;total=next;
        }
        return total;
    }
}
