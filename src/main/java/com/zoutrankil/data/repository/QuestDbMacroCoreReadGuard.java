package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.MacroCoreMonthlyDataset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** D104 alone: a bounded monthly read pins the complete physical output generation. */
final class QuestDbMacroCoreReadGuard {
    private QuestDbMacroCoreReadGuard() {}
    static boolean applies(DatasetDefinition definition) {
        return "macro_core_monthly".equals(definition.datasetId());
    }
    static void validate(DatasetDefinition definition, DatasetReadQuery query) {
        if (!applies(definition)) return;
        if (!definition.equals(MacroCoreMonthlyDataset.definition(definition.objectName())))
            throw new IllegalArgumentException("D104 requires its exact registered table contract");
        if (!query.columns().equals(definition.storageColumns()) || query.pageSize() > 12
                || !Set.of("month").containsAll(query.equalities().keySet()))
            throw new IllegalArgumentException("D104 requires all 9 columns, month filters and at most 12 rows per page");
        if (query.equalities().containsKey("month")) requireMonth(query.equalities().get("month"));
        if (query.rangeColumn() != null) {
            if (!"month".equals(query.rangeColumn()) || !query.equalities().isEmpty())
                throw new IllegalArgumentException("D104 month range requires an unfiltered monthly window");
            LocalDate from = requireMonth(query.fromInclusive()), to = requireMonth(query.toExclusive());
            if (!from.isBefore(to) || ChronoUnit.MONTHS.between(from, to) > 12)
                throw new IllegalArgumentException("D104 requires an increasing range of at most 12 months");
        } else if (!query.equalities().containsKey("month")) {
            throw new IllegalArgumentException("D104 exact month or finite monthly range required");
        }
        if (query.cursor() != null && (query.cursor().keyValues().size() != 1
                || requireMonth(query.cursor().keyValues().getFirst()) == null))
            throw new IllegalArgumentException("D104 complete month cursor required");
    }
    private static LocalDate requireMonth(Object value) {
        if (!(value instanceof LocalDate date) || date.getDayOfMonth() != 1
                || date.getYear() < 1 || date.getYear() > 9999)
            throw new IllegalArgumentException("D104 month uses a first-day LocalDate carrier");
        return date;
    }
    static String version(JdbcTemplate jdbc, DatasetDefinition definition) {
        PhysicalState before = state(jdbc, definition.objectName());
        query(jdbc, "SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"
                + definition.objectName() + "') LIMIT 10", 10, rs -> {
            var types = new LinkedHashMap<String, String>();
            var designated = new ArrayList<String>();
            var keys = new HashSet<String>();
            while (rs.next()) {
                String name = rs.getString("column"), type = rs.getString("type");
                if (name == null || type == null || types.putIfAbsent(name, type) != null)
                    throw new IllegalStateException("D104 schema contains a duplicate or absent column");
                if (flag(rs, "designated")) designated.add(name);
                if (flag(rs, "upsertKey")) keys.add(name);
            }
            var expected = new LinkedHashMap<String, String>();
            definition.columns().forEach(c -> expected.put(c.storageName(), c.storageType().name()));
            if (!types.equals(expected) || !new ArrayList<>(types.keySet()).equals(new ArrayList<>(expected.keySet()))
                    || !designated.equals(List.of("month")) || !keys.equals(Set.of("month")))
                throw new IllegalStateException("D104 full schema, designated month or dedup key differs");
            return true;
        });
        if (!before.equals(state(jdbc, definition.objectName())))
            throw new IllegalStateException("D104 output generation changed while checking metadata");
        return before.version(definition.objectName());
    }
    private record PhysicalState(long id,String directory,Long txn,Long walTxn,Long metadataRows,
                                 long writer,long sequence,long pending,long buffered) {
        String version(String table) {
            // Uninitialized is an actual nullable physical state, not transaction zero.
            return "table:"+table+":id:"+id+":dir:"+directory+":txn:"+(txn==null?"uninitialized":txn)
                    +":wal:"+sequence+":wal-physical:"+walTxn+":metadata-rows:"+metadataRows;
        }
    }
    private static PhysicalState state(JdbcTemplate jdbc,String table) {
        PhysicalState before = metadata(jdbc,table);
        if (before.txn()!=null && before.walTxn()!=null && before.metadataRows()!=null) return before;
        if (before.writer()!=0 || before.sequence()!=0 || before.pending()!=0 || before.buffered()!=0
                || before.txn()!=null && before.txn()!=0
                || before.walTxn()!=null && before.walTxn()!=0 || before.metadataRows()!=null && before.metadataRows()!=0)
            throw new IllegalStateException("D104 nullable frontier requires an independently empty WAL table");
        long actualRows = query(jdbc,"SELECT count() AS actual_rows FROM \""+table+"\" LIMIT 2",2,rs -> {
            if (!rs.next()) throw new IllegalStateException("D104 independent empty count is absent");
            long count = counter(rs,"actual_rows");
            if (rs.next()) throw new IllegalStateException("D104 independent empty count is ambiguous");
            return count;
        });
        if (actualRows!=0 || !before.equals(metadata(jdbc,table)))
            throw new IllegalStateException("D104 nullable frontier is not a stable independently counted empty table");
        return before;
    }
    private static PhysicalState metadata(JdbcTemplate jdbc,String table) {
        DatasetDefinition.identifier(table);
        return query(jdbc, "SELECT t.id,t.directoryName,t.table_txn,t.wal_txn,t.table_row_count,t.wal_pending_row_count,t.table_suspended,"
                + "t.walEnabled,t.partitionBy,t.designatedTimestamp,t.dedup,t.matView,"
                + "w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,w.suspended FROM tables() t "
                + "JOIN wal_tables() w ON w.name=t.table_name WHERE t.table_name='" + table + "' LIMIT 2", 2, rs -> {
            if (!rs.next()) throw new IllegalStateException("D104 output table or WAL is absent");
            long id = counter(rs,"id"), writer = counter(rs,"writerTxn"), seq = counter(rs,"sequencerTxn");
            Long txn=nullableCounter(rs,"table_txn"),walTxn=nullableCounter(rs,"wal_txn"),rows=nullableCounter(rs,"table_row_count");
            long pending=counter(rs,"wal_pending_row_count"),buffered=counter(rs,"bufferedTxnSize");
            String directory = rs.getString("directoryName");
            if (directory == null || directory.isBlank() || !flag(rs,"walEnabled") || !flag(rs,"dedup")
                    || flag(rs,"matView") || flag(rs,"table_suspended") || flag(rs,"suspended")
                    || !"YEAR".equals(rs.getString("partitionBy")) || !"month".equals(rs.getString("designatedTimestamp"))
                    || pending != 0 || buffered != 0 || writer != seq
                    || walTxn != null && walTxn != writer)
                throw new IllegalStateException("D104 output layout or settled WAL differs");
            if (rs.next()) throw new IllegalStateException("Duplicate D104 physical output metadata");
            return new PhysicalState(id,directory,txn,walTxn,rows,writer,seq,pending,buffered);
        });
    }
    private static Long nullableCounter(ResultSet rs,String column) throws SQLException {
        long result=rs.getLong(column);
        if(rs.wasNull())return null;
        if(result<0)throw new IllegalStateException("D104 negative nullable physical counter: "+column);
        return result;
    }
    private static boolean flag(ResultSet rs, String column) throws SQLException {
        boolean result = rs.getBoolean(column);
        if (rs.wasNull()) throw new IllegalStateException("D104 missing physical status: " + column);
        return result;
    }
    private static long counter(ResultSet rs, String column) throws SQLException {
        long result = rs.getLong(column);
        if (rs.wasNull() || result < 0) throw new IllegalStateException("D104 invalid physical counter: " + column);
        return result;
    }
    private static <T> T query(JdbcTemplate jdbc, String sql, int limit, ResultSetExtractor<T> extractor) {
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(20); statement.setMaxRows(limit); statement.setFetchSize(limit);
            return statement;
        }, extractor);
    }
}
