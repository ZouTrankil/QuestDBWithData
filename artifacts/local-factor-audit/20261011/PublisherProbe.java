import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.domain.*;
import java.util.*;
import java.time.*;
public class PublisherProbe {
 static class Store implements GlobalObservationPublisher.Store {
  Map<GlobalObservationCoverage.Key,GlobalFactorObservation> rows=new LinkedHashMap<>();int sends;boolean drop;
  public void preflight(){}
  public List<GlobalFactorObservation> read(GlobalObservationPublisher.Section s){return List.copyOf(rows.values());}
  public void send(List<GlobalFactorObservation> batch){sends++;if(!drop)for(var row:batch)rows.put(GlobalObservationCoverage.key(row),row);}
 }
 public static void main(String[] args)throws Exception {
  var day=LocalDate.of(2026,9,30);var row=new GlobalFactorObservation(day,"stock","A","f","v1",1.0,day.atTime(19,0),"complete","GLOBAL",null);
  var store=new Store();var publisher=new GlobalObservationPublisher(store,1);
  var first=publisher.publish(List.of(row),false);
  if(!first.status().equals("PARTIAL") || first.writtenRows()!=1)throw new AssertionError(first);
  var repeat=publisher.publish(List.of(row),true);
  if(!repeat.status().equals("VERIFIED") || repeat.writtenRows()!=0 || repeat.reusedRows()!=1 || store.sends!=1)throw new AssertionError(repeat);
  var broken=new Store();broken.drop=true;
  try{new GlobalObservationPublisher(broken,1).publish(List.of(row),true);throw new AssertionError("unverified success");}catch(IllegalStateException correct){}
  System.out.println("PASS: partial source persisted, replay reuses exact row, complete source advances status, lost write fails readback");
 }
}
