package com.zoutrankil.batch;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PostCloseRecoveryCoordinatorTest {
    @TempDir Path temp;
    private static final LocalDate DATE=LocalDate.of(2026,9,29);
    private static final TradingCalendar CALENDAR=new TradingCalendar("calendar-v1",DATE,DATE.plusDays(1),new TreeSet<>(List.of(DATE,DATE.plusDays(1))));

    @Test void newScheduledProbeReusesBusinessIdentityAndPersistsTheAttemptBeforeLaunch() throws Exception {
        var ledger=ledger("recovery.sqlite");var request=blocked(ledger,"recovery-one");var launches=launches();
        var coordinator=new PostCloseRecoveryCoordinator(ledger,launches);Instant first=at(19,0);
        coordinator.probe("post_close_recovery",first,first,"fire-one",CALENDAR);
        var sent=captured(launches);
        assertEquals(1,sent.size());assertEquals(request.instanceId(),sent.getFirst().instanceId());
        assertNotEquals(request.requestId(),sent.getFirst().requestId());assertEquals(1,ledger.recoveryAttempts(request.instanceId()));
        assertEquals(first.plus(Duration.ofMinutes(15)),ledger.nextRecoveryAttempt(request.instanceId()));
    }

    @Test void duplicateFireAndEarlyDistinctFireCannotClaimTwice() throws Exception {
        var ledger=ledger("duplicate.sqlite");var request=blocked(ledger,"recovery-duplicate");var launches=launches();
        var coordinator=new PostCloseRecoveryCoordinator(ledger,launches);Instant first=at(19,0);
        coordinator.probe("post_close_recovery",first,first,"same-fire",CALENDAR);
        coordinator.probe("post_close_recovery",first,first,"same-fire",CALENDAR);
        coordinator.probe("post_close_recovery",first,first,"other-fire-same-slot",CALENDAR);
        assertEquals(1,ledger.recoveryAttempts(request.instanceId()));verify(launches,times(1)).launch(any());
        Instant due=first.plus(Duration.ofMinutes(15));
        coordinator.probe("post_close_recovery",due,due,"next-slot",CALENDAR);
        assertEquals(2,ledger.recoveryAttempts(request.instanceId()));verify(launches,times(2)).launch(any());
    }

    @Test void inDoubtAndCrossMidnightMisfiresAreNeverReplayed() throws Exception {
        var ledger=ledger("no-replay.sqlite");var request=blocked(ledger,"recovery-no-replay");var launches=launches();
        var coordinator=new PostCloseRecoveryCoordinator(ledger,launches);Instant first=at(19,0);
        ledger.state(request.instanceId(),BusinessState.IN_DOUBT,null,"unknown prior outcome");
        coordinator.probe("post_close_recovery",first,first,"in-doubt",CALENDAR);
        assertEquals(0,ledger.recoveryAttempts(request.instanceId()));
        ledger.state(request.instanceId(),BusinessState.BLOCKED,null,"known blocked outcome");
        Instant late=Instant.parse("2026-09-29T16:01:00Z"); // 00:01 next local date
        coordinator.probe("post_close_recovery_final",at(23,55),late,"late-fire",CALENDAR);
        assertEquals(0,ledger.recoveryAttempts(request.instanceId()));verifyNoInteractions(launches);
    }

    @Test void regularAndFinalWindowsHaveTwentyOneDurableAttemptSlots() throws Exception {
        var ledger=ledger("budget.sqlite");var request=blocked(ledger,"recovery-budget");var launches=launches();
        var coordinator=new PostCloseRecoveryCoordinator(ledger,launches);Instant first=at(19,0);
        for(int slot=0;slot<20;slot++) {
            Instant time=first.plus(Duration.ofMinutes(15L*slot));
            coordinator.probe("post_close_recovery",time,time,"regular-"+slot,CALENDAR);
        }
        Instant last=at(23,55);
        coordinator.probe("post_close_recovery_final",last,last,"final",CALENDAR);
        coordinator.probe("post_close_recovery_final",last,last.plusSeconds(1),"over-budget",CALENDAR);
        assertEquals(PostCloseRecoveryCoordinator.MAX_ATTEMPTS,ledger.recoveryAttempts(request.instanceId()));
        verify(launches,times(PostCloseRecoveryCoordinator.MAX_ATTEMPTS)).launch(any());
    }

    private SqliteLedger ledger(String file) {
        var dataSource=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve(file));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/batch").load().migrate();
        return new SqliteLedger(dataSource);
    }
    private static RunRequest blocked(SqliteLedger ledger,String id) {
        var request=new RunRequest(id,"post_close",DATE,DATE,DATE,"post-close-v1","0",null,null,
                RunRequest.hash("frozen-plan"),"calendar-v1","Asia/Shanghai",at(18,30),at(18,31),RunRequest.hash("frozen-plan"));
        ledger.register(request);ledger.state(request.instanceId(),BusinessState.BLOCKED,null,"fixture blocked");return request;
    }
    private static LaunchService launches() throws Exception {
        var launches=mock(LaunchService.class);when(launches.launch(any())).thenReturn(Map.of("business_state","BLOCKED"));return launches;
    }
    private static List<RunRequest> captured(LaunchService launches) throws Exception {
        var captor=org.mockito.ArgumentCaptor.forClass(RunRequest.class);verify(launches,atLeastOnce()).launch(captor.capture());return captor.getAllValues();
    }
    private static Instant at(int hour,int minute) { return LocalDateTime.of(DATE,LocalTime.of(hour,minute)).atZone(RecoveryPolicy.ZONE).toInstant(); }
}
