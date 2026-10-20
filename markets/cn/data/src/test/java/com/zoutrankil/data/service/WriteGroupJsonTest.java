package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.domain.WriteGroupRequest;
import com.zoutrankil.data.domain.stock.basic.StockBasicDataset;
import com.zoutrankil.data.group.storage.QuestDbWriteGroupWriters;
import com.zoutrankil.data.stock.application.StockBasicJobService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

class WriteGroupJsonTest {
    @TempDir Path root;
    private final WriteGroupJson parser =
            new WriteGroupJson(
                    new DatasetRegistry(
                            List.of(
                                    () -> StockBasicDataset.DEFINITION,
                                    () -> StockBasicDataset.LATEST)));
    private static final String JSON =
            """
            {"batchId":"group-1","logicalDate":"2026-09-29","members":[{
            "memberId":"stocks","datasetId":"stock_basic_snapshot","definitionVersion":1,"batchId":"stocks-1",
            "rows":[{"snapshot_ts":"2026-09-29T00:00:00Z","ts_code":"000001.SZ","symbol":"000001",
            "name":"sample","area":null,"industry":"bank","list_date":"1991-04-03"}]}]}
            """;

    private WriteGroupRequest parse(String json) throws Exception {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void exactTypesAndNullsSurviveOwnerRoundTrip() throws Exception {
        var request = parse(JSON);
        var row = request.members().getFirst().rows().getFirst();
        assertEquals(Instant.parse("2026-09-29T00:00:00Z"), row.get("snapshot_ts", Instant.class));
        assertEquals(LocalDate.of(1991, 4, 3), row.get("list_date", LocalDate.class));
        assertNull(row.get("area", String.class));
        assertEquals(
                row.asMap(),
                StockBasicWriteGroupService.encode(StockBasicWriteGroupService.decode(row))
                        .asMap());
    }

    @Test
    void incompleteAmbiguousOrDuplicateInputIsRejected() {
        assertThrows(Exception.class, () -> parse(JSON.replace("\"area\":null,", "")));
        assertThrows(Exception.class, () -> parse(JSON.replace("1991-04-03", "19910403")));
        assertThrows(Exception.class, () -> parse(JSON.replace("\"000001.SZ\"", "123")));
        assertThrows(
                Exception.class,
                () ->
                        parse(
                                JSON.replace(
                                        "\"batchId\":\"group-1\"",
                                        "\"batchId\":\"group-1\",\"batchId\":\"other\"")));
        assertThrows(Exception.class, () -> parse(JSON + " {}"));
    }

    @Test
    void viewAndCallerSelectedTargetAreRejectedBeforeWriting() {
        assertThrows(
                Exception.class,
                () -> parse(JSON.replace("stock_basic_snapshot", "stock_basic_latest")));
        assertThrows(
                Exception.class,
                () ->
                        parse(
                                JSON.replace(
                                        "\"logicalDate\"",
                                        "\"targetId\":\"other-table\",\"logicalDate\"")));
        assertThrows(
                Exception.class,
                () -> parse(JSON.replace("\"definitionVersion\":1", "\"definitionVersion\":2")));
    }

    @Test
    void stockBasicOwnerRejectsSnapshotOutsideFrozenLogicalDateBeforeTargetAccess()
            throws Exception {
        var target = Mockito.mock(StockBasicJobService.class);
        var registry = new DatasetRegistry(List.of(() -> StockBasicDataset.DEFINITION));
        var service =
                new StockBasicWriteGroupService(
                        registry,
                        target,
                        new QuestDbWriteGroupWriters(null, null),
                        root.resolve("ledger.sqlite3").toString());
        var file = root.resolve("mismatch.json");
        Files.writeString(file, JSON.replace("2026-09-29T00:00:00Z", "2026-09-28T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> service.run(file, null));
        Mockito.verifyNoInteractions(target);
    }
}
