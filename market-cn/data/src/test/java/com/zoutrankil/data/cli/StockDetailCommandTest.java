package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;

import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockDetailCommandTest {
    private final StockDetailInfoJobService owner=mock(StockDetailInfoJobService.class);
    private final CommandLineRunner cli=new CommandLineRunner(mock(StockBasicSyncService.class),
            mock(DatasetRegistry.class),mock(SyncJobRegistry.class),mock(StockBasicJobService.class),
            mock(StockBasicGroupService.class),mock(ReadGroupReader.class),
            mock(StockBasicWriteGroupService.class),mock(StockBasicScheduleService.class),
            mock(ExchangeCalendarJobService.class),owner);

    @Test void planningCannotExecuteAndAmbiguousScopeIsRejected() throws Exception {
        var date=LocalDate.of(2026,9,29);
        var frozen=StockDetailInfoJobService.definition().freeze(null,
                java.util.Map.of("discover",true),date,date,date);
        when(owner.plan(List.of(),true,date)).thenReturn(frozen);
        when(owner.targetId()).thenReturn("static-stock_detail_info-1");
        cli.run(new DefaultApplicationArguments("plan-stock-detail-job","--discover","true",
                "--logical-date","2026-09-29"));
        verify(owner,never()).run(any());
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-detail-job","--discover","true","--codes","000001.SZ",
                "--logical-date","2026-09-29")));
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "plan-stock-detail-job","--discover","true","--logical-date","2026-09-29",
                "--resume-from","old-run")));
        verify(owner,never()).run(any());
    }

    @Test void incompleteRunExitsAsFailureAndRecoveryNeedsWriterProof() throws Exception {
        var date=LocalDate.of(2026,9,29);
        var frozen=StockDetailInfoJobService.definition().freeze(null,
                java.util.Map.of("codes",List.of("000001.SZ"),"discover",false),date,date,date);
        when(owner.plan(List.of("000001.SZ"),false,date)).thenReturn(frozen);
        when(owner.run(frozen)).thenReturn(new StockDetailInfoJobService.Result("run-1",
                SyncRunState.IN_DOUBT,1,0,0,0,0,"publication-1","receipt","UNKNOWN"));
        assertThrows(IncompleteCommandException.class,()->cli.run(new DefaultApplicationArguments(
                "run-stock-detail-job","--codes","000001.SZ","--logical-date","2026-09-29")));
        when(owner.resume(frozen,"old-run")).thenReturn(new StockDetailInfoJobService.Result("run-2",
                SyncRunState.VERIFIED,1,0,0,1,1,null,"receipt",null));
        cli.run(new DefaultApplicationArguments("run-stock-detail-job","--codes","000001.SZ",
                "--logical-date","2026-09-29","--resume-from","old-run"));
        verify(owner).resume(frozen,"old-run");
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "reconcile-stock-detail-run","--run","run-1","--writer-stopped","false")));
        verify(owner,never()).reconcilePublished(anyString(),anyBoolean());
    }
    @Test void finishPublicationIsExplicitAndRequiresStoppedWriter() throws Exception {
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments(
                "finish-stock-detail-publication","--run","run-1","--writer-stopped","false")));
        verify(owner,never()).finishInterrupted(anyString(),anyBoolean());
        when(owner.finishInterrupted("run-1",true)).thenReturn(new StockDetailInfoJobService.Result("run-1",
                SyncRunState.VERIFIED,1,1,0,0,1,"publication-1","receipt",null));
        cli.run(new DefaultApplicationArguments("finish-stock-detail-publication","--run","run-1","--writer-stopped","true"));
        verify(owner).finishInterrupted("run-1",true);
        verify(owner,never()).run(any());verify(owner,never()).reconcilePublished(anyString(),anyBoolean());
    }
}
