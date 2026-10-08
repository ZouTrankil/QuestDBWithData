package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.domain.StockBasicLatest;
import com.zoutrankil.data.domain.DatasetReadPage;
import com.zoutrankil.data.domain.DatasetReadQuery;

import java.util.List;

/**
 * Portable view contract: one row per tsCode, selected by greatest snapshotTimestamp.
 * Each database adapter supplies the physical view/query and its DDL migration.
 */
public interface StockBasicLatestRepository {
    List<StockBasicLatest> findLatest();
    DatasetReadPage<StockBasicLatest> findLatestPage(DatasetReadQuery query);
}
