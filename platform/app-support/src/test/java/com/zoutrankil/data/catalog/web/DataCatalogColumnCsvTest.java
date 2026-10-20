package com.zoutrankil.data.catalog.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.NumericContract;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
                        List.of(
                                column,
                                new DataCatalog.Column(
                                        "amount",
                                        "DOUBLE",
                                        true,
                                        "成交额",
                                        null,
                                        null,
                                        "amount",
                                        "amount",
                                        new NumericContract(
                                                "千元",
                                                "元",
                                                new BigDecimal("1000"),
                                                "CNY",
                                                "不适用",
                                                "不适用",
                                                "null 保持缺失",
                                                "来源,含逗号"))),
                        List.of("announcement_date"));
        var csv = new String(DataCatalogCsv.encodeColumns(detail), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF对象名,物理字段,逻辑字段,来源字段"));
        assertTrue(csv.contains("数值原单位,基准单位,原值到基准倍率,币种"));
        assertTrue(csv.contains("千元,元,1000,CNY,不适用,不适用,null 保持缺失,\"来源,含逗号\""));
        assertTrue(
                csv.contains(
                        "shock,trade_date,announcement_date,source_date,TIMESTAMP,BUSINESS_DATE,BASIC,calendar,DAY,是,\"公告,原文\","));
    }
}
