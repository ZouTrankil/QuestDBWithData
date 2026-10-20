package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class FlowCommandsRecoveryTest {
    @Test
    void stageDiscardRequiresExplicitStoppedWriterBeforeCallingOwner() throws Exception {
        var owner = mock(MoneyflowHsgtJobService.class);
        var commands = new FlowCommands(owner, null, null, null);
        for (var options :
                List.of(
                        Map.of("--run", "original"),
                        Map.of("--run", "original", "--writer-stopped", "false"),
                        Map.of("--run", "original", "--writer-stopped", "true", "--all", "true")))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> commands.execute("discard-moneyflow-hsgt-stage", options));
        verifyNoInteractions(owner);
        commands.execute(
                "discard-moneyflow-hsgt-stage",
                Map.of("--run", "original", "--writer-stopped", "true"));
        verify(owner).discardUnpublished("original", true);
    }
}
