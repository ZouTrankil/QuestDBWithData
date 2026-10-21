package com.zoutrankil.market.cn.macro;

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
import com.zoutrankil.market.cn.macro.application.MacroObservationDailySync;
import com.zoutrankil.market.cn.macro.application.MacroObservationSyncOwner;
import com.zoutrankil.market.cn.macro.mapper.MacroObservationDefinition;
import com.zoutrankil.market.cn.macro.port.MacroObservationTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class MacroObservationDailySyncTest {
    @TempDir Path root;

    List<MacroObservationSyncOwner> owners() throws Exception {
        var owners = new ArrayList<MacroObservationSyncOwner>();
        for (String dataset : MacroObservationDefinition.DATASETS) {
            var source = SourceContract.load(dataset);
            var owner =
                    spy(
                            new MacroObservationSyncOwner(
                                    source,
                                    MacroObservationDefinition.from(source),
                                    mock(MacroObservationTarget.class),
                                    mock(TusharePageService.class),
                                    root.resolve("ledger.sqlite3")));
            doReturn(new Result(dataset, SyncRunState.VERIFIED, 1, 1, null)).when(owner).run(any());
            owners.add(owner);
        }
        return owners;
    }

    @Test
    void firstDayOfNewYearUsesEndedPeriodsAndAnUnpublishedSourceDoesNotHideOtherSources()
            throws Exception {
        var owners = owners();
        var cpi =
                owners.stream()
                        .filter(owner -> owner.datasetId().equals("cn_cpi"))
                        .findFirst()
                        .orElseThrow();
        doReturn(new Result("unpublished-cpi", SyncRunState.FAILED, 0, 0, "WAITING_SOURCE"))
                .when(cpi)
                .run(any());
        LocalDate day = LocalDate.parse("2027-01-01");
        var sync = new MacroObservationDailySync(owners, 6, 4);
        var failure = assertThrows(IllegalStateException.class, () -> sync.runAt(day));
        assertEquals(1, failure.getSuppressed().length);
        for (var owner : owners) {
            if (owner.datasetId().equals("cn_gdp"))
                verify(owner)
                        .plan(
                                LocalDate.parse("2026-03-31"),
                                LocalDate.parse("2026-12-31"),
                                day,
                                Mode.RECONCILE);
            else
                verify(owner)
                        .plan(
                                LocalDate.parse("2026-07-01"),
                                LocalDate.parse("2026-12-01"),
                                day,
                                Mode.RECONCILE);
            verify(owner).run(any());
        }
    }

    @Test
    void finiteCliCannotStartMacroSchedulerEvenWithEnabledProperty() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test", Map.of("app.sync.macro-observations.enabled", "true")));
            context.register(MacroObservationDailySync.class);
            context.refresh();
            assertEquals(0, context.getBeansOfType(MacroObservationDailySync.class).size());
        }
    }
}
