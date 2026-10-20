package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UsRegularSessionViewTest {
    @Test
    void compilerFormattingCanChangeButLiteralVersionsTimezonesAndPredicatesCannot() {
        String sql =
                "SELECT \"ts\",cast('US-v1' as varchar) FROM \"us_equity_intraday\" WHERE ts>='2026-01-01'";
        assertTrue(
                UsRegularSessionView.sameSql(
                        sql,
                        " ( select ts, CAST('US-v1' AS VARCHAR) from us_equity_intraday where ts >= '2026-01-01' ) "));
        assertFalse(UsRegularSessionView.sameSql(sql, sql.replace("US-v1", "us-v1")));
        assertFalse(UsRegularSessionView.sameSql(sql, sql + " OR 1=1"));
        assertFalse(UsRegularSessionView.sameSql(sql, sql.replace(">=", ">")));
    }
}
