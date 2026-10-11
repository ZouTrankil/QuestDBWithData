import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.factor.domain.*;
import java.util.*;import java.time.*;
public class PlannedEmissionProbe {
 public static void main(String[] args)throws Exception {
  var cat=FactorDefinitionCatalog.load();var a=LocalDate.of(2026,9,29);var b=a.plusDays(1);
  var ids=List.of("reversal_1d","risk_adv20");var columns=new HashMap<String,double[]>();
  for(String id:ids)columns.put(cat.require(id).sourceColumn(),new double[]{1,2});
  var panel=List.of(new DailyFactorPanel.Features("000001.SZ",new LocalDate[]{a,b},columns));
  var requested=new ArrayList<FactorIncrementalPlanner.Requested>();
  for(int i=0;i<2;i++){var d=cat.require(ids.get(i));requested.add(new FactorIncrementalPlanner.Requested(new FactorIncrementalPlanner.Section(i==0?a:b,d.factorId(),d.version(),"GLOBAL"),"fingerprint",20));}
  var plan=FactorIncrementalPlanner.plan(requested,List.of(),2);var rows=new ArrayList<GlobalFactorObservation>();
  long count=NativeDailyObservations.streamPlanned(panel,plan,cat,b.atTime(20,0),rows::addAll);
  if(count!=2 || rows.size()!=2 || !rows.get(0).signalDate().equals(a) || !rows.get(1).signalDate().equals(b))throw new AssertionError(rows);
  columns.remove(cat.require(ids.get(1)).sourceColumn());rows.clear();
  try{NativeDailyObservations.streamPlanned(panel,plan,cat,b.atTime(20,0),rows::addAll);throw new AssertionError("missing column accepted");}catch(IllegalArgumentException expected){}
  if(!rows.isEmpty())throw new AssertionError("emitted before validation");
  System.out.println("PASS: two requested sections emit two rows, no cross product; missing later input emits zero rows");
 }
}
