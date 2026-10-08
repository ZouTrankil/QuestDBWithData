package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncService;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.domain.ReadGroupRequest;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.StockBasicSnapshot;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncJobCommandTest {
    @Test void missingBoundsNeverCallsSourceService() {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service,mock(StockBasicGroupService.class),mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class));
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments("run-stock-basic-job")));
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-basic-job","--codes","000001.SZ")));
        verifyNoInteractions(service);
    }
    @Test void passesExplicitCodesAndLogicalDateAndFailsUncertainResult() throws Exception {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service,mock(StockBasicGroupService.class),mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class));
        var codes=List.of("000001.SZ","600000.SH");
        var day=LocalDate.of(2026,9,29);
        when(service.run(codes,day)).thenReturn(new SyncJobRunner.Result("run-test",SyncRunState.IN_DOUBT,2,0,"unknown"));
        assertThrows(IllegalStateException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-basic-job","--codes","000001.SZ,600000.SH","--logical-date","2026-09-29")));
        verify(service).run(codes,day);
    }

    @Test void resumeCommandPassesPriorRunAndNeverStartsFreshRun() throws Exception {
        var service=mock(StockBasicJobService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),mock(SyncJobRegistry.class),service,mock(StockBasicGroupService.class),mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class));
        var codes=List.of("000001.SZ","600000.SH");
        var day=LocalDate.of(2026,9,29);
        when(service.resume(codes,day,"prior-run")).thenReturn(
                new SyncJobRunner.Result("resumed-run",SyncRunState.VERIFIED,2,2,null,1));
        cli.run(new DefaultApplicationArguments("run-stock-basic-job","--codes","000001.SZ,600000.SH",
                "--logical-date","2026-09-29","--resume-from","prior-run"));
        verify(service).resume(codes,day,"prior-run");
        verify(service,never()).run(anyList(),any());
    }

    @Test void groupCommandRequiresExplicitBoundsAndPassesResumeIdentity() throws Exception {
        var group=mock(StockBasicGroupService.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class),mock(StockBasicJobService.class),group,mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class));
        assertThrows(IllegalArgumentException.class,
                ()->cli.run(new DefaultApplicationArguments("run-stock-basic-group","--codes","000001.SZ")));
        verifyNoInteractions(group);
        var codes=List.of("000001.SZ"); var day=LocalDate.of(2026,9,29);
        when(group.run(codes,day,"prior-group")).thenReturn(new SyncGroupRunner.Result(
                "new-group",SyncRunState.VERIFIED,List.of()));
        cli.run(new DefaultApplicationArguments("run-stock-basic-group","--codes","000001.SZ",
                "--logical-date","2026-09-29","--resume-from","prior-group"));
        verify(group).run(codes,day,"prior-group");
    }

    @Test void readGroupCommandKeepsEachCodeInItsOwnBoundedMember() throws Exception {
        var read=mock(ReadGroupReader.class);
        var cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class),mock(StockBasicJobService.class),mock(StockBasicGroupService.class),read,mock(StockBasicWriteGroupService.class));
        assertThrows(IllegalArgumentException.class, ()->cli.run(new DefaultApplicationArguments(
                "read-stock-basic-group","--codes","000001.SZ")));
        verifyNoInteractions(read);
        var page=new DatasetReadPage<StockBasicSnapshot>("stock_basic_snapshot",1,null,Instant.now(),List.of(),null);
        var member=new ReadGroupReader.MemberResult("code0","stock_basic_snapshot",1,
                StockBasicSnapshot.class,ReadGroupReader.Status.READ,page,null);
        when(read.read(any(),any())).thenReturn(new ReadGroupReader.Result(Instant.now(),Instant.now(),List.of(member)));
        cli.run(new DefaultApplicationArguments("read-stock-basic-group","--codes","000001.SZ,600000.SH",
                "--from","2026-09-29","--to","2026-09-30","--page-size","2"));
        var request=org.mockito.ArgumentCaptor.forClass(ReadGroupRequest.class);
        verify(read).read(request.capture(),any());
        assertEquals(2,request.getValue().members().size());
        assertEquals(2,request.getValue().members().getFirst().query().pageSize());
        assertEquals("000001.SZ",request.getValue().members().getFirst().query().equalities().get("ts_code"));
        assertEquals("600000.SH",request.getValue().members().getLast().query().equalities().get("ts_code"));
    }
}
