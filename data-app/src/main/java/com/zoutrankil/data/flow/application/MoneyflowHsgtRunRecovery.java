package com.zoutrankil.data.flow.application;

import com.zoutrankil.data.flow.port.MoneyflowHsgtStagingPort;

import com.zoutrankil.data.flow.domain.MoneyflowHsgtState;

import com.zoutrankil.data.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Reconciles a fully written stage against its original frozen run and immutable fetched receipts. */
public final class MoneyflowHsgtRunRecovery {
 private MoneyflowHsgtRunRecovery(){}
 public static void finishStageOnly(MoneyflowHsgtStagingPort tables,Path ledgerPath,String table,String runId,boolean writerStopped)throws Exception{
  if(!writerStopped)throw new IllegalStateException("D027 stopped writer required");
  var ledger=SyncRunLedger.openReadOnly(ledgerPath);var saved=ledger.getRun(runId);var entry=ledger.get(runId);
  if(!Set.of(SyncRunState.RUNNING,SyncRunState.IN_DOUBT).contains(entry.state())||!saved.jobId().equals("data.moneyflow_hsgt")||saved.jobVersion()!=MoneyflowHsgtSyncJobOwner.DEFINITION.version())throw new IllegalStateException("D027 stage recovery requires its active frozen run");
  var json=JobDefinitionJson.mapper();JsonNode frozen=json.readTree(saved.frozenJson());var definition=json.treeToValue(frozen.path("definition"),SyncJobDefinition.class);
  if(!definition.equals(MoneyflowHsgtSyncJobOwner.DEFINITION))throw new IllegalStateException("D027 frozen definition changed");
  Map<String,Object> params=json.convertValue(frozen.path("parameters"),new TypeReference<LinkedHashMap<String,Object>>(){});
  for(var spec:definition.parameters().entrySet())if(params.containsKey(spec.getKey())&&spec.getValue().type()==SyncJobDefinition.ParameterType.DATE)params.put(spec.getKey(),LocalDate.parse(frozen.path("parameters").path(spec.getKey()).asText()));
  var request=definition.freeze(SyncJobDefinition.Mode.valueOf(frozen.path("mode").asText()),params,LocalDate.parse(frozen.path("from").asText()),LocalDate.parse(frozen.path("to").asText()),LocalDate.parse(frozen.path("logicalDate").asText()));
  String fingerprint=SyncRequestIdentity.fingerprint(request,saved.targetId());
  if(!fingerprint.equals(SyncRequestIdentity.fingerprint(saved.frozenJson(),saved.targetId()))||!saved.targetId().equals(params.get("targetId"))||!saved.targetId().equals(tables.logicalTargetId(table)))throw new IllegalStateException("D027 frozen request/target differs");
  Path root=ledgerPath.toAbsolutePath().normalize().getParent().resolve("sync-evidence").resolve(runId).toRealPath(),stageRoot=root.resolve("stage");
  if(Files.isSymbolicLink(stageRoot)||!Files.isDirectory(stageRoot))throw new IllegalStateException("D027 stage folder invalid");
  Path intentFile=null;try(var files=Files.newDirectoryStream(stageRoot,"*-intent.json")){for(Path p:files){if(intentFile!=null)throw new IllegalStateException("D027 stage intent ambiguous");intentFile=p;}}
  if(intentFile==null||Files.isSymbolicLink(intentFile)||!Files.isRegularFile(intentFile)||Files.size(intentFile)>4*1024*1024||!intentFile.toRealPath().startsWith(root))throw new IllegalStateException("D027 bounded owned intent required");
  JsonNode intent=json.readTree(FileEvidenceStore.readBounded(intentFile, 4 * 1024 * 1024, () -> new IllegalStateException("D027 bounded owned intent required")));String stage=intent.path("stage").asText();
  if(!stage.matches("java_d027_moneyflow_hsgt_stage_[0-9a-f]{32}")||!"READY".equals(intent.path("phase").asText())||!"moneyflow_hsgt".equals(intent.path("dataset").asText())||intent.path("dedup").asBoolean(true)
    ||!runId.equals(intent.path("runId").asText())||!table.equals(intent.path("target").asText())||!saved.targetId().equals(intent.path("logicalTargetId").asText())
    ||!fingerprint.equals(intent.path("requestFingerprint").asText())||!request.from().toString().equals(intent.path("windowFrom").asText())||!request.to().toString().equals(intent.path("windowTo").asText())
    ||!request.logicalDate().toString().equals(intent.path("logicalDate").asText())||!request.mode().name().equals(intent.path("mode").asText()))throw new IllegalStateException("D027 intent differs from frozen request");
  var publication=new MoneyflowHsgtPublication(tables,ledgerPath);
  try(var operation=publication.beginOperation(runId)){
   if(publication.findForRun(runId).isPresent())throw new IllegalStateException("D027 journal already exists; finish it instead");
   var target=tables.open(table);var before=target.snapshot();var outside=target.outside(request.from(),request.to());
   String physical=tables.physicalTargetId(table,before.identity());
   if(!physical.equals(params.get("physicalTargetId"))||!physical.equals(intent.path("physicalTargetBefore").asText()))throw new IllegalStateException("D027 baseline physical target changed");
   requireSnapshot(intent.path("before"),before);requireSnapshot(intent.path("preservedOutside"),outside);
   var actualStage=tables.open(stage).snapshot();String stagePhysical=tables.physicalTargetId(stage,actualStage.identity());
   var initialStage=intent.path("stageOutside");
   if(!stagePhysical.equals(intent.path("stagePhysicalTarget").asText())||actualStage.identity().id()!=initialStage.path("identity").path("id").asLong(-1)
      ||!actualStage.identity().directory().equals(initialStage.path("identity").path("directory").asText())||!outside.fingerprint().equals(initialStage.path("fingerprint").asText())||outside.rows().size()!=initialStage.path("rows").asInt(-1))throw new IllegalStateException("D027 stage identity/outside proof changed");
   var recovery=MoneyflowHsgtRecoveryProof.slices(ledgerPath,runId,request);
   var pages=recovery.stream().map(MoneyflowHsgtRecoveryProof.Slice::page).toList();
   var locks=new DatasetIntervalLock(ledgerPath);
   var lease=locks.findOwned(runId,new DatasetIntervalLock.Scope("moneyflow_hsgt",request.from(),request.to()));
   boolean fetchedEmpty=recovery.stream().anyMatch(s->MoneyflowHsgtRecoveryProof.fetchedEmpty(s.entry().state(),s.fetched()));
   if(lease==null||!lease.inDoubt()&&!fetchedEmpty)throw new IllegalStateException("D027 retained IN_DOUBT lease required");
   var prepared=new MoneyflowHsgtState.Prepared(table,stage,saved.targetId(),physical,stagePhysical,runId,fingerprint,request.from(),request.to(),before,outside,root);
   var verified=new MoneyflowHsgtStaging(tables).verify(prepared,pages,()->false);
   // The explicit stopped-writer proof and complete empty-slice/stage proof retain a hard-stopped lease before renames.
   if(!lease.inDoubt())locks.retainInDoubt(lease);
   publication.publish(operation,verified,()->false);
  }
 }
 private static void requireSnapshot(JsonNode proof,MoneyflowHsgtState.Snapshot snapshot){
  var id=proof.path("identity");if(id.path("id").asLong(-1)!=snapshot.identity().id()||!id.path("directory").asText().equals(snapshot.identity().directory())||id.path("writerTxn").asLong(-1)!=snapshot.identity().writerTxn()
    ||!proof.path("fingerprint").asText().equals(snapshot.fingerprint())||proof.path("rows").asInt(-1)!=snapshot.rows().size()||proof.path("bytes").asInt(-1)!=snapshot.bytes())throw new IllegalStateException("D027 baseline snapshot proof changed");
 }
}
