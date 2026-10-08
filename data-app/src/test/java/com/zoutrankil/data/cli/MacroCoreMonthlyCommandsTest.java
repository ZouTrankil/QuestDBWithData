package com.zoutrankil.data.cli;

import com.zoutrankil.data.derived.application.MacroCoreMonthlyJobService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MacroCoreMonthlyCommandsTest {
    @Test void missingOwnerIsRejected() {
        assertThrows(NullPointerException.class,()->MacroCoreMonthlyCommands.execute(
                "install-macro-core-monthly-isolated",Map.of(),null));
    }
    @Test void installRejectsFlagsBeforeOwnerIo() {
        var owner=mock(MacroCoreMonthlyJobService.class);
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyCommands.execute(
                "install-macro-core-monthly-isolated",Map.of("--force","true"),owner));
        verifyNoInteractions(owner);
    }
    @Test void planRequiresExplicitDatesAndOnlyKnownFlags() {
        var owner=mock(MacroCoreMonthlyJobService.class);
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyCommands.execute(
                "plan-macro-core-monthly-job",Map.of("--from","2026-06-01","--to","2026-07-01"),owner));
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyCommands.execute(
                "plan-macro-core-monthly-job",Map.of("--from","2026-06-01","--to","2026-07-01",
                        "--logical-date","2026-10-07","--force","true"),owner));
        verifyNoInteractions(owner);
    }
    @Test void malformedDateIsRejectedBeforeOwnerIo() {
        var owner=mock(MacroCoreMonthlyJobService.class);
        assertThrows(java.time.format.DateTimeParseException.class,()->MacroCoreMonthlyCommands.execute(
                "run-macro-core-monthly-job",Map.of("--from","202606","--to","2026-07-01",
                        "--logical-date","2026-10-07"),owner));
        verifyNoInteractions(owner);
    }
    @Test void statusRequiresOnlyRunFlagBeforeOwnerIo() {
        var owner=mock(MacroCoreMonthlyJobService.class);
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyCommands.execute(
                "macro-core-monthly-job-status",Map.of("--run","d104-example","--mode","FULL"),owner));
        verifyNoInteractions(owner);
    }
    @Test void unknownCommandIsRejectedBeforeOwnerIo() {
        var owner=mock(MacroCoreMonthlyJobService.class);
        assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyCommands.execute(
                "full-macro-core-monthly-job",Map.of(),owner));
        verifyNoInteractions(owner);
    }
}
