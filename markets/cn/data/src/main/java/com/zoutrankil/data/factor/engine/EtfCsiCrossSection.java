package com.zoutrankil.data.factor.engine;

import java.util.*;

/** CSI research normalization: median +/- 5 * 1.4826 MAD, population standard deviation. */
public final class EtfCsiCrossSection {
    private EtfCsiCrossSection() {}
    public static double[] normalize(double[] raw) {
        double[] valid=Arrays.stream(raw).filter(Double::isFinite).sorted().toArray();
        double[] result=new double[raw.length];Arrays.fill(result,Double.NaN);
        if(valid.length==0)return result;
        double median=median(valid);double[] deviations=new double[valid.length];
        for(int i=0;i<valid.length;i++)deviations[i]=Math.abs(valid[i]-median);
        Arrays.sort(deviations);double mad=median(deviations);
        double low=Double.NEGATIVE_INFINITY,high=Double.POSITIVE_INFINITY;
        if(mad>1e-12){low=median-5*1.4826*mad;high=median+5*1.4826*mad;}
        double mean=0,m2=0;int count=0;
        for(double value:valid){double x=Math.max(low,Math.min(high,value));count++;double delta=x-mean;mean+=delta/count;m2+=delta*(x-mean);}
        double std=Math.sqrt(m2/count);if(!Double.isFinite(std)||std<=1e-12)return result;
        for(int i=0;i<raw.length;i++)if(Double.isFinite(raw[i]))result[i]=(Math.max(low,Math.min(high,raw[i]))-mean)/std;
        return result;
    }
    public static double[] composite(List<double[]> rawColumns,int minimum) {
        if(rawColumns.isEmpty() || minimum<1 || minimum>rawColumns.size())throw new IllegalArgumentException("Valid CSI composite inputs required");
        int n=rawColumns.getFirst().length;double[] sums=new double[n],result=new double[n];int[] counts=new int[n];
        for(var column:rawColumns) {
            if(column.length!=n)throw new IllegalArgumentException("Aligned CSI cross-section required");
            var normalized=normalize(column);
            for(int i=0;i<n;i++)if(!Double.isNaN(normalized[i])){sums[i]+=normalized[i];counts[i]++;}
        }
        for(int i=0;i<n;i++)result[i]=counts[i]<minimum?Double.NaN:sums[i]/counts[i];
        return result;
    }
    private static double median(double[] sorted) {
        int n=sorted.length;return n%2==1?sorted[n/2]:(sorted[n/2-1]+sorted[n/2])/2;
    }
}
