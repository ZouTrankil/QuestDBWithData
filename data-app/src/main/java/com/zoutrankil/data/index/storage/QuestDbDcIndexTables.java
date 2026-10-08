package com.zoutrankil.data.index.storage;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.index.domain.*;
import com.zoutrankil.data.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import java.util.*;
import java.time.*;
import java.util.function.BooleanSupplier;
public final class QuestDbDcIndexTables implements DcIndexTables {
private final JdbcTemplate jdbc;
public QuestDbDcIndexTables(JdbcTemplate source){jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));jdbc.setQueryTimeout(30);}
public DcIndexTables.Table open(String table){return new DcIndexStorage(jdbc,table);}
public String logicalTargetId(String table){com.zoutrankil.data.domain.DatasetDefinition.identifier(table);return DcIndexTargetIdentity.logical(jdbc.execute((ConnectionCallback<String>)c->c.getMetaData().getURL()),table);}
public String physicalTargetId(String table,DcIndexState.Identity identity){return DcIndexStorage.physicalTargetId(jdbc,table,identity);}
public DcIndexState.Snapshot snapshotIfPresent(String name)throws Exception{var exists=jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?",name);if(exists.isEmpty())return null;long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!QuestDbWriteChecks.walSettled(jdbc,name)){if(System.nanoTime()>deadline)throw new IllegalStateException("D023 renamed table WAL unresolved");Thread.sleep(50);}return new DcIndexStorage(jdbc,name).snapshot();}
public void rename(String from,String to){jdbc.execute("RENAME TABLE \""+from+"\" TO \""+to+"\"");}
}
