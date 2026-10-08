package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Narrow entry points for shared bounded reads; concrete guard helpers stay private to storage. */
public final class DerivedMonthlyReadGuards {
    private DerivedMonthlyReadGuards() {}

    public static void validateEquityStyle(DatasetDefinition definition, DatasetReadQuery query) {
        QuestDbEquityStyleReadGuard.validate(definition, query);
    }
    public static boolean appliesEquityStyle(DatasetDefinition definition) {
        return QuestDbEquityStyleReadGuard.applies(definition);
    }
    public static String versionEquityStyle(JdbcTemplate jdbc, DatasetDefinition definition) {
        return QuestDbEquityStyleReadGuard.version(jdbc, definition);
    }

    public static void validateMacroCore(DatasetDefinition definition, DatasetReadQuery query) {
        QuestDbMacroCoreReadGuard.validate(definition, query);
    }
    public static boolean appliesMacroCore(DatasetDefinition definition) {
        return QuestDbMacroCoreReadGuard.applies(definition);
    }
    public static String versionMacroCore(JdbcTemplate jdbc, DatasetDefinition definition) {
        return QuestDbMacroCoreReadGuard.version(jdbc, definition);
    }

    public static void validateMacroCoreView(DatasetDefinition definition, DatasetReadQuery query) {
        QuestDbMacroCoreViewReadGuard.validate(definition, query);
    }
    public static boolean appliesMacroCoreView(DatasetDefinition definition) {
        return QuestDbMacroCoreViewReadGuard.applies(definition);
    }
    public static String versionMacroCoreView(JdbcTemplate jdbc, DatasetDefinition definition) {
        return QuestDbMacroCoreViewReadGuard.version(jdbc, definition);
    }
}
