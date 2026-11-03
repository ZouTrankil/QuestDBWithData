package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ReferenceKeyRevisionTest {
    @TempDir Path root;

    @Test
    void differentBrokersKeepTheSameStockAndTheRawCapacityCannotBeHiddenByDeduplication()
            throws Exception {
        var rows = new ArrayList<Map<String, JsonNode>>();
        for (String broker : List.of("券商甲", "券商乙"))
            rows.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "month",
                                    new TextNode("202609"),
                                    "broker",
                                    new TextNode(broker),
                                    "ts_code",
                                    new TextNode("000001.SZ"),
                                    "name",
                                    new TextNode("平安银行"))));
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals(Map.of("month", "202609"), params);
                            return new PageExecutor.Page(List.copyOf(rows), null, false, null);
                        });
        var target = new ContractTableSyncTest.Memory("broker_recommend");
        var owner =
                new ContractTableSyncOwner(
                        "broker_recommend", target, pages, root.resolve("broker.sqlite"));
        var date = LocalDate.of(2026, 9, 1);
        var plan = owner.plan("ALL", date, date, LocalDate.of(2026, 10, 21), Mode.RECONCILE);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        rows.get(1).put("name", new TextNode("来源名称修订"));
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        int sent = target.sends;
        rows.clear();
        for (int i = 0; i < 1000; i++)
            rows.add(
                    Map.of(
                            "month",
                            new TextNode("202609"),
                            "broker",
                            new TextNode("券商" + i),
                            "ts_code",
                            new TextNode("000001.SZ"),
                            "name",
                            new TextNode("平安银行")));
        assertNotEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(sent, target.sends);
    }

    @Test
    void alertExpiryIsMutableAndHistoryOutsideTheRequestedStartDateIsNeverWritten()
            throws Exception {
        var rows = new ArrayList<Map<String, JsonNode>>();
        for (String type : List.of("交易所重点提示证券", "其他重点提示"))
            rows.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "ts_code",
                                    new TextNode("000001.SZ"),
                                    "name",
                                    new TextNode("平安银行"),
                                    "start_date",
                                    new TextNode("20260928"),
                                    "end_date",
                                    new TextNode("20261009"),
                                    "type",
                                    new TextNode(type))));
        var older = new LinkedHashMap<>(rows.getFirst());
        older.put("start_date", new TextNode("20260901"));
        rows.add(older);
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals(Map.of("ts_code", "000001.SZ"), params);
                            return new PageExecutor.Page(List.copyOf(rows), null, false, null);
                        });
        var target = new ContractTableSyncTest.Memory("stk_alert");
        var owner =
                new ContractTableSyncOwner(
                        "stk_alert", target, pages, root.resolve("alert.sqlite"));
        var date = LocalDate.of(2026, 9, 28);
        var plan = owner.plan("000001.SZ", date, date, LocalDate.of(2026, 10, 21), Mode.RECONCILE);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        rows.getFirst().put("end_date", new TextNode("20261020"));
        rows.get(1).put("end_date", NullNode.instance);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(2, target.rows.size());
        assertTrue(
                target.rows.values().stream().anyMatch(r -> "20261020".equals(r.get("end_date"))));
        assertTrue(target.rows.values().stream().anyMatch(r -> r.get("end_date") == null));
        int sent = target.sends;
        rows.clear();
        for (int i = 0; i < 1000; i++) {
            var row = new LinkedHashMap<>(older);
            row.put("type", new TextNode("提示" + i));
            rows.add(row);
        }
        assertNotEquals(SyncRunState.VERIFIED_EMPTY, owner.run(plan).state());
        assertEquals(sent, target.sends);
    }
}
