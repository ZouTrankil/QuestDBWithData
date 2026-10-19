package com.zoutrankil.data.domain.policy;

import com.zoutrankil.data.domain.EtfFactorDataset;
import com.zoutrankil.data.domain.IndexMonthlyDataset;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedTablePolicyTest {
    private record Admission(IsolatedTablePolicy policy, String prefix, String formalTable,
                             Class<? extends RuntimeException> failureType, String message) {}

    private static final List<Admission> ADMISSIONS = List.of(
            new Admission(IsolatedTablePolicy.DAILY, "java_d007_daily_", "daily", IllegalStateException.class,
                    "D007 execution requires a dedicated java_d007_daily_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.STOCK_LIMIT, "java_d010_stk_limit_", "stk_limit", IllegalStateException.class,
                    "D010 execution requires a dedicated java_d010_stk_limit_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.STOCK_ST_DAILY, "java_d012_stk_st_daily_", "stk_st_daily", IllegalStateException.class,
                    "D012 execution requires a dedicated java_d012_stk_st_daily_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.ETF_DAILY, "java_d014_etf_daily_", "etf_daily", IllegalStateException.class,
                    "D014 execution requires a dedicated java_d014_etf_daily_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.ETF_ADJ, "java_d015_etf_adj_", "etf_adj", IllegalStateException.class,
                    "D015 execution requires a dedicated java_d015_etf_adj_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.INDEX_DAILY_MARKET, "java_d019_index_daily_market_", "index_daily_market", IllegalStateException.class,
                    "D019 execution requires a dedicated java_d019_index_daily_market_<suffix> target"),
            new Admission(IsolatedTablePolicy.INDEX_DAILY_BASIC, "java_d020_index_daily_basic_", "index_daily_basic", IllegalStateException.class,
                    "D020 execution requires java_d020_index_daily_basic_<suffix> isolated target"),
            new Admission(IsolatedTablePolicy.INDEX_WEIGHT, "java_d021_index_weight_", "index_weight", IllegalArgumentException.class,
                    "D021 isolated table name required"));

    @Test void acceptsExplicitSuffixesWithoutChangingIdentifierRules() {
        for (var admission : ADMISSIONS) {
            assertEquals(admission.prefix(), admission.policy().prefix());
            for (String suffix : List.of("a", "123", "_", "Test_123")) {
                assertDoesNotThrow(() -> admission.policy().require(admission.prefix() + suffix));
            }
        }
    }

    @Test void invalidIdentifiersFailBeforeDatasetScopeValidation() {
        for (var admission : ADMISSIONS) {
            for (String invalid : new String[]{null, "", "1table", "with space", admission.prefix() + "bad-name"}) {
                var failure = assertThrowsExactly(IllegalArgumentException.class,
                        () -> admission.policy().require(invalid));
                assertEquals("Invalid identifier", failure.getMessage());
                assertNull(failure.getCause());
            }
        }
    }

    @Test void formalTablesEmptySuffixesAndOtherNamespacesKeepOriginalFailures() {
        for (var admission : ADMISSIONS) {
            for (String denied : List.of(admission.formalTable(), admission.prefix(), "java_test_unrelated",
                    admission.prefix().toUpperCase(java.util.Locale.ROOT) + "test")) {
                var failure = assertThrowsExactly(admission.failureType(), () -> admission.policy().require(denied));
                assertEquals(admission.message(), failure.getMessage());
                assertNull(failure.getCause());
            }
        }
    }

    @Test void existingDatasetConstantsAndMonthlyAdmissionRemainCompatible() {
        assertEquals(8_000, EtfFactorDataset.SOURCE_ROW_CAP);
        assertEquals("java_d022_index_monthly_", IndexMonthlyDataset.ISOLATED_PREFIX);
        assertDoesNotThrow(() -> IndexMonthlyDataset.requireIsolatedTableName("java_d022_index_monthly_stage_123"));
        var failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> IndexMonthlyDataset.requireIsolatedTableName("index_monthly"));
        assertEquals("D022 isolated target required; the formal index_monthly table is external and has no dedup key",
                failure.getMessage());
    }
}
