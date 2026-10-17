package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.domain.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquityStyleMonthlyWriteGroupPreflightTest {
    @TempDir Path temporary;
    @Test void thirteenMonthsFailBeforeOwnerQueryLedgerOrSubmission() throws Exception {reject(13,false);}
    @Test void timestampOnlyNullPayloadFailsBeforeOwnerQueryLedgerOrSubmission() throws Exception {reject(1,true);}
    private void reject(int count,boolean allNull) throws Exception {
        var registry=new DatasetRegistry(List.of((DatasetImplementation)()->EquityStyleMonthlyDataset.DEFINITION,
                (DatasetImplementation)()->IndexMonthlyDataset.DEFINITION,(DatasetImplementation)()->IndexCatalogDataset.DEFINITION));
        var jdbc=mock(JdbcTemplate.class);var owner=mock(EquityStyleMonthlyJobService.class);Path ledger=temporary.resolve("uncreated.sqlite3");
        var service=new StockBasicWriteGroupService(registry,null,new com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters(jdbc,null),ledger.toString());
        var ownerField=StockBasicWriteGroupService.class.getDeclaredField("equityStyleMonthlyTarget");ownerField.setAccessible(true);ownerField.set(service,owner);
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<count;i++) {
            var row=new LinkedHashMap<String,Object>();for(String column:EquityStyleMonthlyDataset.STORAGE_COLUMNS)
                row.put(column,column.equals("month")?LocalDate.of(2025,1,1).plusMonths(i):allNull?null:1.0d);rows.add(row);
        }
        Path request=temporary.resolve("input.json");Files.writeString(request,JobDefinitionJson.mapper().writeValueAsString(Map.of(
                "batchId","d103-invalid","logicalDate",LocalDate.of(2026,10,6),"members",List.of(Map.of("memberId","styles","datasetId","equity_style_monthly",
                "definitionVersion",1,"batchId","d103-invalid-member","rows",rows)))));
        assertThrows(IllegalArgumentException.class,()->service.run(request,null));verifyNoInteractions(owner,jdbc);
        assertFalse(Files.exists(ledger));assertFalse(Files.exists(Path.of(ledger+"-wal")));
    }
}
