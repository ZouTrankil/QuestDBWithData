package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;
import com.zoutrankil.data.repository.BacktestDailyMaterializedViewPort;
import com.zoutrankil.data.repository.BacktestDailyWindowVerifier;

import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pin the native MV and all four raw-source identities during a factor read. */
public final class BacktestFactorSource {
    public static final String VERSION_KEY = "backtestSourceVersion";

    private BacktestFactorSource() {}

    /** 按实际读取月窗验证完整结果；原版本戳仍只表示来源身份，不冒充数值证明。 */
    public static void verifyWindow(JdbcTemplate jdbc, LocalDate from, LocalDate to) {
        new BacktestDailyWindowVerifier(jdbc).verifyRange(BacktestDailyScope.LEGACY, from, to);
    }

    public static String version(JdbcTemplate jdbc) {
        var state = new BacktestDailyMaterializedViewPort(jdbc).sourceState();
        try {
            return JobDefinitionJson.canonicalMapper().writeValueAsString(state);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode backtest source version", failure);
        }
    }

    public static List<Map<String, Object>> stamp(
            JdbcTemplate jdbc, List<Map<String, Object>> rows) {
        String version = version(jdbc);
        return rows.stream()
                .map(
                        row -> {
                            var copy = new LinkedHashMap<String, Object>(row);
                            copy.put(VERSION_KEY, version);
                            return (Map<String, Object>) copy;
                        })
                .toList();
    }

    public static void requireUnchanged(JdbcTemplate jdbc, Map<String, Object> row) {
        if (row.containsKey(VERSION_KEY) && !Objects.equals(row.get(VERSION_KEY), version(jdbc)))
            throw new IllegalStateException(
                    "Backtest native MV or raw sources changed during factor computation");
    }
}
