package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WriteGroupCommandTest {
    @Test void explicitRequestAndPriorRunArePassedAndUnknownDeliveryIsNotSuccess() throws Exception {
        var writer = mock(StockBasicWriteGroupService.class);
        var cli = new CommandLineRunner(mock(StockBasicSyncService.class), mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class), mock(StockBasicJobService.class), mock(StockBasicGroupService.class),
                mock(ReadGroupReader.class), writer);
        assertThrows(IllegalArgumentException.class, () -> cli.run(new DefaultApplicationArguments("write-dataset-group")));
        verifyNoInteractions(writer);
        when(writer.run(Path.of("write.json"), "prior-run"))
                .thenReturn(new SyncGroupRunner.Result("new-run", SyncRunState.IN_DOUBT, List.of()));
        assertThrows(IllegalStateException.class, () -> cli.run(new DefaultApplicationArguments(
                "write-dataset-group", "--request", "write.json", "--resume-from", "prior-run")));
        verify(writer).run(Path.of("write.json"), "prior-run");
    }
}
