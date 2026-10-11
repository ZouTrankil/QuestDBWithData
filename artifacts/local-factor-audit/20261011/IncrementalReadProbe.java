import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.domain.*;
import java.util.*;import java.time.*;
public class IncrementalReadProbe {
 static GlobalFactorObservation row(String id,double value){var d=LocalDate.of(2026,9,30);return new GlobalFactorObservation(d,"stock",id,"factor","v1",value,d.atTime(19,0),"complete","GLOBAL",null);}
 public static void main(String[] args){
  var a=row("000001.SZ",1);var b=row("000002.SZ",2);var extra=row("000003.SZ",3);
  int[] reads={0};var r=GlobalObservationIncrementalRead.reconcile(List.of(a,b),(s,n)->{reads[0]++;return List.of(a,row("000002.SZ",0),extra);});
  if(reads[0]!=1 || r.matching()!=1 || r.unexpected()!=1 || !r.writes().equals(List.of(b)))throw new AssertionError(r);
  reads[0]=0;try{GlobalObservationIncrementalRead.reconcile(List.of(a,a),(s,n)->{reads[0]++;return List.of();});throw new AssertionError();}catch(IllegalArgumentException expected){}
  if(reads[0]!=0)throw new AssertionError();
  try{GlobalObservationIncrementalRead.reconcile(List.of(a),(s,n)->List.of(a,a));throw new AssertionError();}catch(IllegalStateException expected){}
  if(!GlobalObservationIncrementalRead.reconcile(List.of(a),(s,n)->List.of(a)).writes().isEmpty())throw new AssertionError();
  System.out.println("PASS: one read per section, changed-only writes, preserve extra rows, duplicate rejection, unchanged no-op");
 }
}
