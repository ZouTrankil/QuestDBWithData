package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.StockDetailInfoStorage;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable endpoint/table identity for D022 checkpoints across journaled physical replacements. */
public final class IndexMonthlyTargetIdentity {
    private IndexMonthlyTargetIdentity() {}
    public static String logical(JdbcTemplate jdbc, String table) {
        DatasetDefinition.identifier(table);
        return StaticTargetIdentity.identify(jdbc, table, 0L, "d022-logical-target-v1");
    }
}
