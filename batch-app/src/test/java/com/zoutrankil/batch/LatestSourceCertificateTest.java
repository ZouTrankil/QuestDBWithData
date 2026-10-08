package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class LatestSourceCertificateTest {
    @TempDir Path temp;

    @Test void newerFailedRevisionSupersedesOlderVerifiedSourceCertificate() {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("metadata.sqlite"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/batch").load().migrate();
        var ledger=new SqliteLedger(dataSource);var date=LocalDate.of(2026,9,28);var now=Instant.parse("2026-09-28T12:00:00Z");
        var scope=RunRequest.hash("daily","frozen-universe-v1");
        var first=request("source-daily-r0","0",null,null,"daily-input-r0",scope,date,now);
        ledger.register(first);ledger.completeSource(first,new StageExecutor.Result(BusinessState.VERIFIED,evidence(first),null),1L);
        var second=request("source-daily-r1","1",first.instanceId(),"provider correction","daily-input-r1",scope,date,now);
        ledger.register(second);ledger.completeSource(second,new StageExecutor.Result(BusinessState.PARTIAL,null,"provider-incomplete"),2L);
        var latest=ledger.latestSourceCertificates(date);
        assertEquals("source_daily",latest.get("daily").job());
        assertEquals(BusinessState.PARTIAL,latest.get("daily").state());
        assertTrue(latest.get("daily").requestJson().contains("daily-input-r1"));
    }
    private static RunRequest request(String requestId,String revision,String supersedes,String reason,String input,String scope,LocalDate date,Instant now) {
        return new RunRequest(requestId,"source_daily",date,date,date,"daily-v1",revision,supersedes,reason,input,"calendar-v1",
                "Asia/Shanghai",now,now,scope);
    }
    private static CompletionEvidence evidence(RunRequest request) {
        return new CompletionEvidence(1,"java-source:daily",request.instanceId(),"Source:daily","batch-daily",request.logicalDate(),
                request.rangeStart(),request.rangeEnd(),request.rangeStart(),request.rangeEnd(),request.definitionVersion(),"provider-v1",
                request.inputFingerprint(),1,1,true,true,true,true,true,false,request.triggeredAt(),BusinessState.VERIFIED,"fixture://daily",null);
    }
}
