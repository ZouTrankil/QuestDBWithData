package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.port.MarketBreadthDailySession;
import com.zoutrankil.data.derived.port.MarketBreadthDailyTarget;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Configured factory; construction does not inspect an instance or admit a mutation. */
public final class QuestDbMarketBreadthDailyTarget implements MarketBreadthDailyTarget {
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;

    public QuestDbMarketBreadthDailyTarget(
            JdbcTemplate jdbc,
            QuestDbProperties properties,
            boolean mutationsEnabled,
            String expectedTargetId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        this.expectedTargetId = expectedTargetId == null ? "" : expectedTargetId.trim();
    }

    @Override
    public MarketBreadthDailySession newSession() {
        return new MarketBreadthDailyMaterializationPort(
                jdbc,
                properties,
                mutationsEnabled,
                expectedTargetId.isBlank() ? null : expectedTargetId);
    }
}
