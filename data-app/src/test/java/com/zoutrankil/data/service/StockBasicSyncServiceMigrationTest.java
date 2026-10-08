package com.zoutrankil.data.service;

import com.zoutrankil.data.stock.application.StockBasicSyncService;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.stock.application.StockBasicWriteService;
import com.zoutrankil.data.stock.port.StockBasicLatestRepository;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicSyncServiceMigrationTest {
    private final TushareClient client = mock(TushareClient.class);
    private final StockBasicMapper mapper = new StockBasicMapper();
    private final StockBasicWriteService repository = mock(StockBasicWriteService.class);
    private final StockBasicLatestRepository latest = mock(StockBasicLatestRepository.class);
    private final SchemaMigrationService migration = mock(SchemaMigrationService.class);
    private final StockBasicSyncService service =
            new StockBasicSyncService(client, mapper, repository, latest, migration);

    @Test
    void migrationFailureStopsFetchingAndWriting() {
        doThrow(new FlywayException("invalid schema")).when(migration).migrate();
        assertThrows(FlywayException.class, service::syncToQuestDb);
        verifyNoInteractions(client, repository);
    }

    @Test
    void latestReadRunsAfterMigration() {
        when(latest.findLatest()).thenReturn(List.of());
        service.loadLatestStocks();
        var ordered = inOrder(migration, latest);
        ordered.verify(migration).migrate();
        ordered.verify(latest).findLatest();
    }
}
