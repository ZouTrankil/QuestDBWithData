package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Configured factory; construction does not inspect an instance or admit a mutation. */
public final class QuestDbRetailSentimentDailyTarget implements RetailSentimentDailyTarget {
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;

    public QuestDbRetailSentimentDailyTarget(
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
    public RetailSentimentDailySession newSession() {
        return new RetailSentimentDailyMaterializationPort(
                jdbc,
                properties,
                mutationsEnabled,
                expectedTargetId.isBlank() ? null : expectedTargetId);
    }
}
