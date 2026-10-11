import com.zoutrankil.data.repository.SyncRunLedger;import com.zoutrankil.data.domain.SyncRunState;
import com.fasterxml.jackson.databind.ObjectMapper;
public class PartialReconciliationProbe {
 public static void main(String[] args)throws Exception {
  var method=SyncRunLedger.class.getDeclaredMethod("requireCompletionEvidence",SyncRunState.class,SyncRunState.class,String.class);method.setAccessible(true);
  var mapper=new ObjectMapper();var root=mapper.createObjectNode();root.put("submittedRows",250);root.put("sourceComplete",false);root.put("allSubmittedSlicesVerified",true);
  var proof=root.putObject("verification");proof.put("expectedRows",250);proof.put("matchedRows",250);proof.put("actualRows",250);proof.put("mismatchedRows",0);proof.put("duplicateKeys",0);proof.put("missingKeys",0);proof.put("passed",true);proof.put("writerStopped",true);proof.put("sourceFingerprint","sha");proof.put("readbackEvidence","evidence");
  SyncRunState.IN_DOUBT.requireTransition(SyncRunState.PARTIAL);
  method.invoke(null,SyncRunState.IN_DOUBT,SyncRunState.PARTIAL,root.toString());
  int rejected=0;
  for(String field:new String[]{"writerStopped","matchedRows","readbackEvidence","sourceFingerprint"}) {
   var bad=root.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.get("verification")).remove(field);
   try{method.invoke(null,SyncRunState.IN_DOUBT,SyncRunState.PARTIAL,bad.toString());throw new AssertionError(field);}catch(java.lang.reflect.InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;rejected++;}
  }
  for(String field:new String[]{"sourceComplete","allSubmittedSlicesVerified","submittedRows"}) {
   var bad=root.deepCopy();bad.remove(field);try{method.invoke(null,SyncRunState.IN_DOUBT,SyncRunState.PARTIAL,bad.toString());throw new AssertionError(field);}catch(java.lang.reflect.InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;rejected++;}
  }
  System.out.println("PASS: valid submitted-prefix proof accepted; "+rejected+" incomplete proofs rejected");
 }
}
