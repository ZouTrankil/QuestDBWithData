package com.zoutrankil.data.factor.engine;

import java.util.Arrays;

/** One frozen date/factor/universe group, never a batch-wide normalization. */
public final class FactorCrossSection {
    private FactorCrossSection() {}
    public record Result(double[] values, int sampleCount, double clippedFraction) {}

    public static Result normalize(double[] raw, String policy) {
        double[] valid=Arrays.stream(raw).filter(Double::isFinite).sorted().toArray();
        double[] output=new double[raw.length];
        Arrays.fill(output,Double.NaN);
        if (!policy.equals("none") && !policy.equals("rank") && !policy.equals("robust_zscore"))
            throw new IllegalArgumentException("Unsupported normalization: " + policy);
        if (policy.equals("none")) {
            for(int i=0;i<raw.length;i++) if(Double.isFinite(raw[i])) output[i]=raw[i];
            return new Result(output,valid.length,Double.NaN);
        }
        if (policy.equals("rank")) {
            for(int i=0;i<raw.length;i++) if(Double.isFinite(raw[i])) {
                int low=lowerBound(valid,raw[i]), high=upperBound(valid,raw[i]);
                output[i]=(low+1.0+high)/(2.0*valid.length);
            }
            return new Result(output,valid.length,Double.NaN);
        }
        if(valid.length<3 || valid[0]==valid[valid.length-1]) return new Result(output,valid.length,0);
        double low=quantile(valid,.01), high=quantile(valid,.99), mean=0,m2=0;
        int count=0,clipped=0;
        for(double value:valid) {
            if(value<low || value>high) clipped++;
            double x=Math.max(low,Math.min(high,value));
            count++;double delta=x-mean;mean+=delta/count;m2+=delta*(x-mean);
        }
        double std=Math.sqrt(m2/count);
        if(!Double.isFinite(std)||std<=0) return new Result(output,count,0);
        for(int i=0;i<raw.length;i++) if(Double.isFinite(raw[i]))
            output[i]=(Math.max(low,Math.min(high,raw[i]))-mean)/std;
        return new Result(output,count,(double)clipped/count);
    }

    private static double quantile(double[] sorted,double probability) {
        double position=(sorted.length-1)*probability;
        int low=(int)Math.floor(position), high=(int)Math.ceil(position);
        return sorted[low]+(position-low)*(sorted[high]-sorted[low]);
    }
    private static int lowerBound(double[] values,double target) {
        int low=0,high=values.length;
        while(low<high){int mid=(low+high)>>>1;if(values[mid]<target)low=mid+1;else high=mid;}
        return low;
    }
    private static int upperBound(double[] values,double target) {
        int low=0,high=values.length;
        while(low<high){int mid=(low+high)>>>1;if(values[mid]<=target)low=mid+1;else high=mid;}
        return low;
    }
}
