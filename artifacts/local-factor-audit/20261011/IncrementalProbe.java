import com.zoutrankil.data.factor.application.FactorIncrementalPlanner;
import java.time.*;
import java.util.*;
public class IncrementalProbe {
 public static void main(String[] args){
  var day=LocalDate.of(2026,9,30);
  var a=new FactorIncrementalPlanner.Section(day,"a","v1","all");
  var b=new FactorIncrementalPlanner.Section(day,"b","v1","all");
  var c=new FactorIncrementalPlanner.Section(day.minusDays(2),"c","v1","all");
  var requests=List.of(new FactorIncrementalPlanner.Requested(a,"new",252),new FactorIncrementalPlanner.Requested(b,"same",252),new FactorIncrementalPlanner.Requested(c,"same",252));
  var observed=List.of(new FactorIncrementalPlanner.Coverage(a,"old",true,true),new FactorIncrementalPlanner.Coverage(b,"same",true,true),new FactorIncrementalPlanner.Coverage(c,"same",true,false));
  var plan=FactorIncrementalPlanner.plan(requests,observed,1);
  if(plan.sections().size()!=1 || !plan.sections().getFirst().reason().equals("partial_source") || !plan.readFrom().equals(day.minusDays(462)))throw new AssertionError(plan);
  var both=FactorIncrementalPlanner.plan(requests,observed,2);
  if(both.sections().size()!=2 || !both.sections().getLast().reason().equals("input_revised"))throw new AssertionError(both);
  var empty=FactorIncrementalPlanner.plan(List.of(requests.get(1)),observed,2);
  if(!empty.sections().isEmpty() || empty.readFrom()!=null)throw new AssertionError(empty);
  System.out.println("PASS: complete skipped, partial retry, revision replay, oldest gap batching, warmup range, empty plan");
 }
}
