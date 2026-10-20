package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyLayout;
import com.zoutrankil.data.derived.port.RetailSentimentDailySession;
import com.zoutrankil.data.derived.port.RetailSentimentDailyTarget;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Configured factory; construction does not inspect an instance or admit a mutation. */
public final class QuestDbRetailSentimentDailyTarget
        implements RetailSentimentDailyTarget.Versioned {
    private final RetailSentimentDailyLayout layout;
    private final JdbcTemplate jdbc;
    private final QuestDbProperties properties;
    private final boolean mutationsEnabled;
    private final String expectedTargetId;

    public QuestDbRetailSentimentDailyTarget(
            JdbcTemplate jdbc,
            QuestDbProperties properties,
            boolean mutationsEnabled,
            String expectedTargetId) {
        this(
                jdbc,
                properties,
                mutationsEnabled,
                expectedTargetId,
                RetailSentimentDailyLayout.LEGACY);
    }

    public QuestDbRetailSentimentDailyTarget(
            JdbcTemplate jdbc,
            QuestDbProperties properties,
            boolean mutationsEnabled,
            String expectedTargetId,
            RetailSentimentDailyLayout layout) {
        this.layout = Objects.requireNonNull(layout);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.properties = Objects.requireNonNull(properties);
        this.mutationsEnabled = mutationsEnabled;
        this.expectedTargetId = expectedTargetId == null ? "" : expectedTargetId.trim();
    }

    @Override
    public RetailSentimentDailyLayout layout() {
        return layout;
    }

    @Override
    public RetailSentimentDailyTarget forLayout(RetailSentimentDailyLayout original) {
        return new QuestDbRetailSentimentDailyTarget(
                jdbc, properties, mutationsEnabled, expectedTargetId, original);
    }

    @Override
    public RetailSentimentDailySession newSession() {
        return new RetailSentimentDailyMaterializationPort(
                jdbc,
                properties,
                mutationsEnabled,
                expectedTargetId.isBlank() ? null : expectedTargetId,
                layout);
    }
}
