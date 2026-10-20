package com.zoutrankil.data.catalog.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.domain.DatasetDefinition;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

class DataCatalogColumnCsvTest {
    @Test
    void fieldCsvUsesRegisteredMappingsTemporalContractAndCsvQuoting() {
        var time =
                new DatasetDefinition.TemporalContract(
                        DatasetDefinition.TemporalKind.BUSINESS_DATE,
                        "BASIC",
                        "calendar",
                        "DAY",
                        "公告日期");
        var column =
                new DataCatalog.Column(
                        "trade_date",
                        "TIMESTAMP",
                        false,
                        "公告,原文",
                        time,
                        null,
                        "source_date",
                        "announcement_date");
        var entry =
                new DataCatalog.Entry(
                        "shock",
                        null,
                        null,
                        DatasetDefinition.ObjectKind.TABLE,
                        DataCatalog.Presence.PRESENT,
                        DataCatalog.ClassificationStatus.CLASSIFIED,
                        null,
                        null,
                        null,
                        "trade_date",
                        null,
                        null,
                        List.of(),
                        true);
        var detail =
                new DataCatalog.Detail(
                        "fixture",
                        Instant.EPOCH,
                        entry,
                        List.of(column),
                        List.of("announcement_date"));
        var csv = new String(DataCatalogCsv.encodeColumns(detail), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF对象名,物理字段,逻辑字段,来源字段"));
        assertTrue(
                csv.contains(
                        "shock,trade_date,announcement_date,source_date,TIMESTAMP,BUSINESS_DATE,BASIC,calendar,DAY,是,\"公告,原文\","));
    }
}
