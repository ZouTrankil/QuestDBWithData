package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarSyncAdapter;
import com.zoutrankil.data.service.*;
import com.zoutrankil.data.domain.SyncRunState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalendarCommandTest {
    private final ExchangeCalendarJobService owner=mock(ExchangeCalendarJobService.class);
    private final CommandLineRunner cli=new CommandLineRunner(mock(StockBasicSyncService.class),mock(DatasetRegistry.class),
            mock(SyncJobRegistry.class),mock(StockBasicJobService.class),mock(StockBasicGroupService.class),
            mock(ReadGroupReader.class),mock(StockBasicWriteGroupService.class),mock(StockBasicScheduleService.class),owner);
    private String[] args(String command) { return new String[]{command,"--exchanges","SSE,SZSE","--from","2026-09-25",
            "--to","2026-09-28","--logical-date","2026-09-29"}; }
    @Test void boundedCalendarPlanDoesNotInvokeExecution() throws Exception {
        var from=LocalDate.of(2026,9,25);var to=LocalDate.of(2026,9,28);var day=LocalDate.of(2026,9,29);
        var request=ExchangeCalendarSyncAdapter.definition(true).freeze(null,Map.of("exchanges",List.of("SSE","SZSE")),from,to,day);
        when(owner.plan(List.of("SSE","SZSE"),from,to,day,null))
                .thenReturn(new ExchangeCalendarJobService.Plan(request,"target",Map.of(),0));
        cli.run(new DefaultApplicationArguments(args("plan-exchange-calendar")));
        verify(owner,never()).run(anyList(),any(),any(),any(),any());
        verify(owner,never()).execute(any(),any());
    }
    @Test void missingBoundsNeverExecuteAndUnknownWriteReturnsIncomplete() throws Exception {
        assertThrows(IllegalArgumentException.class,()->cli.run(new DefaultApplicationArguments("run-exchange-calendar")));
        verifyNoInteractions(owner);
        when(owner.run(anyList(),any(),any(),any(),isNull()))
                .thenReturn(new SyncJobRunner.Result("calendar-run",SyncRunState.IN_DOUBT,8,0,"unknown"));
        assertThrows(IncompleteCommandException.class,()->cli.run(new DefaultApplicationArguments(args("run-exchange-calendar"))));
    }
}
