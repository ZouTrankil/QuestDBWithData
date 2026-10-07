package com.zoutrankil.data.domain.policy;

import java.time.LocalDate;
import java.util.*;

/** Native numerical implementation of market_sentiment_daily_v2_rule_pca_20260427.
 * The legacy full-window winsorization and PCA fit are preserved: this is a rebuild
 * through the supplied end date, and does not promise historical point-in-time PCA.
 */
public final class MarketSentimentDailyCalculation {
    private MarketSentimentDailyCalculation() {}
    public static final String MODEL_VERSION="market_sentiment_daily_v2_rule_pca_20260427";
    public record Stock(String code,double close,double previous,double amount,double turnover,
                        double circMv,double totalMv,double pb,double upLimit,double downLimit,
                        double distance,int streak,boolean previousLimit) {
        double ret(){return divide(close,previous)-1;}
        boolean up(){return close>previous;}
        boolean down(){return close<previous;}
        boolean limitUp(){return finite(upLimit)&&Math.abs(close-upLimit)<=1e-6;}
        boolean limitDown(){return finite(downLimit)&&Math.abs(close-downLimit)<=1e-6;}
    }
    public static final class Day {
        public final LocalDate date;
        public final Map<String,Double> numbers=new LinkedHashMap<>();
        public final Map<String,Boolean> flags=new LinkedHashMap<>();
        public String state;
        public Day(LocalDate date){this.date=Objects.requireNonNull(date);}
        public double get(String name){return numbers.getOrDefault(name,Double.NaN);}
        public void put(String name,double value){numbers.put(name,finite(value)?value:Double.NaN);}
        public boolean flag(String name){return flags.getOrDefault(name,false);}
    }
    /** pandas rolling(20,min_periods=10) after ST/suspended filtering, per stock. */
    public static final class StockHistory {
        private final double[] close=new double[20]; private int size,cursor,streak; private boolean previousLimit;
        public Stock prepare(String code,double value,double previous,double amount,double turnover,
                             double circMv,double totalMv,double pb,double upLimit,double downLimit){
            close[cursor]=value;cursor=(cursor+1)%20;size=Math.min(20,size+1);
            double sum=0;int valid=0;for(int i=0;i<size;i++)if(finite(close[i])){sum+=close[i];valid++;}
            double ma=valid>=10?sum/valid:Double.NaN;
            boolean limit=finite(upLimit)&&Math.abs(value-upLimit)<=1e-6;
            streak=limit?streak+1:0;
            var result=new Stock(code,value,previous,amount,turnover,circMv,totalMv,pb,upLimit,downLimit,
                    divide(value,ma)-1,streak,previousLimit);
            previousLimit=limit;return result;
        }
    }
    public static Day aggregate(LocalDate date,List<Stock> stocks){
        if(stocks.isEmpty())throw new IllegalArgumentException("Nonempty tradable panel required");
        var d=new Day(date);int n=stocks.size();
        d.put("total_amount",sum(stocks.stream().mapToDouble(Stock::amount).toArray()));
        d.put("amount_to_circ_mv",divide(d.get("total_amount"),10*sum(stocks.stream().mapToDouble(Stock::circMv).toArray())));
        var turnovers=stocks.stream().mapToDouble(Stock::turnover).toArray();
        d.put("avg_turnover_rate",mean(turnovers));d.put("median_turnover_rate",quantile(turnovers,.5));
        d.put("high_turnover_stock_ratio",stocks.stream().filter(s->s.turnover>=10).count()/(double)n);
        d.put("up_ratio",stocks.stream().filter(Stock::up).count()/(double)n);
        d.put("down_ratio",stocks.stream().filter(Stock::down).count()/(double)n);
        d.put("up_down_spread",d.get("up_ratio")-d.get("down_ratio"));
        d.put("limit_up_ratio",stocks.stream().filter(Stock::limitUp).count()/(double)n);
        d.put("limit_down_ratio",stocks.stream().filter(Stock::limitDown).count()/(double)n);
        d.put("limit_net_ratio",d.get("limit_up_ratio")-d.get("limit_down_ratio"));
        d.put("pct_above_ma20",stocks.stream().filter(s->s.distance>0).count()/(double)n);
        d.put("median_distance_to_ma20",quantile(stocks.stream().mapToDouble(Stock::distance).toArray(),.5));
        d.put("pct_distance_to_ma20_gt_5",stocks.stream().filter(s->s.distance>.05).count()/(double)n);
        d.put("pct_distance_to_ma20_lt_minus_5",stocks.stream().filter(s->s.distance<-.05).count()/(double)n);
        d.put("all_a_ret",mean(stocks.stream().mapToDouble(Stock::ret).toArray()));
        d.put("max_limit_streak",stocks.stream().mapToInt(Stock::streak).max().orElse(0));
        d.put("limit_promotion_rate",divide(stocks.stream().filter(s->s.previousLimit&&s.limitUp()).count(),
                stocks.stream().filter(Stock::previousLimit).count()));
        double[] amounts=stocks.stream().mapToDouble(Stock::amount).filter(MarketSentimentDailyCalculation::finite).sorted().toArray();
        double top=0;for(int start=0;start<amounts.length;){int end=start+1;while(end<amounts.length&&amounts[end]==amounts[start])end++;
            double rank=((start+1)+end)/2.0;if(rank/amounts.length>=.9)for(int i=start;i<end;i++)top+=amounts[i];start=end;}
        d.put("top_amount_share",divide(top,d.get("total_amount")));
        d.put("small_large_ret",bucketDifference(stocks,false));
        d.put("growth_value_ret",-bucketDifference(stocks,true));
        d.put("large_ret_proxy",d.get("all_a_ret")-(finite(d.get("small_large_ret"))?d.get("small_large_ret"):0));
        return d;
    }
    private static double bucketDifference(List<Stock> stocks,boolean pb){
        var ordered=stocks.stream().filter(s->finite(s.ret())&&finite(pb?s.pb:s.totalMv))
                .sorted(Comparator.comparingDouble((Stock s)->pb?s.pb:s.totalMv).thenComparing(Stock::code)).toList();
        if(ordered.stream().map(s->pb?s.pb:s.totalMv).distinct().count()<3)return Double.NaN;
        double lower=0,upper=0;int nl=0,nu=0,n=ordered.size();
        // qcut on rank(method=first), using linearly interpolated rank boundaries.
        for(int i=0;i<n;i++){if(i<=(n-1)/3.0){lower+=ordered.get(i).ret();nl++;}
            else if(i>2*(n-1)/3.0){upper+=ordered.get(i).ret();nu++;}}
        return divide(lower,nl)-divide(upper,nu);
    }
    public static void score(List<Day> days){
        component(days,"heat_score",List.of("amount_to_circ_mv","avg_turnover_rate","median_turnover_rate","high_turnover_stock_ratio","top_amount_share"));
        // Legacy _component_score iterates ONLY its columns argument. The separately
        // supplied inverse_columns entries (down_ratio, limit_down_ratio, and
        // pct_distance_to_ma20_lt_minus_5) are absent from those lists, so they are
        // no-ops in the published model. Do not silently add them to this version.
        component(days,"breadth_score",List.of("up_ratio","up_down_spread"));
        component(days,"limit_score",List.of("limit_up_ratio","limit_net_ratio","max_limit_streak","limit_promotion_rate"));
        component(days,"profit_effect_score",List.of("pct_above_ma20","median_distance_to_ma20","pct_distance_to_ma20_gt_5"));
        component(days,"structure_score",List.of("small_large_ret","growth_value_ret"));
        component(days,"leverage_score",List.of("margin_buy_sell_ratio","margin_buy_amount_ratio","margin_balance_change_5d"));
        component(days,"moneyflow_score",List.of("moneyflow_net_amount_ratio","moneyflow_large_net_ratio"));
        for(int i=0;i<days.size();i++){var d=days.get(i);var p=i==0?null:days.get(i-1);
            d.flags.put("index_up_breadth_down",p!=null&&d.get("all_a_ret")>0&&d.get("up_ratio")<p.get("up_ratio"));
            d.flags.put("amount_up_limit_down",p!=null&&d.get("total_amount")>p.get("total_amount")&&d.get("limit_up_ratio")<p.get("limit_up_ratio"));
            d.flags.put("small_up_large_down",d.get("small_large_ret")>0&&d.get("large_ret_proxy")<0);
            d.put("divergence_raw",(d.flag("index_up_breadth_down")?1:0)+(d.flag("amount_up_limit_down")?1:0)+(d.flag("small_up_large_down")?1:0));
        }
        component(days,"divergence_score",List.of("divergence_raw","limit_down_ratio"));
        for(int i=0;i<days.size();i++){var d=days.get(i);var p=i==0?null:days.get(i-1);
            d.flags.put("is_false_boom",d.flag("index_up_breadth_down")&&d.get("pct_above_ma20")<.5);
            d.flags.put("is_limit_collapse",d.get("max_limit_streak")<=historyMax(days,i,10,5,"max_limit_streak")-3
                    &&d.get("limit_down_ratio")>historyQuantile(days,i,60,20,"limit_down_ratio",.8));
            d.flags.put("is_leverage_warning",d.get("margin_buy_sell_ratio")<historyQuantile(days,i,60,20,"margin_buy_sell_ratio",.2)&&d.get("all_a_ret")>=0);
            d.flags.put("is_crowded",d.get("heat_score")>=80&&d.get("top_amount_share")>historyQuantile(days,i,252,60,"top_amount_share",.8));
            d.put("sentiment_score_core",mean(new double[]{d.get("heat_score"),d.get("breadth_score"),d.get("limit_score"),d.get("profit_effect_score"),d.get("structure_score"),d.get("divergence_score")}));
            d.put("sentiment_score_enhanced",mean(new double[]{d.get("sentiment_score_core"),d.get("leverage_score"),d.get("moneyflow_score")}));
            d.put("sentiment_score",finite(d.get("leverage_score"))||finite(d.get("moneyflow_score"))?d.get("sentiment_score_enhanced"):d.get("sentiment_score_core"));
            d.flags.put("sentiment_up_return_down",p!=null&&d.get("sentiment_score")>p.get("sentiment_score")&&d.get("all_a_ret")<0);
            d.state=classify(d);
        }
        pca(days);
    }
    private static void component(List<Day> days,String target,List<String> fields){
        double[][] z=new double[fields.size()][];for(int j=0;j<fields.size();j++){String field=fields.get(j);z[j]=zscore(days.stream().mapToDouble(d->d.get(field)).toArray());}
        for(int i=0;i<days.size();i++){double[] values=new double[z.length];for(int j=0;j<z.length;j++)values[j]=z[j][i];
            double value=mean(values);days.get(i).put(target,clip(50+16.6667*value,0,100));}
    }
    static double[] zscore(double[] raw){
        double lo=quantile(raw,.01),hi=quantile(raw,.99);double[] x=raw.clone(),result=new double[x.length];
        for(int i=0;i<x.length;i++)x[i]=clip(x[i],lo,hi);
        for(int i=0;i<x.length;i++){double[] history=Arrays.copyOfRange(x,Math.max(0,i-756),i);
            if(Arrays.stream(history).filter(MarketSentimentDailyCalculation::finite).count()<120)history=Arrays.copyOfRange(x,0,i);
            if(Arrays.stream(history).filter(MarketSentimentDailyCalculation::finite).count()<20){result[i]=Double.NaN;continue;}
            // Welford preserves exact zero variance for constant non-binary
            // values (e.g. 1.1), matching pandas' explicit zero-std branches.
            double m=0,m2=0;int count=0;for(double v:history)if(finite(v)){count++;double delta=v-m;m+=delta/count;m2+=delta*(v-m);}
            double std=Math.sqrt(Math.max(0,m2/count));
            result[i]=!finite(x[i])?Double.NaN:std==0?(x[i]>m?3:x[i]<m?-3:0):clip((x[i]-m)/std,-3,3);
        }return result;
    }
    private static String classify(Day d){
        double s=d.get("sentiment_score");if(!finite(s))return "unknown";
        if(d.flag("is_limit_collapse")||d.flag("is_leverage_warning"))return "退潮期";
        if(d.flag("is_false_boom")||d.get("divergence_score")>=70)return "分歧期";
        if(s>=80&&d.get("limit_score")>=65)return "高潮期";
        if(s<20&&d.get("breadth_score")<35)return "冰点期";
        if(s>=55&&d.get("breadth_score")>=50&&d.get("profit_effect_score")>=50)return "升温期";
        if(s>=20&&s<55&&d.get("breadth_score")>=45)return "修复期";return "中性期";
    }
    private static double[] history(List<Day> days,int index,int window,String field){double[] x=new double[index-Math.max(0,index-window)];
        for(int i=0;i<x.length;i++)x[i]=days.get(Math.max(0,index-window)+i).get(field);return x;}
    private static double historyMax(List<Day> days,int i,int w,int min,String f){double[] x=history(days,i,w,f);
        return Arrays.stream(x).filter(MarketSentimentDailyCalculation::finite).count()<min?Double.NaN:Arrays.stream(x).filter(MarketSentimentDailyCalculation::finite).max().orElse(Double.NaN);}
    private static double historyQuantile(List<Day> days,int i,int w,int min,String f,double q){double[] x=history(days,i,w,f);
        return Arrays.stream(x).filter(MarketSentimentDailyCalculation::finite).count()<min?Double.NaN:quantile(x,q);}
    /** StandardScaler population variance, covariance eigendecomposition, sklearn Vt-based svd_flip. */
    private static void pca(List<Day> days){
        List<String> fields=List.of("heat_score","breadth_score","limit_score","profit_effect_score","structure_score","divergence_score","leverage_score","moneyflow_score");
        var valid=days.stream().filter(d->fields.stream().filter(f->finite(d.get(f))).count()>=5).toList();
        if(valid.size()<50)return;int n=valid.size(),p=fields.size();double[][] x=new double[n][p];
        for(int j=0;j<p;j++){double running=0,last=Double.NaN;int count=0;
            for(int i=0;i<n;i++){double v=valid.get(i).get(fields.get(j));if(finite(v)){running+=v;count++;}
                if(!finite(v))v=count>0?running/count:last;if(finite(v))last=v;x[i][j]=v;}
            double next=Double.NaN;for(int i=n-1;i>=0;i--){if(finite(x[i][j]))next=x[i][j];else x[i][j]=next;}
            if(!finite(x[0][j]))throw new IllegalStateException("PCA feature has no observations: "+fields.get(j));
            double m=0;for(int i=0;i<n;i++)m+=x[i][j];m/=n;double variance=0;
            for(int i=0;i<n;i++)variance+=(x[i][j]-m)*(x[i][j]-m);variance/=n;
            double eps=Math.ulp(1.0),constantBound=n*eps*variance+Math.pow(n*m*eps,2);
            double scale=variance<=constantBound?1:Math.sqrt(variance);
            for(int i=0;i<n;i++)x[i][j]=(x[i][j]-m)/scale;
        }
        // sklearn PCA centers its input once more after StandardScaler. Keep the
        // residual centered input for both the covariance and score projection.
        for(int j=0;j<p;j++){double center=0;for(int i=0;i<n;i++)center+=x[i][j];center/=n;for(int i=0;i<n;i++)x[i][j]-=center;}
        double[][] a=new double[p][p],v=new double[p][p];for(int j=0;j<p;j++){v[j][j]=1;for(int k=0;k<p;k++){for(int i=0;i<n;i++)a[j][k]+=x[i][j]*x[i][k];a[j][k]/=n-1;}}
        boolean converged=false;for(int iteration=0;iteration<10000;iteration++){int row=0,col=1;double largest=0;
            for(int j=0;j<p;j++)for(int k=j+1;k<p;k++)if(Math.abs(a[j][k])>largest){largest=Math.abs(a[j][k]);row=j;col=k;}
            if(largest<1e-13){converged=true;break;}double theta=.5*Math.atan2(2*a[row][col],a[col][col]-a[row][row]);double c=Math.cos(theta),s=Math.sin(theta);
            double rr=c*c*a[row][row]-2*s*c*a[row][col]+s*s*a[col][col];
            double cc=s*s*a[row][row]+2*s*c*a[row][col]+c*c*a[col][col];
            for(int k=0;k<p;k++)if(k!=row&&k!=col){double ar=a[k][row],ac=a[k][col];a[k][row]=a[row][k]=c*ar-s*ac;a[k][col]=a[col][k]=s*ar+c*ac;}
            a[row][row]=rr;a[col][col]=cc;a[row][col]=a[col][row]=0;
            for(int k=0;k<p;k++){double vr=v[k][row],vc=v[k][col];v[k][row]=c*vr-s*vc;v[k][col]=s*vr+c*vc;}
        }
        if(!converged)throw new IllegalStateException("PCA eigendecomposition did not converge");
        Integer[] order=new Integer[p];for(int j=0;j<p;j++)order[j]=j;Arrays.sort(order,Comparator.comparingDouble((Integer j)->a[j][j]).reversed());
        var names=List.of("pc1_heat","pc2_divergence","pc3_structure");
        for(int component=0;component<3;component++){int eigen=order[component],pivot=0;for(int j=1;j<p;j++)if(Math.abs(v[j][eigen])>Math.abs(v[pivot][eigen]))pivot=j;
            double sign=v[pivot][eigen]<0?-1:1;for(int i=0;i<n;i++){double projection=0;for(int j=0;j<p;j++)projection+=x[i][j]*v[j][eigen]*sign;valid.get(i).put(names.get(component),projection);}}
    }
    public static boolean finite(double value){return Double.isFinite(value);}
    public static double divide(double a,double b){return finite(a)&&finite(b)&&b!=0?a/b:Double.NaN;}
    public static double mean(double[] values){double sum=0;int n=0;for(double v:values)if(finite(v)){sum+=v;n++;}return n==0?Double.NaN:sum/n;}
    public static double sum(double[] values){double sum=0;for(double v:values)if(finite(v))sum+=v;return sum;}
    public static double quantile(double[] values,double q){double[] sorted=Arrays.stream(values).filter(MarketSentimentDailyCalculation::finite).sorted().toArray();if(sorted.length==0)return Double.NaN;
        double index=(sorted.length-1)*q;int lo=(int)Math.floor(index),hi=(int)Math.ceil(index);return sorted[lo]+(index-lo)*(sorted[hi]-sorted[lo]);}
    private static double clip(double x,double lo,double hi){return finite(x)&&finite(lo)&&finite(hi)?Math.max(lo,Math.min(hi,x)):Double.NaN;}
}
