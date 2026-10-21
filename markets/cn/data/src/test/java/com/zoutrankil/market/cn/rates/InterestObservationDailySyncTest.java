package com.zoutrankil.market.cn.rates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.zoutrankil.batch.source.SourceContract;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.SyncJobRunner.Result;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.port.DateObservationTarget;
import com.zoutrankil.market.cn.rates.application.InterestObservationDailySync;
import com.zoutrankil.market.cn.rates.application.InterestObservationSyncOwner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

class InterestObservationDailySyncTest {
    @TempDir Path root;

    @Test
    void leapDayRemainsInWindowAndOneFailureDoesNotHideOtherSources() throws Exception {
        var owners = new ArrayList<InterestObservationSyncOwner>();
        for (String dataset : InterestObservationSyncOwner.DATASETS) {
            var definition =
                    InterestObservationSyncOwner.writable(
                            InterestObservationSyncTest.readDefinition(dataset));
            var owner =
                    spy(
                            new InterestObservationSyncOwner(
                                    SourceContract.load(dataset),
                                    definition,
                                    mock(DateObservationTarget.class),
                                    mock(TusharePageService.class),
                                    root.resolve("ledger.sqlite3")));
            doReturn(
                            new Result(
                                    dataset,
                                    dataset.equals("hibor")
                                            ? SyncRunState.FAILED
                                            : SyncRunState.VERIFIED,
                                    1,
                                    1,
                                    null))
                    .when(owner)
                    .run(any());
            owners.add(owner);
        }
        var day = LocalDate.parse("2024-03-01");
        var sync = new InterestObservationDailySync(owners, 2);
        var failure = assertThrows(IllegalStateException.class, () -> sync.runAt(day));
        assertEquals(1, failure.getSuppressed().length);
        for (var owner : owners) {
            verify(owner).plan(LocalDate.parse("2024-02-29"), day, day, Mode.RECONCILE);
            verify(owner).run(any());
        }
    }

    @Test
    void finiteCliNeverStartsInterestScheduler() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test",
                                    Map.of("app.sync.interest-observations.enabled", "true")));
            context.register(InterestObservationDailySync.class);
            context.refresh();
            assertEquals(0, context.getBeansOfType(InterestObservationDailySync.class).size());
        }
    }
}
