package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.domain.StockBasic;
import com.zoutrankil.data.domain.StockBasicLatest;
import com.zoutrankil.data.domain.StockBasicSyncReport;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.stock.port.StockBasicLatestRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.domain.SyncJobDefinition;

@Service
public class StockBasicSyncService implements SyncJobOwner {
    @Override public String datasetId() { return "stock_basic_snapshot"; }
    @Override public Set<SyncJobDefinition.Mode> supportedSyncModes() {
        return Set.of(SyncJobDefinition.Mode.SNAPSHOT);
    }
    @Override public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(StockBasicJobDefinition.CURRENT);
    }
    private final TushareClient tushareClient;
    private final StockBasicMapper stockBasicMapper;
    private final StockBasicWriteService writes;
    private final StockBasicLatestRepository stockBasicLatestRepository;
    private final SchemaMigrationService schemaMigrationService;

    public StockBasicSyncService(
            TushareClient tushareClient,
            StockBasicMapper stockBasicMapper,
            StockBasicWriteService writes,
            StockBasicLatestRepository stockBasicLatestRepository,
            SchemaMigrationService schemaMigrationService) {
        this.tushareClient = tushareClient;
        this.stockBasicMapper = stockBasicMapper;
        this.writes = writes;
        this.stockBasicLatestRepository = stockBasicLatestRepository;
        this.schemaMigrationService = schemaMigrationService;
    }

    public StockBasicSyncReport syncToQuestDb()
            throws IOException, InterruptedException {
        schemaMigrationService.migrate();
        List<StockBasic> stocks = fetchDomainStocks();
        StockBasicSyncReport result =
                writes.storeAndVerify(stocks);
        if (result.submittedRows() != result.visibleRows()) {
            throw new IllegalStateException("QuestDB visibility mismatch: submitted "
                    + result.submittedRows() + ", visible " + result.visibleRows());
        }
        return result;
    }

    public void verifyQuestDbConnection() {
        writes.verifyConnection();
    }

    public void initializeQuestDbSchema() {
        schemaMigrationService.migrate();
    }

    public List<StockBasicLatest> loadLatestStocks() {
        schemaMigrationService.migrate();
        return stockBasicLatestRepository.findLatest();
    }

    public DatasetReadPage<StockBasicLatest> loadLatestStockPage(DatasetReadQuery query) {
        schemaMigrationService.migrate();
        return stockBasicLatestRepository.findLatestPage(query);
    }

    private List<StockBasic> fetchDomainStocks() throws IOException {
        List<StockBasic> stocks = new ArrayList<>();
        for (var source : tushareClient.fetchCurrentListedStocks()) {
            stocks.add(stockBasicMapper.toDomain(source));
        }
        return List.copyOf(stocks);
    }
}
