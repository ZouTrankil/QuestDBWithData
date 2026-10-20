package com.zoutrankil.data.cli;

import com.zoutrankil.data.bootstrap.StartupArguments;
import com.zoutrankil.data.calendar.application.ExchangeCalendarJobService;
import com.zoutrankil.data.index.application.IndexCatalogJobService;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.LedgerManagementService;
import com.zoutrankil.data.service.ReadGroupReader;
import com.zoutrankil.data.service.StockBasicGroupService;
import com.zoutrankil.data.service.StockBasicScheduleService;
import com.zoutrankil.data.service.StockBasicWriteGroupService;
import com.zoutrankil.data.service.SyncJobRegistry;
import com.zoutrankil.data.stock.application.StockBasicJobService;
import com.zoutrankil.data.stock.application.StockBasicSyncService;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnNotWebApplication
public class CommandLineRunner implements ApplicationRunner {
    private final CliCommandRegistry commands;

    @Autowired
    public CommandLineRunner(CliCommandRegistry commands) {
        this.commands = Objects.requireNonNull(commands);
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasetRegistry,
            @Lazy SyncJobRegistry jobRegistry,
            StockBasicJobService jobService,
            StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            StockBasicWriteGroupService writeGroupService,
            @Lazy StockBasicScheduleService scheduleService,
            ExchangeCalendarJobService calendarService,
            StockDetailInfoJobService stockDetailService,
            IndexCatalogJobService indexCatalogService,
            LedgerManagementService ledgerManagementService) {
        this(
                LegacyCliCommandFamilies.create(
                        syncService,
                        datasetRegistry,
                        jobRegistry,
                        jobService,
                        groupService,
                        readGroupReader,
                        writeGroupService,
                        scheduleService,
                        calendarService,
                        stockDetailService,
                        indexCatalogService,
                        ledgerManagementService));
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasetRegistry,
            SyncJobRegistry jobRegistry,
            StockBasicJobService jobService,
            StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            StockBasicWriteGroupService writeGroupService,
            StockBasicScheduleService scheduleService,
            ExchangeCalendarJobService calendarService,
            StockDetailInfoJobService stockDetailService,
            IndexCatalogJobService indexCatalogService) {
        this(
                syncService,
                datasetRegistry,
                jobRegistry,
                jobService,
                groupService,
                readGroupReader,
                writeGroupService,
                scheduleService,
                calendarService,
                stockDetailService,
                indexCatalogService,
                new LedgerManagementService(LedgerManagementService.DEFAULT_LEDGER_PATH));
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasets,
            SyncJobRegistry jobs,
            StockBasicJobService job,
            StockBasicGroupService group,
            ReadGroupReader read,
            StockBasicWriteGroupService write,
            StockBasicScheduleService schedule,
            ExchangeCalendarJobService calendar,
            StockDetailInfoJobService detail) {
        this(
                syncService,
                datasets,
                jobs,
                job,
                group,
                read,
                write,
                schedule,
                calendar,
                detail,
                null);
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasetRegistry,
            SyncJobRegistry jobRegistry,
            StockBasicJobService jobService,
            StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            StockBasicWriteGroupService writeGroupService,
            StockBasicScheduleService scheduleService,
            ExchangeCalendarJobService calendarService) {
        this(
                syncService,
                datasetRegistry,
                jobRegistry,
                jobService,
                groupService,
                readGroupReader,
                writeGroupService,
                scheduleService,
                calendarService,
                null);
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasets,
            SyncJobRegistry jobs,
            StockBasicJobService job,
            StockBasicGroupService group,
            ReadGroupReader read,
            StockBasicWriteGroupService write,
            StockBasicScheduleService schedule) {
        this(syncService, datasets, jobs, job, group, read, write, schedule, null);
    }

    public CommandLineRunner(
            StockBasicSyncService syncService,
            DatasetRegistry datasetRegistry,
            SyncJobRegistry jobRegistry,
            StockBasicJobService jobService,
            StockBasicGroupService groupService,
            ReadGroupReader readGroupReader,
            StockBasicWriteGroupService writeGroupService) {
        this(
                syncService,
                datasetRegistry,
                jobRegistry,
                jobService,
                groupService,
                readGroupReader,
                writeGroupService,
                null);
    }

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {
        String[] args = StartupArguments.parse(applicationArguments.getSourceArgs()).commandArray();
        if (args.length == 0) throw new IllegalArgumentException(CliUsage.text());
        commands.execute(args[0], CliOptions.parse(args));
    }
}
