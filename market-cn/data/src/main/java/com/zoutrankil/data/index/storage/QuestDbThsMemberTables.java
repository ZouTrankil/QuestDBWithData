package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.index.domain.ThsMemberState;

import com.zoutrankil.data.index.domain.ThsMemberState.*;
import com.zoutrankil.data.index.port.ThsMemberTables;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import java.time.Duration;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

public class QuestDbThsMemberTables implements ThsMemberTables {
    protected final JdbcTemplate jdbc;

    public QuestDbThsMemberTables(JdbcTemplate source) { this(source, true); }

    QuestDbThsMemberTables(JdbcTemplate source, boolean publicationSession) {
        if (publicationSession) {
            jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
            jdbc.setQueryTimeout(120);
        } else jdbc = Objects.requireNonNull(source);
    }

    @Override public Table open(String table) { return new ThsMemberBoardStorage(jdbc, table); }

    @Override public String identify(String table, long id, String directory) {
        return StaticTargetIdentity.identify(jdbc, table, id, directory);
    }

    @Override public void rename(String from, String to) {
        jdbc.execute("RENAME TABLE \"" + from + "\" TO \"" + to + "\"");
    }

    @Override public Snapshot snapshotIfPresent(String table, String board) throws Exception {
        if (jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?", table).isEmpty()) return null;
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Renamed THS WAL unresolved");
            Thread.sleep(50);
        }
        return new ThsMemberBoardStorage(jdbc, table).snapshot(board);
    }
}
