package com.zoutrankil.data.service;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.mapper.StockBasicMapper;
import com.zoutrankil.data.repository.QuestDbStockBasicRepository;
import com.zoutrankil.data.repository.StockBasicLatestRepository;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockBasicSyncServiceMigrationTest {
    private final TushareClient client = mock(TushareClient.class);
    private final StockBasicMapper mapper = new StockBasicMapper();
    private final QuestDbStockBasicRepository repository = mock(QuestDbStockBasicRepository.class);
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

    @Test
    void csvSyncDoesNotAccessDatabase(@TempDir Path directory) throws Exception {
        when(client.fetchCurrentListedStocks()).thenReturn(List.of());
        assertEquals(0, service.syncToCsv(directory.resolve("stocks.csv")));
        verifyNoInteractions(migration, repository, latest);
    }
}
