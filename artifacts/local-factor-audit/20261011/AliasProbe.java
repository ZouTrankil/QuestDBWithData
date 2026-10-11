import com.zoutrankil.data.factor.engine.StockCodeAliases;
import java.time.LocalDate;
import java.util.List;
public class AliasProbe {
 public static void main(String[] args) {
  var rules=StockCodeAliases.load();
  for(String day:List.of("2025-10-08","2025-10-09")) {
   var date=LocalDate.parse(day);
   var old=new StockCodeAliases.Row("835438.BJ",date,new double[]{1,10});
   var now=new StockCodeAliases.Row("920438.BJ",date,new double[]{1,20});
   var output=rules.normalize(List.of(now,old),new int[]{0});
   if(output.size()!=1 || !output.getFirst().symbol().equals("920438.BJ") || output.getFirst().values()[1]!=(day.endsWith("08")?10:20))throw new AssertionError(day);
  }
  var date=LocalDate.parse("2025-10-09");
  try {
   rules.normalize(List.of(new StockCodeAliases.Row("835438.BJ",date,new double[]{1}),new StockCodeAliases.Row("920438.BJ",date,new double[]{2})),new int[]{0});
   throw new AssertionError("conflict accepted");
  } catch(IllegalStateException expected) {}
  if(!rules.canonical("300114.SZ").equals("302132.SZ"))throw new AssertionError();
  if(!rules.canonical("000001.SZ").equals("000001.SZ"))throw new AssertionError();
  System.out.println("PASS: switch boundary, preferred payload, conflict rejection, SZ alias and unchanged code");
 }
}
