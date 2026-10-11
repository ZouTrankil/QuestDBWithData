package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Local CLI recovery evidence: one captured invocation, matching terminal run, absent original process. */
public record FactorWriterExitEvidence(long pid,String logSha256,String runId) {
    public static FactorWriterExitEvidence inspect(Path log,String expectedRun) throws Exception {
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
        if(!matched)throw new IllegalArgumentException("Uncertain run terminal record missing");
        var proof=new FactorWriterExitEvidence(pid,FileEvidenceStore.sha256(bytes),expectedRun);
        proof.requireStopped();return proof;
    }
    public void requireStopped() {
        if(pid<=0 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
            throw new IllegalStateException("Original writer process is still present; cannot reconcile");
    }
}
