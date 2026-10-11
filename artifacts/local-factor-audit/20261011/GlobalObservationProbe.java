import com.zoutrankil.data.factor.application.GlobalFactorObservations;
import com.zoutrankil.data.factor.domain.FactorDefinition;
import java.time.*;
public class GlobalObservationProbe {
 public static void main(String[] args){
  var d=new FactorDefinition("a","v1","stock","canonical_daily","daily","a","raw","negative",1,"pit","rank","none",true,true);
  var day=LocalDate.of(2026,9,30);var stamp=day.atTime(20,0);
  var row=GlobalFactorObservations.raw(day,"000001.SZ",d,2.5,null,stamp,"ok","",null);
  if(row.factorValue()!=2.5 || !row.scope().equals("GLOBAL") || !row.qualityStatus().equals("complete") || !row.availableAt().equals(day.atTime(19,0)))throw new AssertionError(row);
  var future=GlobalFactorObservations.raw(day,"000001.SZ",d,2.5,null,day.atTime(18,0),"ok","",null);
  if(!future.qualityStatus().equals("unavailable"))throw new AssertionError(future);
  var missing=GlobalFactorObservations.raw(day,"000001.SZ",d,Double.NaN,null,stamp,"ok","",null);
  if(missing.factorValue()!=null || !missing.qualityStatus().equals("missing"))throw new AssertionError(missing);
  var invalid=GlobalFactorObservations.raw(day,"000001.SZ",d,2.5,null,stamp,"missing_source","invalid",null);
  if(!invalid.qualityStatus().equals("invalid"))throw new AssertionError(invalid);
  System.out.println("PASS: raw value retains sign, GLOBAL scope, availability, missing value, explicit quality precedence");
 }
}
