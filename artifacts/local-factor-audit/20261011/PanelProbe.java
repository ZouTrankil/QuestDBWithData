import com.zoutrankil.data.factor.engine.*;import com.zoutrankil.data.domain.*;import java.time.*;import java.nio.file.*;import java.util.*;
public class PanelProbe {
 public static void main(String[] args)throws Exception{
  var mapper=JobDefinitionJson.mapper();Path root=Path.of(args[0]);var source=mapper.readTree(Files.readAllBytes(root.resolve("daily-feature-java-direct.json"))).get("input");
  var securities=new ArrayList<DailyFactorPanel.Security>();var inputs=new ArrayList<Object>();
  for(int s=0;s<3;s++){
   LocalDate[] dates=new LocalDate[source.get("dates").size()];for(int i=0;i<dates.length;i++)dates[i]=LocalDate.parse(source.get("dates").get(i).asText());
   Map<String,double[]> columns=new LinkedHashMap<>();var names=source.get("columns").fieldNames();while(names.hasNext()){String name=names.next();double[] v=new double[dates.length];for(int i=0;i<v.length;i++){var node=source.get("columns").get(name).get(i);v[i]=node.isNull()?Double.NaN:node.asDouble();if(name.equals("close"))v[i]*=1+s*.05*Math.sin(i*.1);if(name.equals("total_mv"))v[i]*=1+s*.7;if(name.equals("vol")&&s==2)v[i]=Double.NaN;}columns.put(name,v);}
   securities.add(new DailyFactorPanel.Security("00000"+(s+1)+".SZ",dates,columns,LocalDate.of(2000+s,1,1)));
  }
  var result=DailyFactorPanel.compute(securities);
  var emittedPages=new ArrayList<List<com.zoutrankil.data.factor.domain.GlobalFactorObservation>>();
  long emitted=com.zoutrankil.data.factor.application.NativeDailyObservations.streamPanel(result,Set.of(LocalDate.of(2026,9,30)),List.of("market_beta","risk_nonlinear_size"),com.zoutrankil.data.factor.application.FactorDefinitionCatalog.load(),LocalDateTime.of(2026,9,30,20,0),emittedPages::add);
  if(emitted!=6 || emittedPages.size()!=1 || emittedPages.getFirst().size()!=6)throw new AssertionError("panel emission");
  System.out.println("PASS: shared panel emits 6 catalog observations for 3 securities, 2 factors, 1 requested date");
  var rows=new ArrayList<Object>();
  for(int s=0;s<result.size();s++){var r=result.get(s);for(int i=0;i<r.dates().length;i++){var row=new LinkedHashMap<String,Object>();row.put("ts_code",r.symbol());row.put("trade_date",r.dates()[i].toString());for(String name:List.of("market_return","nonlinear_size","listing_days","market_beta_252","residual_vol_252","volume_momentum")){double v=r.columns().get(name)[i];row.put(name,Double.isFinite(v)?v:null);}rows.add(row);}}
  Files.write(root.resolve("panel-java.json"),mapper.writeValueAsBytes(rows));System.out.println("panel_rows="+rows.size());
 }
}
