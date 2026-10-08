package com.zoutrankil.data.stock.storage;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.domain.StockDetailState.*;
import com.zoutrankil.data.stock.port.StockDetailTarget;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
public final class QuestDbStockDetailTarget implements StockDetailTarget {
    private final JdbcTemplate jdbc;
    private final String table;
    public QuestDbStockDetailTarget(JdbcTemplate jdbc,String table) {
        this.jdbc=Objects.requireNonNull(jdbc);DatasetDefinition.identifier(table);this.table=table;
    }
    public String tableName() { return table; }
    public Table open(String table) { return new StockDetailInfoStorage(jdbc,table); }
    public String identify(String table,Identity identity) { return StaticTargetIdentity.identify(jdbc,table,identity.id(),identity.directory()); }
    public boolean exists(String table) { return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).isEmpty(); }
    public void rename(String from,String to) { jdbc.execute("RENAME TABLE "+from+" TO "+to); }
    public StockDetailTarget publicationTables() {
        var copy=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));copy.setQueryTimeout(20);
        return new QuestDbStockDetailTarget(copy,table);
    }
    public Prepared prepare(Snapshot before,List<StockDetailInfo> source) throws Exception { return StockDetailInfoStaging.prepare(before,source); }
    public StageWriter newStaging() { return new StockDetailInfoStaging(jdbc); }
}
