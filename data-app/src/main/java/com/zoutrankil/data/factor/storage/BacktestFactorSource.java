package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.BacktestDailyMaterializationPort;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Result-table and read-view identity only; does not certify the deferred owner publication ledger. */
public final class BacktestFactorSource {
    public static final String VERSION_KEY = "backtestSourceVersion";
    private BacktestFactorSource() {}
    public static String version(JdbcTemplate jdbc) {
        var state = new BacktestDailyMaterializationPort(jdbc).sourceState();
        try { return JobDefinitionJson.canonicalMapper().writeValueAsString(state); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode backtest source version", failure); }
    }
    public static List<Map<String,Object>> stamp(JdbcTemplate jdbc, List<Map<String,Object>> rows) {
        String version = version(jdbc);
        return rows.stream().map(row -> {
            var copy = new LinkedHashMap<String,Object>(row);
            copy.put(VERSION_KEY, version);
            return (Map<String,Object>) copy;
        }).toList();
    }
    public static void requireUnchanged(JdbcTemplate jdbc, Map<String,Object> row) {
        if (row.containsKey(VERSION_KEY) && !Objects.equals(row.get(VERSION_KEY), version(jdbc)))
            throw new IllegalStateException("Backtest result table or view changed during factor computation");
    }
}
