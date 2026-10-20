package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicDataset;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Configured physical target; each execution obtains an independent write session. */
public final class QuestDbEtfBasicTarget implements EtfWriteTarget<EtfBasic, EtfBasicKey> {
    private final String table;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;

    public QuestDbEtfBasicTarget(String table, JdbcTemplate jdbc, QuestDB questdb) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        EtfBasicDataset.requireIsolatedTable(table);
        this.table = table;
    }

    @Override
    public String tableName() {
        return table;
    }

    @Override
    public String targetId() {
        EtfBasicDataset.requireIsolatedTable(table);
        QuestDbWriteChecks.preflight(jdbc, table, EtfBasicDataset.definition(table));
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?", table);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number id)
                || !(rows.getFirst().get("directoryName") instanceof String directory))
            throw new IllegalStateException(
                    "Exact isolated etf_basic QuestDB target identity required");
        return StaticTargetIdentity.identify(jdbc, table, id.longValue(), directory);
    }

    @Override
    public VerifiedWriteSession<EtfBasic, EtfBasicKey> newWriter(String frozenTargetId) {
        return new EtfBasicWritePort(table, frozenTargetId, jdbc, questdb);
    }
}
