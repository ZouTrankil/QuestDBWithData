import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.domain.*;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import com.zoutrankil.data.service.*;
import java.time.*;import java.nio.file.*;import java.util.*;
public class FactorRunnerProbe {
 static class Port implements FactorObservationWritePort {
  Map<GlobalObservationCoverage.Key,GlobalFactorObservation> data=new HashMap<>();
  public String targetIdentity(){return "static-v2-"+"b".repeat(64);}public void preflight(){}public boolean walSettled(){return true;}
  public boolean uncertainSenderStopped(){return true;}
  public void send(List<GlobalFactorObservation> rows){for(var r:rows)data.put(GlobalObservationCoverage.key(r),r);}
  public List<GlobalFactorObservation> readback(List<GlobalObservationCoverage.Key> keys){return keys.stream().map(data::get).filter(Objects::nonNull).toList();}
  public VerifiedBatchExecutor.Codec<GlobalFactorObservation,GlobalObservationCoverage.Key> codec(){return new VerifiedBatchExecutor.Codec<>(){
   public GlobalObservationCoverage.Key key(GlobalFactorObservation r){return GlobalObservationCoverage.key(r);}
   public byte[] canonicalBytes(GlobalFactorObservation r){return r.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
  };}
 }
 public static void main(String[] args)throws Exception{
  Path root=Files.createTempDirectory(Path.of(args[0]),"runner-probe-");Path ledger=root.resolve("ledger.sqlite");var date=LocalDate.of(2026,9,30);
  var rows=List.of(new GlobalFactorObservation(date,"stock","000001.SZ","momentum","v1",.5,date.atTime(19,0),"complete","GLOBAL",null));
  var port=new Port();Path evidence=root.resolve("observations.json");String sha=FactorObservationEvidence.writeNew(evidence,rows,false,"a".repeat(64));
  var service=new FactorObservationJobService(new SyncJobRunner<>(new SyncRunLedger(ledger),new DatasetIntervalLock(ledger)));
  var request=service.plan(SyncJobDefinition.Mode.INCREMENTAL,date,date,date,port.targetIdentity(),sha);
  var result=service.execute("probe-first",null,request,rows,false,evidence,port,()->false);
  if(result.state()!=SyncRunState.PARTIAL||result.verifiedRows()!=1)throw new AssertionError(result);
  var resumed=service.execute("probe-resume","probe-first",request,rows,false,evidence,port,()->false);
  if(resumed.state()!=SyncRunState.PARTIAL||resumed.reusedRows()!=1)throw new AssertionError(resumed);
  System.out.println("PASS: real SQLite runner/lease, PARTIAL publication, resume reuses verified row; "+resumed);
 }
}
