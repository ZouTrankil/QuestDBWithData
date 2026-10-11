package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FactorPipelineCommandsTest {
    @TempDir Path root;
    private Map<String,String> options() {
        return Map.of("--date","2026-09-30","--factors","reversal_1d","--pools","all_market","--root",root.toString());
    }
    @Test void failedComputeRetainsPhaseAndDoesNotPublish() throws Exception {
        var panel=mock(FactorPanelCommands.class);var publisher=mock(FactorObservationCommands.class);
        doThrow(new IllegalStateException("source changed")).when(panel).execute(eq("compute-factor-panel"),anyMap());
        assertThrows(IllegalStateException.class,()->new FactorPipelineCommands(panel,publisher).execute("run-factor-pipeline",options()));
        verifyNoInteractions(publisher);
        try(var files=Files.walk(root)) {
            var receipt=files.filter(p->p.getFileName().toString().equals("pipeline.json")).findFirst().orElseThrow();
            var value=JobDefinitionJson.mapper().readTree(Files.readAllBytes(receipt));
            assertEquals("FAILED_REQUIRES_INSPECTION",value.path("phase").asText());
            assertEquals("COMPUTING",value.path("interruptedPhase").asText());
            assertFalse(value.path("factorReady").asBoolean());
            assertFalse(value.has("publicationRunId"));
        }
    }
    @Test void rootLeaseExcludesConcurrentExecution() throws Exception {
        var panel=mock(FactorPanelCommands.class);var publisher=mock(FactorObservationCommands.class);
        try(var channel=FileChannel.open(root.resolve("pipeline.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                var held=channel.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class,
                    ()->new FactorPipelineCommands(panel,publisher).execute("run-factor-pipeline",options()));
        }
        verifyNoInteractions(panel,publisher);
    }
}
