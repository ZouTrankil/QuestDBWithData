import com.zoutrankil.data.factor.engine.*;
import java.time.*;import java.util.*;
public class RequestedPanelProbe {
 public static void main(String[] args) {
  var input=new ArrayList<DailyFactorPanel.Security>();
  for(int s=0;s<4;s++) {
   var dates=new LocalDate[320];var columns=new LinkedHashMap<String,double[]>();
   for(String name:List.of("close","amount","turnover_rate","total_mv","circ_mv","pb","pe_ttm","ps_ttm","vol"))columns.put(name,new double[320]);
   for(int i=0;i<320;i++) {
    dates[i]=LocalDate.of(2025,1,1).plusDays(i);
    for(var e:columns.entrySet())e.getValue()[i]=2+s+i*.01;
    columns.get("close")[i]=(i%79==0)?Double.NaN:10+s+Math.sin(i*.13+s)+i*.001;
    columns.get("vol")[i]=Double.NaN;
   }
   input.add(new DailyFactorPanel.Security("s"+s,dates,columns,LocalDate.of(2020,1,1)));
  }
  var full=DailyFactorPanel.compute(input);var from=LocalDate.of(2025,1,1).plusDays(318);
  var small=DailyFactorPanel.compute(input,from,from.plusDays(1));int checks=0;
  for(int s=0;s<4;s++)for(String column:full.get(s).columns().keySet())for(int i=0;i<2;i++) {
   double a=full.get(s).columns().get(column)[318+i],b=small.get(s).columns().get(column)[i];
   if(Double.doubleToLongBits(a)!=Double.doubleToLongBits(b))throw new AssertionError(column);
   checks++;
  }
  if(small.stream().anyMatch(s->s.dates().length!=2))throw new AssertionError("warmup retained");
  System.out.println("PASS: "+checks+" exact window comparisons; full warmup retained for kernels only");
 }
}
