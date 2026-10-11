import com.zoutrankil.data.factor.engine.FactorNeutralization;
import java.nio.file.*;
public class ProjectionProbe {
 public static void main(String[] args) throws Exception {
  int n=800; String[] symbols=new String[n], industry=new String[n]; double[] size=new double[n], y=new double[n];
  for(int i=0;i<n;i++){symbols[i]=String.format("%06d.SZ",i);industry[i]="I"+(i%12);size[i]=10+Math.log1p(i);y[i]=Math.sin(i*.17)+i*.0003;}
  var cache=new FactorNeutralization.ProjectionCache(4);
  var first=FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  var second=FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  if(cache.builds()!=1 || cache.hits()!=1 || !java.util.Arrays.equals(first.values(),second.values()))throw new AssertionError("reuse");
  size[3]+=.2;FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  if(cache.builds()!=2)throw new AssertionError("exposure invalidation");size[3]-=.2;
  y[4]=Double.NaN;FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  if(cache.builds()!=3)throw new AssertionError("sample invalidation");y[4]=Math.sin(4*.17)+4*.0003;
  StringBuilder csv=new StringBuilder("ts_code,log_market_cap,industry,value,result\n");
  for(int i=0;i<n;i++)csv.append(symbols[i]+","+size[i]+","+industry[i]+","+y[i]+","+first.values()[i]+"\n");
  Files.writeString(Path.of(args[0]),csv);
  for(int i=0;i<100;i++)FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  long start=System.nanoTime();for(int i=0;i<200;i++)FactorNeutralization.residualize(symbols,y,size,industry,20,cache);
  System.out.println("cached_java_ms_per_factor="+(System.nanoTime()-start)/200e6);
  start=System.nanoTime();for(int i=0;i<20;i++)FactorNeutralization.residualize(symbols,y,size,industry,20);
  System.out.println("uncached_java_ms_per_factor="+(System.nanoTime()-start)/20e6);
  System.out.println("cache_reuse_and_invalidation=PASS");cache.close();
 }
}
