package com.zoutrankil.data.cli;

import com.zoutrankil.data.flow.application.MoneyflowHsgtJobService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlowCommandsRecoveryTest {
    @Test void stageDiscardRequiresExplicitStoppedWriterBeforeCallingOwner()throws Exception {
        var owner=mock(MoneyflowHsgtJobService.class);
        var commands=new FlowCommands(owner,null,null,null);
        for(var options:java.util.List.of(Map.of("--run","original"),
                Map.of("--run","original","--writer-stopped","false"),
                Map.of("--run","original","--writer-stopped","true","--all","true")))
            assertThrows(IllegalArgumentException.class,()->commands.execute("discard-moneyflow-hsgt-stage",options));
        verifyNoInteractions(owner);
        commands.execute("discard-moneyflow-hsgt-stage",Map.of("--run","original","--writer-stopped","true"));
        verify(owner).discardUnpublished("original",true);
    }
}
