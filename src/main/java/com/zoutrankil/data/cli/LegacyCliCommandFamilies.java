package com.zoutrankil.data.cli;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;

import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.LedgerManagementService;

/** Compatibility construction for existing direct Java callers; Spring wires the family beans. */
final class LegacyCliCommandFamilies {
    private LegacyCliCommandFamilies() {}

    static CliCommandRegistry create(StockBasicSyncService syncService,
            DatasetRegistry datasetRegistry,
            SyncJobRegistry jobRegistry,
            com.zoutrankil.data.stock.application.StockBasicJobService jobService,
            com.zoutrankil.data.service.StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService,
            com.zoutrankil.data.service.StockBasicScheduleService scheduleService,
            com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService,
            com.zoutrankil.data.stock.application.StockDetailInfoJobService stockDetailService,
            com.zoutrankil.data.service.IndexCatalogJobService indexCatalogService,
            LedgerManagementService ledgerManagementService) {
        java.util.Objects.requireNonNull(ledgerManagementService);
        return new CliCommandRegistry(java.util.List.of(
                new LegacyQuestDbCommands(syncService),
                new CatalogCommands(datasetRegistry, jobRegistry),
                new LedgerCommands(ledgerManagementService),
                new ScheduleCommands(scheduleService),
                new GroupCommands(jobRegistry, groupService, readGroupReader, writeGroupService),
                new StockCommands(jobService, stockDetailService, null, null, null, null, null, null),
                new CalendarCommands(calendarService),
                new EtfCommands(null, null, null, null, null, null),
                new IndexCommands(null, null, null, indexCatalogService, null, null, null, null, null),
                new FlowCommands(null, null, null, null),
                new L2Commands(null, null, null, null, null),
                new MaterializationCommands(null, null, null, null, null)));
    }
}
