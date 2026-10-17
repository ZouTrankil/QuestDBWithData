package com.zoutrankil.data.factor;

import com.zoutrankil.data.factor.application.L2SourceScope;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class L2SourceScopeTest {
    @TempDir Path directory;
    private final LocalDate date=LocalDate.parse("2026-09-30");
    private Path file() {return directory.resolve("dfcf_l2_process_20260930.json");}
    @Test void absentHistoricalReceiptIsAllowedButMalformedReceiptIsBlocked() throws Exception {
        assertEquals("unrecorded",L2SourceScope.inspect(directory,date).status());
        assertTrue(L2SourceScope.assess(L2SourceScope.inspect(directory,date),List.of()).publishable());
        Files.writeString(file(),"{");
        assertFalse(L2SourceScope.assess(L2SourceScope.inspect(directory,date),List.of()).publishable());
    }
    @Test void partialArchiveMustBeOutsideEveryReadyPool() throws Exception {
        Files.writeString(file(),"{\"date\":\"20260930\",\"total_symbols\":2,\"requested_symbols\":1,\"excluded_corrupt_symbols\":[\" 600000.sh \"]}");
        var receipt=L2SourceScope.inspect(directory,date);
        assertEquals("partial_archive",receipt.status());assertEquals(64,receipt.sha256().length());
        assertEquals("blocked_partial_scope_unresolved",L2SourceScope.assess(receipt,List.of()).status());
        assertEquals("partial_scope_safe",L2SourceScope.assess(receipt,List.of(new L2SourceScope.Pool("p",true,Set.of("600001.SH")))).status());
        assertEquals("blocked_partial_scope_affected",L2SourceScope.assess(receipt,List.of(new L2SourceScope.Pool("p",true,Set.of("600000.SH")))).status());
        assertFalse(L2SourceScope.assess(receipt,List.of(new L2SourceScope.Pool("p",false,Set.of("600001.SH")))).publishable());
    }
    @Test void failedRunsWrongDatesAndCountsCannotPass() throws Exception {
        for(String payload:List.of("{\"date\":\"20260929\"}",
                "{\"date\":\"20260930\",\"run_state\":\"running\"}",
                "{\"date\":\"20260930\",\"total_symbols\":2,\"requested_symbols\":3}",
                "{\"date\":\"20260930\",\"total_symbols\":2,\"requested_symbols\":2,\"failed_batches\":1}")) {
            Files.writeString(file(),payload);
            assertFalse(L2SourceScope.assess(L2SourceScope.inspect(directory,date),List.of()).publishable());
        }
    }
}
