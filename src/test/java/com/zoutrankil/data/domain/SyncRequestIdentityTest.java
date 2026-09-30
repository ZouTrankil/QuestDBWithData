package com.zoutrankil.data.domain;

import com.zoutrankil.data.service.StockBasicSyncAdapter;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyncRequestIdentityTest {
    @Test void identityChangesWithTargetParametersOrLogicalDate() {
        var job=StockBasicSyncAdapter.definition(true);
        var day=LocalDate.of(2026,9,29);
        var request=job.freeze(null,Map.of("codes",List.of("000001.SZ","600000.SH")),null,null,day);
        var first=SyncRequestIdentity.fingerprint(request,"target-a");
        assertEquals(64,first.length());
        assertNotEquals(first,SyncRequestIdentity.fingerprint(request,"target-b"));
        assertNotEquals(first,SyncRequestIdentity.fingerprint(job.freeze(null,
                Map.of("codes",List.of("600000.SH","000001.SZ")),null,null,day),"target-a"));
        assertNotEquals(first,SyncRequestIdentity.fingerprint(job.freeze(null,
                Map.of("codes",List.of("000001.SZ","600000.SH")),null,null,day.plusDays(1)),"target-a"));
    }
    @Test void objectFormattingDoesNotChangeIdentityButSameVersionPolicyEditsDo() throws Exception {
        var request=StockBasicSyncAdapter.definition(true).freeze(null,Map.of("codes",List.of("000001.SZ")),
                null,null,LocalDate.of(2026,9,29));
        String snapshot=SyncRequestIdentity.snapshotJson(request);
        var json=JobDefinitionJson.mapper(); var tree=json.readTree(snapshot);
        String expected=SyncRequestIdentity.fingerprint(snapshot,"target");
        assertEquals(expected,SyncRequestIdentity.fingerprint(json.writerWithDefaultPrettyPrinter().writeValueAsString(tree),"target"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("definition")).put("timeout","PT1M");
        assertNotEquals(expected,SyncRequestIdentity.fingerprint(tree.toString(),"target"));
        assertThrows(IllegalArgumentException.class,()->SyncRequestIdentity.fingerprint("{}","target"));
    }
}
