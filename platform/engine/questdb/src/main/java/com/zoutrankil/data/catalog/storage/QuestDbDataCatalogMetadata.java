package com.zoutrankil.data.catalog.storage;

import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.port.DataCatalogMetadataPort;
import com.zoutrankil.data.domain.DatasetDefinition.ObjectKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只查系统元数据；普通视图缺少行数时保持未知，不执行视图本身。 */
public final class QuestDbDataCatalogMetadata implements DataCatalogMetadataPort {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public QuestDbDataCatalogMetadata(DataSource source, int timeoutSeconds, Clock clock) {
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("目录查询超时范围为 1～60 秒");
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(timeoutSeconds);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override public DataCatalog.Snapshot snapshot() {
        Instant observedAt = clock.instant();
        String databaseId = jdbc.queryForObject("SELECT current_data_id()", String.class);
        var objects = new TreeMap<String, DataCatalog.PhysicalObject>();
        jdbc.query("SELECT table_name,table_type,designatedTimestamp,table_row_count,table_min_timestamp,table_max_timestamp FROM tables()",
                (rs, index) -> new DataCatalog.PhysicalObject(rs.getString("table_name"), kind(rs.getString("table_type")),
                        rs.getString("designatedTimestamp"), number(rs, "table_row_count"),
                        instant(rs, "table_min_timestamp"), instant(rs, "table_max_timestamp"), null, null))
                .forEach(object -> objects.put(object.objectName(), object));
        jdbc.query("SELECT view_name,view_status FROM views()", (rs, index) ->
                new DataCatalog.PhysicalObject(rs.getString("view_name"), ObjectKind.VIEW, null,
                        null, null, null, rs.getString("view_status"), null))
                .forEach(object -> objects.put(object.objectName(), object));
        var mvs = jdbc.query("SELECT view_name,view_status,refresh_type,base_table_name,last_refresh_start_timestamp,"
                + "last_refresh_finish_timestamp,refresh_base_table_txn,base_table_txn,timer_interval,timer_interval_unit,invalidation_reason FROM materialized_views()",
                (rs, index) -> new NamedMv(rs.getString("view_name"), new DataCatalog.MaterializedView(
                        rs.getString("view_status"), rs.getString("refresh_type"), rs.getString("base_table_name"),
                        instant(rs, "last_refresh_start_timestamp"), instant(rs, "last_refresh_finish_timestamp"),
                        number(rs, "refresh_base_table_txn"), number(rs, "base_table_txn"), number(rs, "timer_interval"),
                        rs.getString("timer_interval_unit"), rs.getString("invalidation_reason"))));
        // MV 的物理存储也在 tables() 中出现，合并刷新信息时保留其统计值。
        for (var mv : mvs) {
            var physical = objects.get(mv.name());
            objects.put(mv.name(), new DataCatalog.PhysicalObject(mv.name(), ObjectKind.MATERIALIZED_VIEW,
                    physical == null ? null : physical.designatedTimestamp(), physical == null ? null : physical.rowCount(),
                    physical == null ? null : physical.minTimestamp(), physical == null ? null : physical.maxTimestamp(),
                    mv.state().status(), mv.state()));
        }
        return new DataCatalog.Snapshot(databaseId, observedAt, List.copyOf(objects.values()));
    }

    @Override public List<DataCatalog.Column> columns(String objectName) {
        // 对象名作为值参数绑定，不能拼接成用户可执行的 SQL。
        return jdbc.query("SELECT column_name,data_type,is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position",
                (rs, index) -> new DataCatalog.Column(rs.getString("column_name"), rs.getString("data_type"),
                        nullable(rs.getString("is_nullable")), "待补充", null), objectName);
    }

    private static ObjectKind kind(String value) {
        return switch (value) {
            case "T" -> ObjectKind.TABLE;
            case "V" -> ObjectKind.VIEW;
            case "M" -> ObjectKind.MATERIALIZED_VIEW;
            default -> throw new IllegalStateException("未知 QuestDB 对象类型");
        };
    }
    private static Long number(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() || value < 0 ? null : value;
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getTimestamp(name, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return value == null ? null : value.toInstant();
    }
    private static Boolean nullable(String value) {
        return value == null ? null : value.equalsIgnoreCase("yes");
    }
    private record NamedMv(String name, DataCatalog.MaterializedView state) {}
}
