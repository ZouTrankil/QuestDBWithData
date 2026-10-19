package com.zoutrankil.data.stock.application;

import com.zoutrankil.data.service.*;
import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.domain.stock.basic.StockBasic;
import com.zoutrankil.data.domain.stock.basic.StockBasicLatest;
import com.zoutrankil.data.domain.stock.basic.StockBasicSyncReport;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.stock.mapper.StockBasicMapper;
import com.zoutrankil.data.stock.port.StockBasicLatestRepository;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.zoutrankil.data.domain.shared.SyncJobOwner;
import com.zoutrankil.data.domain.SyncJobDefinition;

/** 股票基础快照同步入口：获取来源、映射业务字段、写入并核验 QuestDB 可见行。 */
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

    /** 提交行数与可见行数不一致即失败，不能仅凭发送成功报告同步完成。 */
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
        for (var source : fetchCurrentListedStocks()) {
            stocks.add(stockBasicMapper.toDomain(source));
        }
        return List.copyOf(stocks);
    }
    /** 股票字段和 DTO 属于 A 股，通用客户端只负责请求与响应。 */
    private java.util.List<com.zoutrankil.data.client.dto.TushareStockBasicDto> fetchCurrentListedStocks() throws java.io.IOException {
        var page = tushareClient.request(new com.zoutrankil.data.client.dto.TushareRequest("stock_basic",
                java.util.Map.of("exchange", "", "list_status", "L"),
                com.zoutrankil.data.client.dto.TushareStockBasicDto.FIELDS, 10_000));
        return page.rows().stream().map(row -> new com.zoutrankil.data.client.dto.TushareStockBasicDto(
                sourceText(row,"ts_code"),sourceText(row,"symbol"),sourceText(row,"name"),
                sourceText(row,"area"),sourceText(row,"industry"),sourceText(row,"list_date"))).toList();
    }
    private static String sourceText(java.util.Map<String,com.fasterxml.jackson.databind.JsonNode> row, String field) {
        return row.get(field).isNull() ? null : row.get(field).asText();
    }
}
