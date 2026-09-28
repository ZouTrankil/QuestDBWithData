package com.zoutrankil.questdbwithdata.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Creates the isolated demo table and its query view from version-controlled DDL. */
@Component
public class QuestDbSchemaInitializer {
    public static final String TABLE = "java_tushare_stock_basic_qwp_test";
    public static final String LATEST_VIEW = "java_tushare_stock_basic_latest_qwp_test";

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS java_tushare_stock_basic_qwp_test (
                snapshot_ts TIMESTAMP,
                ts_code SYMBOL,
                symbol SYMBOL,
                name STRING,
                area SYMBOL,
                industry SYMBOL,
                list_date STRING
            ) TIMESTAMP(snapshot_ts) PARTITION BY DAY WAL
              DEDUP UPSERT KEYS(snapshot_ts, ts_code)
            """;

    // Keep the view query in the common SQL subset so another database adapter can reuse it.
    private static final String CREATE_LATEST_VIEW_SQL = """
            CREATE VIEW IF NOT EXISTS java_tushare_stock_basic_latest_qwp_test AS (
                SELECT snapshot_ts, ts_code, symbol, name, area, industry, list_date
                FROM (
                    SELECT snapshot_ts, ts_code, symbol, name, area, industry, list_date,
                           ROW_NUMBER() OVER (
                               PARTITION BY ts_code ORDER BY snapshot_ts DESC
                           ) AS row_num
                    FROM java_tushare_stock_basic_qwp_test
                ) ranked
                WHERE row_num = 1
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public QuestDbSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Idempotently creates the base table first, then dependent views. */
    public void initialize() {
        jdbcTemplate.execute(CREATE_TABLE_SQL);
        jdbcTemplate.execute(CREATE_LATEST_VIEW_SQL);
    }
}
