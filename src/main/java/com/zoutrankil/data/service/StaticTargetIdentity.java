package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.stock.domain.StockDetailState;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;

/** Compatibility entry point for static target identity policy and JDBC endpoint lookup. */
public final class StaticTargetIdentity {
    private StaticTargetIdentity() {}
    public static String identify(JdbcTemplate jdbc,String table,StockDetailState.Identity identity) {
        return identify(jdbc,table,identity.id(),identity.directory());
    }
    public static String identify(JdbcTemplate jdbc,String table,long id,String directory) {
        return com.zoutrankil.data.repository.StaticTargetIdentity.identify(jdbc,table,id,directory);
    }
    static String identify(String jdbcUrl,String table,StockDetailState.Identity identity) {
        DatasetDefinition.identifier(table);Objects.requireNonNull(identity);
        return com.zoutrankil.data.domain.policy.StaticTargetIdentity.identify(
                jdbcUrl,table,identity.id(),identity.directory());
    }
}
