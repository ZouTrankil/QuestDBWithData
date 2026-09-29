package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.SyncRunState;
import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncJobCommandTest {
    @Test void missingBoundsNeverCallsSourceService() {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service);
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments("run-stock-basic-job")));
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-basic-job","--codes","000001.SZ")));
        verifyNoInteractions(service);
    }
    @Test void passesExplicitCodesAndLogicalDateAndFailsUncertainResult() throws Exception {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service);
        var codes=List.of("000001.SZ","600000.SH");
        var day=LocalDate.of(2026,9,29);
        when(service.run(codes,day)).thenReturn(new SyncJobRunner.Result("run-test",SyncRunState.IN_DOUBT,2,0,"unknown"));
        assertThrows(IllegalStateException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-basic-job","--codes","000001.SZ,600000.SH","--logical-date","2026-09-29")));
        verify(service).run(codes,day);
    }

    @Test void resumeCommandPassesPriorRunAndNeverStartsFreshRun() throws Exception {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service);
        var codes=List.of("000001.SZ","600000.SH");
        var day=LocalDate.of(2026,9,29);
        when(service.resume(codes,day,"prior-run")).thenReturn(
                new SyncJobRunner.Result("resumed-run",SyncRunState.VERIFIED,2,2,null,1));
        cli.run(new DefaultApplicationArguments("run-stock-basic-job","--codes","000001.SZ,600000.SH",
                "--logical-date","2026-09-29","--resume-from","prior-run"));
        verify(service).resume(codes,day,"prior-run");
        verify(service,never()).run(anyList(),any());
    }
}
