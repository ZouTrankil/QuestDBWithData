package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import com.zoutrankil.questdbwithdata.domain.StockBasicLatest;
import com.zoutrankil.questdbwithdata.domain.StockBasicSyncReport;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;
import com.zoutrankil.questdbwithdata.mapper.StockBasicMapper;
import com.zoutrankil.questdbwithdata.repository.QuestDbStockBasicRepository;
import com.zoutrankil.questdbwithdata.repository.StockBasicLatestRepository;
import com.zoutrankil.questdbwithdata.storage.StockBasicCsvWriter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.zoutrankil.questdbwithdata.domain.SyncJobOwner;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;

@Service
public class StockBasicSyncService implements SyncJobOwner {
    @Override public String datasetId() { return "stock_basic_snapshot"; }
    @Override public Set<SyncJobDefinition.Mode> supportedSyncModes() {
        return Set.of(SyncJobDefinition.Mode.SNAPSHOT);
    }
    @Override public List<SyncJobDefinition> syncJobDefinitions() {
        return List.of(StockBasicJobDefinition.DEFINITION);
    }
    private final TushareClient tushareClient;
    private final StockBasicMapper stockBasicMapper;
    private final QuestDbStockBasicRepository questDbRepository;
    private final StockBasicLatestRepository stockBasicLatestRepository;
    private final SchemaMigrationService schemaMigrationService;

    public StockBasicSyncService(
            TushareClient tushareClient,
            StockBasicMapper stockBasicMapper,
            QuestDbStockBasicRepository questDbRepository,
            StockBasicLatestRepository stockBasicLatestRepository,
            SchemaMigrationService schemaMigrationService) {
        this.tushareClient = tushareClient;
        this.stockBasicMapper = stockBasicMapper;
        this.questDbRepository = questDbRepository;
        this.stockBasicLatestRepository = stockBasicLatestRepository;
        this.schemaMigrationService = schemaMigrationService;
    }

    public int syncToCsv(Path output) throws IOException {
        List<StockBasic> stocks = fetchDomainStocks();
        StockBasicCsvWriter.write(output, stocks);
        return stocks.size();
    }

    public StockBasicSyncReport syncToQuestDb()
            throws IOException, InterruptedException {
        schemaMigrationService.migrate();
        List<StockBasic> stocks = fetchDomainStocks();
        StockBasicSyncReport result =
                questDbRepository.storeAndVerify(stocks);
        if (result.submittedRows() != result.visibleRows()) {
            throw new IllegalStateException("QuestDB visibility mismatch: submitted "
                    + result.submittedRows() + ", visible " + result.visibleRows());
        }
        return result;
    }

    public void verifyQuestDbConnection() {
        questDbRepository.verifyConnection();
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
