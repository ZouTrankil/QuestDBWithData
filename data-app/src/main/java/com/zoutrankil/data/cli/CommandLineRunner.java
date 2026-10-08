package com.zoutrankil.data.cli;

import com.zoutrankil.data.index.application.IndexCatalogJobService;

import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;

import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.LedgerManagementService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.ApplicationRunner;

@Component
@ConditionalOnNotWebApplication
public class CommandLineRunner implements ApplicationRunner {
    private final CliCommandRegistry commands;

    @Autowired
    public CommandLineRunner(CliCommandRegistry commands) {
        this.commands = java.util.Objects.requireNonNull(commands);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             @org.springframework.context.annotation.Lazy SyncJobRegistry jobRegistry,
                             com.zoutrankil.data.stock.application.StockBasicJobService jobService,
                             com.zoutrankil.data.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService,
                             @org.springframework.context.annotation.Lazy
                             com.zoutrankil.data.service.StockBasicScheduleService scheduleService,
                             com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService,
                             com.zoutrankil.data.stock.application.StockDetailInfoJobService stockDetailService,
                             com.zoutrankil.data.index.application.IndexCatalogJobService indexCatalogService,
                             LedgerManagementService ledgerManagementService) {
        this(LegacyCliCommandFamilies.create(syncService, datasetRegistry, jobRegistry, jobService, groupService, readGroupReader, writeGroupService, scheduleService, calendarService, stockDetailService, indexCatalogService, ledgerManagementService));
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.data.stock.application.StockBasicJobService jobService,
                             com.zoutrankil.data.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService,
                             com.zoutrankil.data.service.StockBasicScheduleService scheduleService,
                             com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService,
                             com.zoutrankil.data.stock.application.StockDetailInfoJobService stockDetailService,
                             com.zoutrankil.data.index.application.IndexCatalogJobService indexCatalogService) {
        this(syncService, datasetRegistry, jobRegistry, jobService, groupService, readGroupReader, writeGroupService,
                scheduleService, calendarService, stockDetailService, indexCatalogService,
                new LedgerManagementService(LedgerManagementService.DEFAULT_LEDGER_PATH));
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasets, SyncJobRegistry jobs,
            com.zoutrankil.data.stock.application.StockBasicJobService job,
            com.zoutrankil.data.service.StockBasicGroupService group, ReadGroupReader read,
            com.zoutrankil.data.service.StockBasicWriteGroupService write,
            com.zoutrankil.data.service.StockBasicScheduleService schedule,
            com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendar,
            com.zoutrankil.data.stock.application.StockDetailInfoJobService detail) {
        this(syncService,datasets,jobs,job,group,read,write,schedule,calendar,detail,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.data.stock.application.StockBasicJobService jobService,
                             com.zoutrankil.data.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService,
                             com.zoutrankil.data.service.StockBasicScheduleService scheduleService,
                             com.zoutrankil.data.calendar.application.ExchangeCalendarJobService calendarService) {
        this(syncService,datasetRegistry,jobRegistry,jobService,groupService,readGroupReader,writeGroupService,
                scheduleService,calendarService,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasets, SyncJobRegistry jobs,
            com.zoutrankil.data.stock.application.StockBasicJobService job,
            com.zoutrankil.data.service.StockBasicGroupService group,ReadGroupReader read,
            com.zoutrankil.data.service.StockBasicWriteGroupService write,
            com.zoutrankil.data.service.StockBasicScheduleService schedule) {
        this(syncService,datasets,jobs,job,group,read,write,schedule,null);
    }

    public CommandLineRunner(StockBasicSyncService syncService, DatasetRegistry datasetRegistry,
                             SyncJobRegistry jobRegistry, com.zoutrankil.data.stock.application.StockBasicJobService jobService,
                             com.zoutrankil.data.service.StockBasicGroupService groupService,
                             ReadGroupReader readGroupReader,
                             com.zoutrankil.data.service.StockBasicWriteGroupService writeGroupService) {
        this(syncService,datasetRegistry,jobRegistry,jobService,groupService,readGroupReader,writeGroupService,null);
    }

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {
        String[] args = com.zoutrankil.data.bootstrap.StartupArguments
                .parse(applicationArguments.getSourceArgs()).commandArray();
        if (args.length == 0) throw new IllegalArgumentException(CliUsage.text());
        commands.execute(args[0], CliOptions.parse(args));
    }
}
