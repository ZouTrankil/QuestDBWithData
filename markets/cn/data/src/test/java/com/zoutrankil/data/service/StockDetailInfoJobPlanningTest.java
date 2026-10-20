package com.zoutrankil.data.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.stock.application.StockDetailInfoJobService;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

class StockDetailInfoJobPlanningTest {
    @TempDir Path root;

    @Test
    void mutuallyExclusiveBoundedScopesFreezeWithoutNetworkDatabaseOrLedgerCreation() {
        var jdbc = mock(JdbcTemplate.class);
        var pages = mock(TusharePageService.class);
        var ledger = root.resolve("not-created.sqlite");
        var owner =
                new StockDetailInfoJobService(
                        pages, new QuestDbStockDetailTarget(jdbc, "stock_detail_info"), ledger);
        var day = LocalDate.of(2026, 9, 29);
        var selected = owner.plan(List.of("T600018.SH", "000001.SZ"), false, day);
        assertEquals(SyncJobDefinition.Mode.INCREMENTAL, selected.mode());
        assertEquals(day, selected.from());
        assertEquals(day, selected.to());
        assertTrue((Boolean) owner.plan(List.of(), true, day).parameters().get("discover"));
        assertThrows(IllegalArgumentException.class, () -> owner.plan(List.of(), false, day));
        assertThrows(
                IllegalArgumentException.class, () -> owner.plan(List.of("000001.SZ"), true, day));
        assertThrows(
                IllegalArgumentException.class,
                () -> owner.plan(List.of("000001.SZ", "000001.SZ"), false, day));
        assertThrows(
                IllegalArgumentException.class, () -> owner.plan(List.of("invalid"), false, day));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        owner.plan(
                                IntStream.range(0, 21)
                                        .mapToObj(i -> String.format("%06d.SZ", i))
                                        .toList(),
                                false,
                                day));
        assertFalse(Files.exists(ledger));
        verifyNoInteractions(jdbc, pages);
    }
}
