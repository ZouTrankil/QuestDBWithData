package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.derived.BacktestDailyScope;

import org.springframework.jdbc.core.JdbcTemplate;

/** 依据 QuestDB 原生物化视图及原始表状态判断能否读取，不依赖 Java 发布账本。 */
public final class BacktestDailyMaterializationReadGuard {
    private BacktestDailyMaterializationReadGuard() {}

    public static final class NotReadyException extends IllegalStateException {
        public enum Reason {
            REFRESH_PENDING,
            SOURCES_CHANGED
        }

        private final Reason reason;

        public NotReadyException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    public static boolean applies(DatasetDefinition definition) {
        return BacktestDailyMaterializedViewPort.NAME.equals(definition.datasetId())
                || BacktestDailyMaterializedViewPort.NAME.equals(definition.objectName())
                || definition.objectName().startsWith(BacktestDailyScope.V2_OUTPUT_PREFIX);
    }

    public static String version(JdbcTemplate jdbc, DatasetDefinition definition) {
        if (!applies(definition)
                || definition.objectKind() != DatasetDefinition.ObjectKind.MATERIALIZED_VIEW)
            throw new IllegalArgumentException("The native backtest MV is required");
        return new BacktestDailyMaterializedViewPort(
                        jdbc, BacktestDailyScope.fromDefinition(definition))
                .sourceVersion();
    }
}
