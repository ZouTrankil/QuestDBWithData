package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.mapper.MacroCoreMonthlyViewMapper;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A real registered ordinary view is rejected before publisher, database or ledger IO. */
class MacroCoreMonthlyViewWriteRejectionTest {
    @TempDir Path temporary;

    private static MacroCoreMonthlyView row() {
        return new MacroCoreMonthlyView(YearMonth.of(2026, 6), 1.0, 4.1, 50.3, 4.7,
                8.0, 462.06, 33671.0, 0.07395872071401999);
    }

    @Test void allTypedPublicationAndReplacementPreparationsRejectTheOrdinaryView() {
        var mapper = new MacroCoreMonthlyViewMapper();
        var rows = List.of(row());
        var limits = new DatasetWritePreparation.Limits(12, 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepare(
                MacroCoreMonthlyViewDataset.DEFINITION, rows, mapper::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareStatic(
                MacroCoreMonthlyViewDataset.DEFINITION, rows, mapper::values, limits));
        assertThrows(IllegalArgumentException.class, () -> DatasetWritePreparation.prepareWalReplace(
                MacroCoreMonthlyViewDataset.DEFINITION, rows, mapper::values, limits));
    }

    @Test void preparedWriteGroupRejectsTheViewBeforeAnyOwnerQueryOrLedgerCreation() throws Exception {
        var registry = new DatasetRegistry(List.of(
                (DatasetImplementation) () -> MacroCoreMonthlyDataset.DEFINITION,
                (DatasetImplementation) () -> MacroCoreMonthlyViewDataset.DEFINITION));
        var jdbc = mock(JdbcTemplate.class);
        Path ledger = temporary.resolve("uncreated.sqlite3");
        var service = new StockBasicWriteGroupService(registry, null,new com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters( jdbc, null), ledger.toString());
        Path input = temporary.resolve("invalid-view-write.json");
        Files.writeString(input, JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "batchId", "d105-invalid", "logicalDate", LocalDate.of(2026, 10, 7),
                "members", List.of(Map.of("memberId", "macro-view", "datasetId", "v_macro_core_monthly",
                        "definitionVersion", 1, "batchId", "d105-invalid-member",
                        "rows", List.of(new MacroCoreMonthlyViewMapper().values(row()).asMap()))))));
        assertThrows(IllegalArgumentException.class, () -> service.run(input, null));
        verifyNoInteractions(jdbc);
        assertFalse(Files.exists(ledger));
        assertFalse(Files.exists(Path.of(ledger + "-wal")));
    }
}
