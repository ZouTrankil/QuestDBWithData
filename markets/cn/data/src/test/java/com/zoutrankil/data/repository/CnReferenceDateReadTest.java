package com.zoutrankil.data.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.macro.interest.InterestRateDatasets;
import com.zoutrankil.data.domain.stock.reference.StockShockDatasets;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class CnReferenceDateReadTest {
    private final QuestDbBoundedReader reader = new QuestDbBoundedReader(mock(JdbcTemplate.class));

    private DatasetReadQuery query(DatasetDefinition def, String date) {
        return new DatasetReadQuery(
                def.columns().stream().map(DatasetDefinition.Column::logicalName).toList(),
                Map.of(),
                date,
                LocalDate.of(2026, 3, 3),
                LocalDate.of(2026, 3, 4),
                2,
                null);
    }

    @Test
    void rateReadsQuoteNumericPhysicalTenorsAndUseOnlyObservationDate() {
        for (var def :
                List.of(
                        InterestRateDatasets.SHIBOR,
                        InterestRateDatasets.HIBOR,
                        InterestRateDatasets.LPR)) {
            var prepared = reader.prepare(def, query(def, "observation_date"), null);
            assertTrue(prepared.sql().contains("AS \"observation_date\""));
            assertTrue(
                    prepared.sql()
                            .contains(
                                    def == InterestRateDatasets.LPR
                                            ? "\"1y\" AS \"rate_1y\""
                                            : "\"1w\" AS \"rate_1w\""));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reader.prepare(def, query(def, "trade_date"), null));
            assertEquals(List.of("observation_date"), def.businessKey());
            assertFalse(def.capabilities().contains(DatasetDefinition.Capability.WRITE));
        }
    }

    @Test
    void announcementReadKeepsTheFullOriginalKeyAndRejectsTradeDateAlias() {
        for (var def : List.of(StockShockDatasets.SHOCK, StockShockDatasets.HIGH_SHOCK)) {
            var prepared = reader.prepare(def, query(def, "announcement_date"), null);
            assertTrue(
                    prepared.sql()
                            .contains("cast(\"trade_date\" as long) AS \"announcement_date\""));
            assertEquals(
                    List.of("instrument_code", "announcement_date", "reason", "period"),
                    def.businessKey());
            assertEquals(List.of("ts_code", "trade_date", "reason", "period"), def.dedupKey());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> reader.prepare(def, query(def, "trade_date"), null));
        }
    }

    @Test
    void physicalIdentifierSupportDoesNotRelaxObjectOrLogicalNamesOrAllowSqlSyntax() {
        DatasetDefinition.storageIdentifier("1w");
        assertThrows(IllegalArgumentException.class, () -> DatasetDefinition.identifier("1w"));
        for (String invalid : new String[] {"1w\";DROP TABLE x;--", "1 w", "x.y", "*", ""})
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DatasetDefinition.storageIdentifier(invalid));
    }
}
