package com.zoutrankil.data.l2.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.l2.domain.L2DailyFeaturesRows;
import com.zoutrankil.data.l2.port.L2DailyFeaturesTarget;
import com.zoutrankil.data.l2.port.L2DailyFeaturesWriteSession;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** Physical D086 target metadata and per-attempt writer creation. */
public final class QuestDbL2DailyFeaturesTarget implements L2DailyFeaturesTarget.Versioned {
    private final CnTradeDateLayout layout;
    private final String targetTable;
    private final JdbcTemplate jdbc;
    private final QuestDB questdb;
    private final QuestDbProperties questProperties;

    public QuestDbL2DailyFeaturesTarget(
            String table, JdbcTemplate jdbc, QuestDB questdb, QuestDbProperties properties) {
        this(table, jdbc, questdb, properties, CnTradeDateLayout.LEGACY_V1);
    }

    public QuestDbL2DailyFeaturesTarget(
            String table,
            JdbcTemplate jdbc,
            QuestDB questdb,
            QuestDbProperties properties,
            CnTradeDateLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.questdb = Objects.requireNonNull(questdb);
        this.questProperties = Objects.requireNonNull(properties);
        this.targetTable = table == null ? "" : table.trim();
    }

    @Override
    public int datasetVersion() {
        return layout.schemaVersion();
    }

    @Override
    public L2DailyFeaturesTarget forVersion(int version) {
        return new QuestDbL2DailyFeaturesTarget(
                targetTable,
                jdbc,
                questdb,
                questProperties,
                CnTradeDateLayout.fromVersion(version));
    }

    @Override
    public String tableName() {
        return targetTable;
    }

    @Override
    public L2DailyFeaturesWriteSession newWriter() {
        return new L2DailyFeaturesWritePort(targetTable, jdbc, questdb, layout);
    }

    @Override
    public String targetId() throws Exception {
        L2DailyFeaturesRows.requireIsolatedTableName(targetTable);
        var rows =
                jdbc.queryForList(
                        "SELECT id,directoryName FROM tables() WHERE table_name=?", targetTable);
        if (rows.size() != 1
                || !(rows.getFirst().get("id") instanceof Number)
                || rows.getFirst().get("directoryName") == null)
            throw new IllegalStateException("Exact D086 isolated QuestDB table identity required");
        if (layout == CnTradeDateLayout.TRADE_DATE_V2) {
            Object id = rows.getFirst().get("id");
            Object directory = rows.getFirst().get("directoryName");
            // 新版身份只接完整整数 ID 与非空目录，不能把浮点或文本 ID 强转后认领。
            if (!(id instanceof Integer
                            || id instanceof Long
                            || id instanceof Short
                            || id instanceof Byte)
                    || ((Number) id).longValue() <= 0
                    || !(directory instanceof String text)
                    || text.isBlank()) throw new IllegalStateException("L2 日特征物理身份类型或范围不正确");
        }
        String identity =
                questProperties.getHost()
                        + ":"
                        + questProperties.getPgPort()
                        + ":"
                        + questProperties.getQwpPort()
                        + ":"
                        + questProperties.getDatabase()
                        + ":"
                        + targetTable
                        + ":"
                        + rows.getFirst().get("id")
                        + ":"
                        + rows.getFirst().get("directoryName");
        // v1 原身份字节保留；新版独立摘要域，不能借旧目标的检查点。
        if (layout == CnTradeDateLayout.TRADE_DATE_V2)
            identity = "l2-daily-features-layout-v2:" + identity;
        return "questdb-"
                + HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(identity.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void createIsolatedTarget(String table) {
        L2DailyFeaturesRows.requireIsolatedTableName(table);
        new L2DailyFeaturesWritePort(table, jdbc, questdb, layout).createIsolatedTargetIfMissing();
    }
}
