package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.port.MarketBreadthDailyV1Session;
import com.zoutrankil.data.derived.port.MarketBreadthDailyV1Target;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configured factory; construction does not inspect an instance or admit a mutation. */
public final class QuestDbMarketBreadthDailyV1Target implements MarketBreadthDailyV1Target {
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;

    public QuestDbMarketBreadthDailyV1Target(JdbcTemplate jdbc, QuestDbProperties properties,
                               boolean mutationsEnabled, String expectedTargetId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        this.expectedTargetId = expectedTargetId == null ? "" : expectedTargetId.trim();
    }

    @Override public MarketBreadthDailyV1Session newSession() {
        return new MarketBreadthDailyV1MaterializationPort(jdbc, properties, mutationsEnabled,
                expectedTargetId.isBlank() ? null : expectedTargetId);
    }
}
