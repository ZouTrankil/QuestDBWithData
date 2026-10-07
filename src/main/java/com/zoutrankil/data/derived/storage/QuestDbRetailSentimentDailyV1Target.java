package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Session;
import com.zoutrankil.data.derived.port.RetailSentimentDailyV1Target;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configured factory; construction does not inspect an instance or admit a mutation. */
public final class QuestDbRetailSentimentDailyV1Target implements RetailSentimentDailyV1Target {
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;

    public QuestDbRetailSentimentDailyV1Target(JdbcTemplate jdbc, QuestDbProperties properties,
                               boolean mutationsEnabled, String expectedTargetId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        this.expectedTargetId = expectedTargetId == null ? "" : expectedTargetId.trim();
    }

    @Override public RetailSentimentDailyV1Session newSession() {
        return new RetailSentimentDailyV1MaterializationPort(jdbc, properties, mutationsEnabled,
                expectedTargetId.isBlank() ? null : expectedTargetId);
    }
}
