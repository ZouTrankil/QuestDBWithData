package com.zoutrankil.questdbwithdata.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.domain.temporal.TemporalValues;
import com.zoutrankil.questdbwithdata.mapper.BacktestDailyMapper;
import com.zoutrankil.questdbwithdata.service.DatasetRegistry;
import com.zoutrankil.questdbwithdata.service.ReadGroupReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in, read-only check against the configured local QuestDB and current derived view. */
class BacktestDailyLiveReadAcceptanceTest {
    private static final LocalDate SAMPLE_DATE = LocalDate.of(2026, 9, 17);
    private static final int PAGE_SIZE = 200;

    @Test void typedBoundedReadMatchesCurrentViewForEveryProjectedField() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("D090_LIVE_READ")),
                "Set D090_LIVE_READ=true for the bounded read-only QuestDB check");
        String host = required("APP_QUESTDB_HOST");
        String username = required("APP_QUESTDB_USERNAME");
        String password = required("APP_QUESTDB_PASSWORD");
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_QUESTDB_PGPORT", "8812"));
        String database = System.getenv().getOrDefault("APP_QUESTDB_DATABASE", "qdb");

        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=disable");
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(20);

        long[] formalBefore = tableIdentity(jdbc);
        var reader = new QuestDbBoundedReader(jdbc);
        var repository = new BacktestDailyReadRepository(reader);
        var formalQuery = new DatasetReadQuery(BacktestDailyDataset.DEFINITION.storageColumns(),
                Map.of("trade_date", SAMPLE_DATE), null, null, null, PAGE_SIZE, null);
        var formalRead = reader.prepare(BacktestDailyDataset.DEFINITION, formalQuery, null);
        var page = repository.findForDate(SAMPLE_DATE, PAGE_SIZE, null);
        assertEquals(PAGE_SIZE, page.rows().size(), "bounded formal-table sample should fill one page");

        String viewSql = "SELECT cast(trade_date AS long) AS trade_date_micros, ts_code, open, high, low, close, "
                + "vol, amount, adj_factor, up_limit, down_limit, is_suspended, is_st "
                + "FROM v_backtest_daily WHERE trade_date = cast(? AS TIMESTAMP) "
                + "ORDER BY trade_date, ts_code LIMIT " + PAGE_SIZE;
        long dayMicros = new TemporalValues.CalendarTimestamp(SAMPLE_DATE).storageEpoch(TemporalValues.EpochUnit.MICROS);
        var viewRows = jdbc.query(viewSql, (rs, rowNum) -> fromView(rs), dayMicros);
        assertEquals(PAGE_SIZE, viewRows.size());
        assertEquals(page.rows(), viewRows, "formal legacy snapshot and current view must match across all 13 fields");
        long[] formalAfter = tableIdentity(jdbc);
        assertEquals(formalBefore[0], formalAfter[0], "formal table row count changed during read");
        assertEquals(formalBefore[1], formalAfter[1], "formal table transaction changed during read");

        var group = new ReadGroupReader(new DatasetRegistry(List.of(repository)), reader,
                List.of(new ReadGroupReader.Binding<>(BacktestDailyDataset.DEFINITION, BacktestDaily.class,
                        new BacktestDailyMapper()::fromValues, () -> null)));
        var query = new DatasetReadQuery(BacktestDailyDataset.DEFINITION.storageColumns(),
                Map.of("trade_date", SAMPLE_DATE), null, null, null, PAGE_SIZE, null);
        var groupResult = group.read(new ReadGroupRequest(List.of(new ReadGroupRequest.Member(
                "d090-live-sample", "backtest_daily", 1, query)), java.time.Duration.ofSeconds(30)), () -> false);
        assertTrue(groupResult.complete());
        assertEquals(page.rows(), groupResult.require("d090-live-sample").typedPage(BacktestDaily.class).rows());

        Path evidence = Path.of("artifacts/java-migration/D090/commands/java-live-read-20260930.json");
        Files.createDirectories(evidence.getParent());
        var report = new LinkedHashMap<String, Object>();
        report.put("task_id", "D090");
        report.put("checked_at", Instant.now().toString());
        report.put("mode", "read_only");
        report.put("formal_table", "backtest_daily");
        report.put("active_view", "v_backtest_daily");
        report.put("date", SAMPLE_DATE.toString());
        report.put("page_size", PAGE_SIZE);
        report.put("formal_query", formalRead.sql());
        report.put("formal_parameters", formalRead.parameters().stream()
                .map(bound -> Map.of("column", bound.column().logicalName(), "storage_value", bound.storageValue().toString()))
                .toList());
        report.put("view_query", viewSql);
        report.put("view_parameter_trade_date_epoch_micros", dayMicros);
        report.put("formal_rows", page.rows().size());
        report.put("view_rows", viewRows.size());
        report.put("all_13_fields_equal", true);
        report.put("read_group_typed_binding", "verified");
        report.put("formal_table_row_count_before", formalBefore[0]);
        report.put("formal_table_row_count_after", formalAfter[0]);
        report.put("formal_table_txn_before", formalBefore[1]);
        report.put("formal_table_txn_after", formalAfter[1]);
        report.put("sample_rows", page.rows().subList(0, Math.min(3, page.rows().size())).stream()
                .map(BacktestDailyLiveReadAcceptanceTest::sampleEvidence).toList());
        report.put("writes", "none");
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), report);
    }

    private static BacktestDaily fromView(ResultSet rs) throws java.sql.SQLException {
        long micros = rs.getLong("trade_date_micros");
        LocalDate date = TemporalValues.CalendarTimestamp
                .fromStorageEpoch(micros, TemporalValues.EpochUnit.MICROS).date();
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", date);
        values.put("ts_code", rs.getString("ts_code"));
        values.put("open", nullableDouble(rs, "open"));
        values.put("high", nullableDouble(rs, "high"));
        values.put("low", nullableDouble(rs, "low"));
        values.put("close", nullableDouble(rs, "close"));
        values.put("vol", nullableDouble(rs, "vol"));
        values.put("amount", nullableDouble(rs, "amount"));
        values.put("adj_factor", nullableDouble(rs, "adj_factor"));
        values.put("up_limit", nullableDouble(rs, "up_limit"));
        values.put("down_limit", nullableDouble(rs, "down_limit"));
        Long suspended = rs.getObject("is_suspended", Long.class);
        values.put("is_suspended", suspended == null ? null : Math.toIntExact(suspended));
        values.put("is_st", nullableInteger(rs, "is_st"));
        return new BacktestDailyMapper().fromValues(new DatasetValues(values));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Map<String, Object> sampleEvidence(BacktestDaily row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate().toString());
        values.put("ts_code", row.tsCode());
        values.put("open", row.open());
        values.put("high", row.high());
        values.put("low", row.low());
        values.put("close", row.close());
        values.put("vol", row.vol());
        values.put("amount", row.amount());
        values.put("adj_factor", row.adjFactor());
        values.put("up_limit", row.upLimit());
        values.put("down_limit", row.downLimit());
        values.put("is_suspended", row.isSuspended());
        values.put("is_st", row.isSt());
        return values;
    }

    private static long[] tableIdentity(JdbcTemplate jdbc) {
        return jdbc.query("SELECT table_row_count, table_txn FROM tables() WHERE table_name='backtest_daily'",
                rs -> {
                    if (!rs.next()) throw new IllegalStateException("backtest_daily is missing from tables()");
                    return new long[]{((Number) rs.getObject(1)).longValue(), ((Number) rs.getObject(2)).longValue()};
                });
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required environment setting: " + name);
        return value;
    }
}
