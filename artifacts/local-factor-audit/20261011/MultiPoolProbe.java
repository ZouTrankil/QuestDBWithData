import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.factor.domain.*;
import java.time.*;
import java.util.*;
public class MultiPoolProbe {
 public static void main(String[] args) {
  int n=80;String[] symbols=new String[n],industry=new String[n];double[] size=new double[n],a=new double[n],b=new double[n];
  for(int i=0;i<n;i++){symbols[i]="S"+i;industry[i]="I"+(i%4);size[i]=10+Math.log1p(i);a[i]=Math.sin(i*.17);b[i]=Math.cos(i*.23);}
  var date=LocalDate.of(2026,9,30);
  var d1=new FactorDefinition("a","v1","stock","canonical_daily","daily","a","raw","positive",1,"pit","robust_zscore","industry_size",true,true);
  var d2=new FactorDefinition("b","v1","stock","canonical_daily","daily","b","raw","negative",1,"pit","robust_zscore","industry_size",true,true);
  var factors=List.of(new MultiPoolFactorPipeline.RawFactor(d1,a),new MultiPoolFactorPipeline.RawFactor(d2,b));
  var pools=List.of(new MultiPoolFactorPipeline.Pool("all",java.util.stream.IntStream.range(0,n).toArray()),new MultiPoolFactorPipeline.Pool("half",java.util.stream.IntStream.range(0,40).toArray()));
  var results=new ArrayList<MultiPoolFactorPipeline.Output>();
  var stats=MultiPoolFactorPipeline.compute(date,Set.of(date),symbols,size,industry,factors,pools,results::add);
  if(stats.emittedSections()!=4 || stats.projectionBuilds()!=2 || stats.projectionHits()!=2)throw new AssertionError(stats);
  for(var out:results){int count=out.symbols().size();double[] raw=out.definition()==d1?a:b;
   var expected=FactorPreprocessing.compute(out.definition(),Arrays.copyOf(symbols,count),Arrays.copyOf(raw,count),Arrays.copyOf(size,count),Arrays.copyOf(industry,count));
   for(int i=0;i<count;i++)if(Math.abs(expected.effective()[i]-out.values().effective()[i])>1e-12)throw new AssertionError("pool parity");
  }
  var skipped=MultiPoolFactorPipeline.compute(date.minusDays(1),Set.of(date),symbols,size,industry,factors,pools,x->{throw new AssertionError("warmup leaked");});
  if(skipped.emittedSections()!=0)throw new AssertionError("warmup");
  if(!Arrays.equals(results.get(0).values().raw(),a))throw new AssertionError("scratch alias");
  System.out.println("PASS: pool parity, shared projection, retained result ownership, warmup exclusion; "+stats);
 }
}
