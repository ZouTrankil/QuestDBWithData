package com.zoutrankil.data.factor.engine;

import java.util.*;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.SingularValueDecomposition;

/** Python ETF residual momentum: standardized beta, style dummies, ridge alpha 10, unpenalized intercept. */
public final class EtfResidualMomentum {
    private EtfResidualMomentum() {}
    public static double[] compute(double[] returns20,double[] beta60,String[] categories) {
        int n=returns20.length;
        if(beta60.length!=n || categories.length!=n)throw new IllegalArgumentException("Aligned ETF cross-section required");
        double[] output=new double[n];Arrays.fill(output,Double.NaN);var selected=new ArrayList<Integer>();
        var styles=new TreeSet<String>();
        for(int i=0;i<n;i++)if(!Double.isNaN(returns20[i]) && !Double.isNaN(beta60[i])) {
            selected.add(i);styles.add(categories[i]==null?"其他":categories[i]);
        }
        if(selected.size()<12)return output;
        for(int i:selected)if(!Double.isFinite(returns20[i]) || !Double.isFinite(beta60[i]))
            throw new IllegalArgumentException("Nonfinite ETF regression input");
        var labels=List.copyOf(styles);int width=labels.size()+1;
        double mean=0;for(int i:selected)mean+=beta60[i];mean/=selected.size();
        double variance=0;for(int i:selected)variance+=(beta60[i]-mean)*(beta60[i]-mean);
        double std=Math.sqrt(variance/selected.size());if(std==0)std=1;
        double[][] x=new double[selected.size()][width];double[] y=new double[selected.size()];
        for(int row=0;row<selected.size();row++) {
            int i=selected.get(row);x[row][0]=1;x[row][1]=(beta60[i]-mean)/std;y[row]=returns20[i];
            int style=Collections.binarySearch(labels,categories[i]==null?"其他":categories[i]);
            if(style>0)x[row][style+1]=1;
        }
        var matrix=new Array2DRowRealMatrix(x,false);var transpose=matrix.transpose();
        var gram=transpose.multiply(matrix);for(int i=1;i<width;i++)gram.addToEntry(i,i,10);
        var svd=new SingularValueDecomposition(gram);var singular=svd.getSingularValues();
        // Match numpy pinv's default relative cutoff rather than Commons Math's solver threshold.
        var inverse=new Array2DRowRealMatrix(width,width);
        for(int i=0;i<singular.length;i++)if(singular[i]>singular[0]*1e-15)inverse.setEntry(i,i,1/singular[i]);
        var coefficients=svd.getV().multiply(inverse).multiply(svd.getUT()).operate(transpose.operate(y));
        var fitted=matrix.operate(coefficients);
        for(int i=0;i<selected.size();i++)output[selected.get(i)]=y[i]-fitted[i];
        return output;
    }
}
