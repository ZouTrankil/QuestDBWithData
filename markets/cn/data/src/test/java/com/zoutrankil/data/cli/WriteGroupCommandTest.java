package com.zoutrankil.data.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.StockBasicGroupService;
import com.zoutrankil.data.service.StockBasicWriteGroupService;
import com.zoutrankil.data.service.SyncGroupRunner;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Path;
import java.util.List;

class WriteGroupCommandTest {
    @Test
    void explicitRequestAndPriorRunArePassedAndUnknownDeliveryIsNotSuccess() throws Exception {
        var writer = mock(StockBasicWriteGroupService.class);
        var cli =
                new CommandLineRunner(
                        mock(StockBasicSyncService.class),
                        mock(DatasetRegistry.class),
                        mock(SyncJobRegistry.class),
                        mock(StockBasicJobService.class),
                        mock(StockBasicGroupService.class),
                        mock(ReadGroupReader.class),
                        writer);
        assertThrows(
                IllegalArgumentException.class,
                () -> cli.run(new DefaultApplicationArguments("write-dataset-group")));
        verifyNoInteractions(writer);
        when(writer.run(Path.of("write.json"), "prior-run"))
                .thenReturn(
                        new SyncGroupRunner.Result("new-run", SyncRunState.IN_DOUBT, List.of()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        cli.run(
                                new DefaultApplicationArguments(
                                        "write-dataset-group",
                                        "--request",
                                        "write.json",
                                        "--resume-from",
                                        "prior-run")));
        verify(writer).run(Path.of("write.json"), "prior-run");
    }
}
