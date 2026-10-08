package com.zoutrankil.batch;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MainStrategyLaunchRejectionTest {
    @TempDir Path temp;

    @Test
    void missingExecutableJobIsRejectedAndPersistsAnUncertainOutcome() throws Exception {
        var database = BatchLedgerTestDatabase.create(temp);
        var operator = mock(JobOperator.class);
        var launches = new LaunchService(database.ledger, operator, List.of());
        var request = request();

        var error = assertThrows(IllegalArgumentException.class, () -> launches.launch(request));

        assertEquals("Unregistered executable job", error.getMessage());
        assertEquals(BusinessState.IN_DOUBT, database.reopen().ledger.state(request.instanceId()));
        assertNotEquals(BusinessState.VERIFIED.name(), database.ledger.detail(request.instanceId()).get("business_state"));
        verifyNoInteractions(operator);
    }

    @Test
    void completedOperatorCannotVerifyAJobWithoutItsAuthoritativeCompletionProtocol() throws Exception {
        var database = BatchLedgerTestDatabase.create(temp);
        var job = mock(Job.class);
        when(job.getName()).thenReturn("main_strategy_daily");
        var execution = mock(JobExecution.class);
        when(execution.getId()).thenReturn(71L);
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        var operator = mock(JobOperator.class);
        when(operator.start(eq(job), any())).thenReturn(execution);
        var launches = new LaunchService(database.ledger, operator, List.of(job));
        var request = request();

        var result = launches.launch(request);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        assertEquals(BusinessState.IN_DOUBT.name(), result.get("business_state"));
        assertEquals(BusinessState.IN_DOUBT, database.reopen().ledger.state(request.instanceId()));
        assertEquals(71L, ((Number) result.get("batch_execution_id")).longValue());
        assertTrue(result.get("reason").toString().contains("completion protocol is unavailable"));
        assertTrue(database.ledger.stages(request.instanceId()).isEmpty());
        verify(operator).start(eq(job), any());
    }

    private static RunRequest request() {
        var day = LocalDate.of(2026, 9, 29);
        var at = Instant.parse("2026-09-29T10:30:00Z");
        return new RunRequest("main-strategy-test", "main_strategy_daily", day, day, day,
                "v1", "0", null, null, "source-v1", "calendar-v1", "Asia/Shanghai", at, at);
    }
}
