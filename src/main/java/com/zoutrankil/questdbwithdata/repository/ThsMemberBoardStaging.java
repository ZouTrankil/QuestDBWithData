package com.zoutrankil.questdbwithdata.repository;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.table.ThsMemberRow;
import com.zoutrankil.questdbwithdata.mapper.ThsMemberMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.*;

/** Preserves all other boards in a WAL stage and verifies every copied row by a streamed digest. */
public final class ThsMemberBoardStaging {
    public record Prepared(String target, String board, ThsMemberBoardStorage.Snapshot before,
                           List<ThsMember> source) {
        public Prepared { source = List.copyOf(source); }
    }
    public record Verified(String stage, ThsMemberBoardStorage.Snapshot snapshot, int batches, String receipt) {}
    private final JdbcTemplate jdbc;
    private final ThsMemberMapper mapper = new ThsMemberMapper();

    public ThsMemberBoardStaging(JdbcTemplate source) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(120);
    }

    public Prepared prepare(String target, String board, List<ThsMember> source) throws Exception {
        DatasetDefinition.identifier(target);
        if (!ThsIndex.validCode(board)) throw new IllegalArgumentException("Exact THS board required");
        if (source.size() > ThsMemberBoardStorage.MAX_BOARD_ROWS)
            throw new IllegalArgumentException("THS board source exceeds bound");
        var seen = new HashSet<ThsMember.Key>();
        for (var row : source) {
            if (!board.equals(row.boardCode()) || !seen.add(row.key()))
                throw new IllegalArgumentException("Duplicate or out-of-board source member");
        }
        return new Prepared(target, board, new ThsMemberBoardStorage(jdbc, target).snapshot(board), source);
    }

    public boolean requiresWrite(Prepared prepared) {
        var old = new HashMap<ThsMember.Key, ThsMember>();
        for (var row : prepared.before().boardRows()) {
            var mapped = mapper.fromStorage(row);
            old.put(mapped.key(), mapped);
        }
        if (old.size() != prepared.source().size()) return true;
        for (var row : prepared.source()) {
            var prior = old.get(row.key());
            if (prior == null || !sameBusinessValues(prior, row)) return true;
        }
        return false;
    }

    public Verified write(Prepared prepared, Path folder, BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if (!requiresWrite(prepared)) throw new IllegalArgumentException("Unchanged THS board must not stage a write");
        var current = new ThsMemberBoardStorage(jdbc, prepared.target()).snapshot(prepared.board());
        if (!current.equals(prepared.before())) throw new IllegalStateException("THS target changed after preparation");
        String stage = "java_ths_member_stage_" + UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(folder);
        var json = JobDefinitionJson.mapper();
        Path intent = folder.resolve(stage + "-intent.json");
        Files.write(intent, json.writeValueAsBytes(Map.of("target", prepared.target(), "stage", stage,
                "board", prepared.board(), "before", prepared.before(), "source", prepared.source(),
                "batchLimit", 250)), StandardOpenOption.CREATE_NEW);
        String statement = "CREATE TABLE " + stage + " AS (SELECT * FROM \"" + prepared.target()
                + "\" WHERE ts_code <> '" + prepared.board() + "') TIMESTAMP(update_time) PARTITION BY MONTH WAL "
                + "DEDUP UPSERT KEYS(ts_code,con_code,update_time)";
        jdbc.execute(statement);
        waitWal(stage, cancelled);
        var copied = new ThsMemberBoardStorage(jdbc, stage).snapshot(prepared.board());
        if (!copied.boardRows().isEmpty() || copied.otherRows() != prepared.before().otherRows()
                || !copied.otherFingerprint().equals(prepared.before().otherFingerprint()))
            throw new IllegalStateException("Copied THS unaffected boards differ; retain stage");
        String insert = "INSERT INTO \"" + stage + "\" (ts_code,con_code,con_name,weight,in_date,out_date,is_new,update_time) "
                + "VALUES (?,?,?,?,?,?,?,cast(? AS TIMESTAMP))";
        var rows = prepared.source().stream().map(mapper::toStorage).toList();
        int batches = 0;
        for (int start = 0; start < rows.size(); start += 250) {
            check(cancelled);
            var batch = rows.subList(start, Math.min(rows.size(), start + 250));
            jdbc.batchUpdate(insert, new BatchPreparedStatementSetter() {
                @Override public int getBatchSize() { return batch.size(); }
                @Override public void setValues(PreparedStatement s, int i) throws SQLException {
                    var row = batch.get(i);
                    s.setString(1, row.tsCode()); s.setString(2, row.conCode());
                    s.setString(3, row.conName());
                    if (row.weight() == null) s.setNull(4, Types.DOUBLE);
                    else s.setDouble(4, row.weight());
                    s.setString(5, row.inDate()); s.setString(6, row.outDate()); s.setString(7, row.isNew());
                    s.setLong(8, Math.addExact(Math.multiplyExact(row.updateTime().getEpochSecond(), 1000000),
                            row.updateTime().getNano() / 1000));
                }
            });
            batches++;
        }
        waitWal(stage, cancelled);
        var actual = new ThsMemberBoardStorage(jdbc, stage).snapshot(prepared.board());
        var expected = rows.stream().sorted(Comparator.comparing(ThsMemberRow::conCode)).toList();
        if (!actual.boardRows().equals(expected) || actual.otherRows() != prepared.before().otherRows()
                || !actual.otherFingerprint().equals(prepared.before().otherFingerprint()))
            throw new IllegalStateException("THS stage full-field or unaffected-board mismatch; retain stage");
        Path receipt = folder.resolve(stage + "-verified.json");
        Files.write(receipt, json.writeValueAsBytes(Map.of("stage", stage, "board", prepared.board(),
                "beforeFingerprint", prepared.before().contentFingerprint(),
                "afterFingerprint", actual.contentFingerprint(), "sourceRows", rows.size(),
                "copiedRows", actual.otherRows(), "batches", batches)), StandardOpenOption.CREATE_NEW);
        return new Verified(stage, actual, batches, receipt.toString());
    }

    private void waitWal(String table, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (!QuestDbWriteChecks.walSettled(jdbc, table)) {
            check(cancelled);
            if (System.nanoTime() > deadline) throw new IllegalStateException("THS stage WAL unresolved; retain stage");
            Thread.sleep(50);
        }
    }

    private static boolean sameBusinessValues(ThsMember a, ThsMember b) {
        return Objects.equals(a.constituentName(), b.constituentName())
                && Objects.equals(a.weight(), b.weight()) && Objects.equals(a.inDate(), b.inDate())
                && Objects.equals(a.outDate(), b.outDate()) && Objects.equals(a.isNew(), b.isNew());
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new CancellationException("THS board stage cancelled; retain stage for review");
    }
}
