package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.DatasetValues;
import com.zoutrankil.data.domain.WriteGroupRequest;
import com.zoutrankil.data.domain.fund.EtfAdjDataset;
import com.zoutrankil.data.domain.stock.basic.ExchangeCalendarDataset;
import com.zoutrankil.data.etf.storage.EtfAdjReadRepository;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class VersionedDatasetRegistrationTest {
    private static final String TABLE = "java_d015_etf_adj_registration";

    @Test
    void originalGroupFingerprintRemainsExactWhenActiveLayoutChanges() {
        var reader = mock(QuestDbBoundedReader.class);
        var legacy =
                new DatasetRegistry(
                        List.<DatasetImplementation>of(
                                () -> ExchangeCalendarDataset.DEFINITION,
                                new EtfAdjReadRepository(reader, TABLE)));
        var modern =
                new DatasetRegistry(
                        List.<DatasetImplementation>of(
                                () -> ExchangeCalendarDataset.DEFINITION,
                                new EtfAdjReadRepository(
                                        reader, TABLE, CnTradeDateLayout.TRADE_DATE_V2)));
        var day = LocalDate.of(2026, 9, 28);
        var row =
                new DatasetValues(
                        Map.of("ts_code", "510300.SH", "trade_date", day, "adj_factor", 1.25));
        var old =
                new WriteGroupRequest(
                        "batch",
                        day,
                        List.of(
                                new WriteGroupRequest.Member(
                                        "adj", "etf_adj", 1, "member-batch", List.of(row))));
        var targets = Map.of("etf_adj", "static-v2-" + "a".repeat(64));
        var first = WriteGroupPlan.prepare(old, legacy, targets);
        var restored = WriteGroupPlan.prepare(old, modern, targets);
        assertEquals(first.fingerprint(), restored.fingerprint());
        assertEquals(
                first.members().getFirst().definition(),
                restored.members().getFirst().definition());
        var next =
                new WriteGroupRequest(
                        "batch",
                        day,
                        List.of(
                                new WriteGroupRequest.Member(
                                        "adj", "etf_adj", 2, "member-batch", List.of(row))));
        var selected = WriteGroupPlan.prepare(next, modern, targets);
        assertEquals(2, selected.members().getFirst().definition().schemaVersion());
        assertNotEquals(first.fingerprint(), selected.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> modern.requireDefinition("etf_adj", 3));
        verifyNoInteractions(reader);
    }

    @Test
    void unversionedOwnersRejectOtherVersionsAndVersionedOwnersCannotChangeTargets() {
        var old = EtfAdjDataset.definition(TABLE);
        DatasetImplementation fixed = () -> old;
        assertSame(old, fixed.definition(1));
        assertThrows(IllegalArgumentException.class, () -> fixed.definition(2));
        for (var altered :
                List.of(
                        EtfAdjDataset.definition(
                                "java_d015_etf_adj_other", CnTradeDateLayout.TRADE_DATE_V2),
                        old)) {
            DatasetImplementation bad =
                    new DatasetImplementation() {
                        public DatasetDefinition definition() {
                            return old;
                        }

                        public DatasetDefinition definition(int ignored) {
                            return altered;
                        }
                    };
            var registry =
                    new DatasetRegistry(
                            List.<DatasetImplementation>of(
                                    () -> ExchangeCalendarDataset.DEFINITION, bad));
            assertThrows(
                    IllegalArgumentException.class, () -> registry.requireDefinition("etf_adj", 2));
        }
    }
}
