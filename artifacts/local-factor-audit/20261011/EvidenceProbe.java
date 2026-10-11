import com.zoutrankil.data.factor.application.*;
import com.zoutrankil.data.factor.domain.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public class EvidenceProbe {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory(Path.of(args[0]),"evidence-probe-");Path path=root.resolve("snapshot.json");
  var date=LocalDate.of(2026,9,30);
  var rows=List.of(new GlobalFactorObservation(date,"stock","000001.SZ","momentum","v1",null,date.atTime(19,0),"missing","GLOBAL",null),new GlobalFactorObservation(date,"stock","000002.SZ","momentum","v1",.5,date.atTime(19,0),"complete","GLOBAL","lineage"));
  String sha=FactorObservationEvidence.writeNew(path,rows,false,"a".repeat(64));
  var loaded=FactorObservationEvidence.read(path);
  if(!loaded.rows().equals(rows)||loaded.sourceComplete()||!loaded.fileSha256().equals(sha))throw new AssertionError("roundtrip");
  try{FactorObservationEvidence.writeNew(path,rows,false,"a".repeat(64));throw new AssertionError("overwrite");}catch(FileAlreadyExistsException expected){}
  Path bad=root.resolve("bad.json");Files.writeString(bad,Files.readString(path).replace("java.factor_observations.v1","unknown"));
  try{FactorObservationEvidence.read(bad);throw new AssertionError("format");}catch(IllegalArgumentException expected){}
  try{FactorObservationEvidence.writeNew(root.resolve("duplicate.json"),List.of(rows.get(0),rows.get(0)),false,"a".repeat(64));throw new AssertionError("duplicate");}catch(IllegalArgumentException expected){}
  System.out.println("PASS: roundtrip including nulls and timestamps, fingerprint, partial source, overwrite protection, invalid format and duplicate rejection");
 }
}
