package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Local CLI recovery evidence: one captured invocation, matching terminal run, absent original process. */
public record FactorWriterExitEvidence(long pid,String logSha256,String runId) {
    public static FactorWriterExitEvidence inspect(Path log,String expectedRun) throws Exception {
        return inspect(log,expectedRun,null);
    }
    public static FactorWriterExitEvidence inspect(Path log,String expectedRun,Path ledger) throws Exception {
        byte[] bytes=FileEvidenceStore.readBounded(log,4*1024*1024,
                ()->new IllegalArgumentException("Writer log exceeds evidence budget"));
        String text=new String(bytes,StandardCharsets.UTF_8);
        var starts=Pattern.compile("Starting QuestDataApplication using Java[^\\r\\n]* with PID ([0-9]+)").matcher(text);
        if(!starts.find())throw new IllegalArgumentException("Original writer startup record missing");
        long pid=Long.parseLong(starts.group(1));
        if(starts.find())throw new IllegalArgumentException("Multiple writer invocations in log");
        boolean matched=false;
        for(String line:text.split("\\R")) {
            if(!line.startsWith("{\"runId\":"))continue;
            var result=JobDefinitionJson.mapper().readTree(line);
            if(matched || !expectedRun.equals(result.path("runId").asText())
                    || !"IN_DOUBT".equals(result.path("state").asText()))
                throw new IllegalArgumentException("Writer terminal record differs or is ambiguous");
            matched=true;
        }
        if(!matched) {
            if(ledger==null || !Pattern.compile("finished with non-zero exit value [1-9][0-9]*").matcher(text).find())
                throw new IllegalArgumentException("Uncertain run terminal record missing");
            // Legacy writers predate the durable PID receipt. Bind their captured UTC
            // invocation to exactly one factor run creation in its observed time window.
            var times=Pattern.compile("(?m)^([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]{12}) ").matcher(text);
            java.time.Instant first=null,last=null;
            var format=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            while(times.find()) {
                var time=java.time.LocalDateTime.parse(times.group(1),format).toInstant(java.time.ZoneOffset.UTC);
                if(first==null)first=time;last=time;
            }
            if(first==null || last==null)throw new IllegalArgumentException("Writer time window missing");
            try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+ledger.toAbsolutePath().toUri()+"?mode=ro");
                    var query=db.prepareStatement("SELECT DISTINCT r.id FROM sync_runs r JOIN sync_events e ON e.entry_id=r.id WHERE r.job_id='data.factor_observation' AND e.revision=0 AND e.updated_at>=? AND e.updated_at<=?")) {
                query.setString(1,first.toString());query.setString(2,last.plusMillis(1).toString());
                try(var rows=query.executeQuery()) {
                    if(!rows.next() || !expectedRun.equals(rows.getString(1)) || rows.next())
                        throw new IllegalArgumentException("Crashed invocation does not uniquely identify the factor run");
                }
            }
        }
        var proof=new FactorWriterExitEvidence(pid,FileEvidenceStore.sha256(bytes),expectedRun);
        proof.requireStopped();return proof;
    }
    public void requireStopped() {
        if(pid<=0 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            throw new IllegalStateException("Original writer process is still present; cannot reconcile");
    }
}
