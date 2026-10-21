package com.zoutrankil.market.cn.chips;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.config.DatasetConfiguration;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.chips.application.CyqPerfSyncOwner;
import com.zoutrankil.market.cn.chips.cli.CyqPerfCommands;
import com.zoutrankil.market.cn.chips.config.CyqPerfConfiguration;

import io.questdb.client.QuestDB;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

class CyqPerfConfigurationTest {
    @Test
    void ownerPolicyAndCliAreWiredWithoutConnectingOrStartingSchedule() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var questdb = mock(QuestDB.class);
            var pages = mock(TusharePageService.class);
            context.registerBean(
                    JdbcTemplate.class, () -> new JdbcTemplate(mock(DataSource.class)));
            context.registerBean(QuestDB.class, () -> questdb);
            context.registerBean(TusharePageService.class, () -> pages);
            context.registerBean(
                    ExchangeCalendarReadPort.class, () -> mock(ExchangeCalendarReadPort.class));
            context.register(DatasetConfiguration.class, CyqPerfConfiguration.class);
            context.refresh();
            var job = context.getBean(SyncJobRegistry.class).require("data.cyq_perf", 1);
            assertEquals("cyq_perf_owner", job.owner());
            assertFalse(job.dailyEligible());
            assertEquals(1, context.getBeansOfType(CyqPerfSyncOwner.class).size());
            assertEquals(4, context.getBean(CyqPerfCommands.class).commands().size());
            verifyNoInteractions(pages, questdb);
        }
    }
}
