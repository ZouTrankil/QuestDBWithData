package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.StockBasicLatest;

import java.util.List;

/**
 * Portable view contract: one row per tsCode, selected by greatest snapshotTimestamp.
 * Each database adapter supplies the physical view/query and its DDL migration.
 */
public interface StockBasicLatestRepository {
    List<StockBasicLatest> findLatest();
}
