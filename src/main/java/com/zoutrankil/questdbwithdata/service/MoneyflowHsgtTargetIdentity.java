package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable logical endpoint/table identity retained while journaled physical generations are renamed. */
public final class MoneyflowHsgtTargetIdentity {
    private MoneyflowHsgtTargetIdentity() {}
    public static String logical(JdbcTemplate jdbc, String table) {
        DatasetDefinition.identifier(table);
        return StaticTargetIdentity.identify(jdbc, table, 0L, "d027-logical-target-v1");
    }
}
