import com.zoutrankil.data.factor.application.GlobalObservationCoverage;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import java.time.*;
import java.util.*;
public class CoverageProbe {
 static GlobalFactorObservation row(String id,double value){var d=LocalDate.of(2026,9,30);return new GlobalFactorObservation(d,"stock",id,"a","v1",value,d.atTime(19,0),"complete","GLOBAL",null);}
 public static void main(String[] args){
  var expected=List.of(row("A",1),row("B",2),row("C",3));
  var physical=List.of(row("A",1),row("B",9),row("EXTRA",4));
  var delta=GlobalObservationCoverage.compare(expected,physical);
  if(delta.matching()!=1 || delta.unexpected()!=1 || delta.writes().size()!=2)throw new AssertionError(delta);
  if(!GlobalObservationCoverage.compare(expected,expected).matchesExpected())throw new AssertionError("replay");
  try{GlobalObservationCoverage.compare(expected,List.of(row("A",1),row("A",1)));throw new AssertionError("duplicates");}catch(IllegalStateException correct){}
  System.out.println("PASS: exact reuse, changed/missing values selected, unrelated rows preserved, duplicate physical key rejected");
 }
}
