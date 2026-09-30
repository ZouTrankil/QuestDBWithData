package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DailyBasicJobCommandTest {
    @Test void omittedToDefaultsToLogicalDateBeforeCallingTheOwner() throws Exception {
        var service = mock(DailyBasicJobService.class);
        var cli = cli(service);
        LocalDate logicalDate = LocalDate.of(2026, 9, 30);
        doThrow(new IllegalStateException("captured plan arguments"))
                .when(service).plan(null, logicalDate, logicalDate, null);

        assertThrows(IllegalStateException.class, () -> cli.run(new DefaultApplicationArguments(
                "plan-daily-basic-job", "--logical-date", logicalDate.toString())));
        verify(service).plan(null, logicalDate, logicalDate, null);
    }

    @Test void explicitFutureToIsPassedToTheOwnerForLogicalDateRejection() throws Exception {
        var service = mock(DailyBasicJobService.class);
        var cli = cli(service);
        LocalDate logicalDate = LocalDate.of(2026, 9, 30);
        LocalDate futureDate = LocalDate.of(2026, 10, 1);
        doThrow(new IllegalArgumentException("future bound"))
                .when(service).plan(null, futureDate, logicalDate, null);

        assertThrows(IllegalArgumentException.class, () -> cli.run(new DefaultApplicationArguments(
                "plan-daily-basic-job", "--logical-date", logicalDate.toString(), "--to", futureDate.toString())));
        verify(service).plan(null, futureDate, logicalDate, null);
    }

    private static CommandLineRunner cli(DailyBasicJobService service) throws Exception {
        var runner = new CommandLineRunner(mock(StockBasicSyncService.class), mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class), mock(StockBasicJobService.class), mock(StockBasicGroupService.class),
                mock(ReadGroupReader.class), mock(StockBasicWriteGroupService.class));
        var field = CommandLineRunner.class.getDeclaredField("dailyBasicService");
        field.setAccessible(true);
        field.set(runner, service);
        return runner;
    }
}
