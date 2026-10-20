package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.config.TushareProperties;
import com.zoutrankil.data.financial.application.FinancialDailyBudget;
import com.zoutrankil.data.financial.domain.FinancialContract;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

class FinancialDailyBudgetTest {
    @Test
    void fullMarketCannotBeAdmittedAtDefaultQuotaEvenWithAnInflatedRequestBudget() {
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                FinancialDailyBudget.requireFits(
                                        5000,
                                        5,
                                        FinancialContract.DATASETS,
                                        new TushareProperties(),
                                        1_000_000,
                                        Duration.ofHours(12)));
        assertTrue(failure.getMessage().contains("sourceRequests=125000"));
    }

    @Test
    void approvedSmallScopeIncludesAllFiveOwnersAndClientRetries() {
        var estimate =
                FinancialDailyBudget.requireFits(
                        10,
                        5,
                        FinancialContract.DATASETS,
                        new TushareProperties(),
                        1000,
                        Duration.ofHours(4));
        assertEquals(250, estimate.sourceRequests());
        assertEquals(750, estimate.maximumAttempts());
        assertEquals(Duration.ofMinutes(25), estimate.minimumDuration());
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancialDailyBudget.requireFits(
                                10,
                                5,
                                FinancialContract.DATASETS,
                                new TushareProperties(),
                                749,
                                Duration.ofHours(4)));
    }

    @Test
    void endpointOverridesAndGlobalQuotaCanEachMakeTheWindowTooSmall() {
        var quota = new TushareProperties();
        quota.setEndpointLimits(Map.of("income", 1));
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancialDailyBudget.requireFits(
                                10,
                                5,
                                FinancialContract.DATASETS,
                                quota,
                                1000,
                                Duration.ofHours(1)));
        quota.setGlobalPerMinute(1);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancialDailyBudget.requireFits(
                                10,
                                5,
                                FinancialContract.DATASETS,
                                quota,
                                1000,
                                Duration.ofHours(4)));
    }
}
