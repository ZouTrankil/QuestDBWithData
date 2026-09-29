package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.dto.TushareThsMemberDto;
import com.zoutrankil.questdbwithdata.domain.ThsMemberDataset;
import com.zoutrankil.questdbwithdata.mapper.ThsMemberMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThsMemberMappingTest {
    @Test void allProviderFieldsRoundTripThroughPhysicalAndLogicalValues() {
        var mapper = new ThsMemberMapper();
        var observed = Instant.parse("2026-09-29T01:02:03.123456Z");
        var row = mapper.fromSource(new TushareThsMemberDto("700001.TI", "920288.BJ", "华大海天",
                0.125, "20240229", "20260301", "N"), observed);
        assertEquals(LocalDate.of(2024, 2, 29), row.inDate());
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertEquals(row, mapper.fromValues(mapper.values(row)));
        assertEquals(List.of("board_code", "constituent_code"), ThsMemberDataset.DEFINITION.businessKey());
        assertEquals(List.of("ts_code", "con_code", "update_time"), ThsMemberDataset.DEFINITION.dedupKey());
    }

    @Test void providerNullsAndNonAshareSuffixRemainTyped() {
        var mapper = new ThsMemberMapper();
        var observed = Instant.parse("2026-09-29T01:02:03.123456Z");
        var row = mapper.fromSource(new TushareThsMemberDto("885800.TI", "ABCD.O", "sample",
                null, null, null, "Y"), observed);
        assertNull(mapper.toStorage(row).inDate());
        assertEquals(row, mapper.fromStorage(mapper.toStorage(row)));
        assertThrows(RuntimeException.class, () -> mapper.fromSource(new TushareThsMemberDto(
                "885800.TI", "ABCD.O", "sample", null, "20260229", null, "Y"), observed));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromSource(new TushareThsMemberDto(
                "885800.TI", "ABCD.O", "sample", Double.NaN, null, null, "Y"), observed));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromSource(new TushareThsMemberDto(
                "885800.TI", "ABCD.O", "sample", null, null, null, "X"), observed));
    }
}
