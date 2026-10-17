package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.stock.domain.StockDetailState.Identity;
import com.zoutrankil.data.stock.port.StockDetailNameReadPort;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reuses D002's bounded decoder without exposing a database connection to callers. */
public final class QuestDbStockDetailNameReadPort implements StockDetailNameReadPort {
    private final JdbcTemplate jdbc;
    private final String table;
    public QuestDbStockDetailNameReadPort(JdbcTemplate jdbc, String table) {
        this.jdbc = Objects.requireNonNull(jdbc); DatasetDefinition.identifier(table); this.table = table;
    }
    @Override public String targetId() {
        var identity = new StockDetailInfoStorage(jdbc, table).preflight();
        return identify(identity);
    }
    @Override public String identify(Identity identity) {
        return StaticTargetIdentity.identify(jdbc, table, identity.id(), identity.directory());
    }
    @Override public Session openSession() {
        var storage = new StockDetailInfoStorage(jdbc, table);
        return new Session() {
            @Override public Identity preflight() { return storage.preflight(); }
            @Override public List<StockDetailInfoRow> readKeys(List<String> codes) { return storage.readKeys(codes); }
        };
    }
}
