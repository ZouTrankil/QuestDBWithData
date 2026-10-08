package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.domain.IndexMembershipState.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

public final class QuestDbIndexMembershipTarget implements IndexMembershipTarget {
    private final JdbcTemplate jdbc;
    private final String table;
    public QuestDbIndexMembershipTarget(JdbcTemplate jdbc,String table) {
        this.jdbc=Objects.requireNonNull(jdbc);DatasetDefinition.identifier(table);this.table=table;
    }
    public String tableName() { return table; }
    public Table open(String table) { return new IndexMembershipStorage(jdbc,table); }
    public String identify(String table,long id,String directory) { return StaticTargetIdentity.identify(jdbc,table,id,directory); }
    public boolean exists(String table) { return !jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",table).isEmpty(); }
    public boolean walSettled(String table) { return QuestDbWriteChecks.walSettled(jdbc,table); }
    public void rename(String from,String to) { jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\""); }
    public IndexMembershipTables publicationTables() {
        var copy=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));copy.setQueryTimeout(20);
        return new QuestDbIndexMembershipTarget(copy,table);
    }
    public Prepared prepare(Snapshot before,List<IndexMembership> source,String l2Code) throws Exception {
        return IndexMembershipStaging.prepare(before,source,l2Code);
    }
    public Prepared preparePrepared(Snapshot before,List<IndexMembership> source,String l2Code) throws Exception {
        return IndexMembershipStaging.preparePrepared(before,source,l2Code);
    }
    public IndexMembershipStagingPort newStaging() { return new IndexMembershipStaging(jdbc); }
}
