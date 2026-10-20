package com.zoutrankil.data.derived.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.domain.DatasetImplementation;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.derived.MacroCoreMonthlyDataset;
import com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.StockBasicWriteGroupService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MacroCoreMonthlyWriteGroupPreflightTest {
    @TempDir Path temporary;

    @Test
    void thirteenMonthsFailBeforeOwnerQueryLedgerOrSubmission() throws Exception {
        reject(13, false);
    }

    @Test
    void timestampOnlyNullPayloadFailsBeforeOwnerQueryLedgerOrSubmission() throws Exception {
        reject(1, true);
    }

    private void reject(int count, boolean allNull) throws Exception {
        var registry =
                new DatasetRegistry(
                        List.of((DatasetImplementation) () -> MacroCoreMonthlyDataset.DEFINITION));
        var jdbc = mock(JdbcTemplate.class);
        var owner = mock(MacroCoreMonthlyJobService.class);
        Path ledger = temporary.resolve("uncreated.sqlite3");
        var service =
                new StockBasicWriteGroupService(
                        registry,
                        null,
                        new QuestDbWriteGroupWriters(jdbc, null),
                        ledger.toString());
        var ownerField =
                StockBasicWriteGroupService.class.getDeclaredField("macroCoreMonthlyTarget");
        ownerField.setAccessible(true);
        ownerField.set(service, owner);
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) {
            var row = new LinkedHashMap<String, Object>();
            for (String column : MacroCoreMonthlyDataset.STORAGE_COLUMNS)
                row.put(
                        column,
                        column.equals("month")
                                ? LocalDate.of(2025, 1, 1).plusMonths(i)
                                : allNull ? null : 1.0d);
            rows.add(row);
        }
        Path request = temporary.resolve("input.json");
        Files.writeString(
                request,
                JobDefinitionJson.mapper()
                        .writeValueAsString(
                                Map.of(
                                        "batchId",
                                        "d104-invalid",
                                        "logicalDate",
                                        LocalDate.of(2026, 10, 6),
                                        "members",
                                        List.of(
                                                Map.of(
                                                        "memberId",
                                                        "styles",
                                                        "datasetId",
                                                        "macro_core_monthly",
                                                        "definitionVersion",
                                                        1,
                                                        "batchId",
                                                        "d104-invalid-member",
                                                        "rows",
                                                        rows)))));
        assertThrows(IllegalArgumentException.class, () -> service.run(request, null));
        verifyNoInteractions(owner, jdbc);
        assertFalse(Files.exists(ledger));
        assertFalse(Files.exists(Path.of(ledger + "-wal")));
    }
}
