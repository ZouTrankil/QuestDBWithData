package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.StockBasicLatest;
import com.zoutrankil.questdbwithdata.domain.DatasetReadPage;
import com.zoutrankil.questdbwithdata.domain.DatasetReadQuery;

import java.util.List;

/**
 * Portable view contract: one row per tsCode, selected by greatest snapshotTimestamp.
 * Each database adapter supplies the physical view/query and its DDL migration.
 */
public interface StockBasicLatestRepository {
    List<StockBasicLatest> findLatest();
    DatasetReadPage<StockBasicLatest> findLatestPage(DatasetReadQuery query);
}
