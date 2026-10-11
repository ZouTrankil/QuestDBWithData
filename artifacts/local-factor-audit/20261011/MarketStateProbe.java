import com.zoutrankil.data.factor.engine.*;
import java.time.*;import java.util.*;
public class MarketStateProbe {
 public static void main(String[] args) {
  var securities=new ArrayList<DailyFactorPanel.Security>();
  for(int s=0;s<4;s++) {
   var dates=new LocalDate[45];var c=new LinkedHashMap<String,double[]>();
   for(String name:List.of("close","amount","turnover_rate","up_limit","down_limit"))c.put(name,new double[45]);
   for(int i=0;i<45;i++) {
    dates[i]=LocalDate.of(2026,1,1).plusDays(i);
    double price=10+s+Math.sin(i*.3+s);
    c.get("close")[i]=i%17==0?Double.NaN:price;
    c.get("amount")[i]=i%11==0?Double.NaN:100+s*5+i;
    c.get("turnover_rate")[i]=(i+s)%13==0?Double.NaN:1+s+i*.01;
    c.get("up_limit")[i]=i%5==0?price:price*1.1;
    c.get("down_limit")[i]=i%7==0?price:price*.9;
   }
   securities.add(new DailyFactorPanel.Security("s"+s,dates,c,LocalDate.of(2020,1,1)));
  }
  var r=MarketStateFeatures.compute(securities,LocalDate.of(2026,1,1),LocalDate.of(2026,2,14));
  System.out.println("date,"+String.join(",",r.columns().keySet()));
  for(int i=0;i<r.dates().length;i++) {
   var line=new StringBuilder(r.dates()[i].toString());for(var values:r.columns().values())line.append(',').append(values[i]);System.out.println(line);
  }
 }
}
