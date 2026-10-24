package com.zoutrankil.market.cn.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zoutrankil.data.domain.SyncJobDefinition.Mode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.application.ContractTableSyncOwner;
import com.zoutrankil.market.cn.observation.mapper.ContractTableDefinition;
import com.zoutrankil.market.cn.observation.port.ContractTableTarget;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CyqChipsSyncTest {
    @TempDir Path root;
    final List<Map<String, JsonNode>> raw = new ArrayList<>();
    final List<Integer> offsets = new ArrayList<>();
    int failedOffset = -1;
    boolean repeat;
    final Target target = new Target();
    static final LocalDate DATE = LocalDate.of(2026, 8, 28), LOGICAL = LocalDate.of(2026, 10, 21);

    void populate() {
        for (int price = 1007; price >= 1; price--)
            raw.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "ts_code",
                                    new TextNode("000001.SZ"),
                                    "trade_date",
                                    new TextNode("20260828"),
                                    "price",
                                    new DoubleNode(price),
                                    "percent",
                                    new DoubleNode(price == 1007 ? 49.7d : 0.05d))));
    }

    ContractTableSyncOwner owner() {
        var pages = mock(TusharePageService.class);
        when(pages.fetcher(any(), any()))
                .thenReturn(
                        params -> {
                            assertEquals("000001.SZ", params.get("ts_code"));
                            assertEquals("20260828", params.get("trade_date"));
                            int offset = ((Number) params.get("offset")).intValue(),
                                    limit = ((Number) params.get("limit")).intValue();
                            offsets.add(offset);
                            if (offset == failedOffset) throw new IllegalStateException("第二页来源失败");
                            int start = repeat ? 0 : offset;
                            return new PageExecutor.Page(
                                    List.copyOf(
                                            raw.subList(
                                                    Math.min(start, raw.size()),
                                                    Math.min(start + limit, raw.size()))),
                                    null,
                                    false,
                                    null);
                        });
        return new ContractTableSyncOwner("cyq_chips", target, pages, root.resolve("sync.sqlite"));
    }

    @Test
    void allPagesAreCollectedBeforeOnePairedArrayRowIsWrittenAndRevisionsKeepIndices()
            throws Exception {
        populate();
        var owner = owner();
        var plan = owner.plan("000001.SZ", DATE, DATE, LOGICAL, Mode.RECONCILE);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(List.of(0, 1000), offsets);
        assertEquals(1, target.sends);
        assertEquals(1, target.rows.size());
        var row = target.rows.values().iterator().next();
        var prices = (List<Double>) row.get("price");
        var percentages = (List<Double>) row.get("percent");
        assertEquals(1007, prices.size());
        assertEquals(prices.size(), percentages.size());
        for (int i = 0; i < prices.size(); i++) {
            assertEquals(i + 1d, prices.get(i));
            assertEquals(i == 1006 ? 49.7d : 0.05d, percentages.get(i));
        }
        assertThrows(UnsupportedOperationException.class, () -> prices.add(99d));
        raw.get(1006).put("percent", new DoubleNode(0.06d));
        raw.get(1005).put("percent", new DoubleNode(0.04d));
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        assertEquals(1, target.rows.size());
        percentages = (List<Double>) target.rows.values().iterator().next().get("percent");
        assertEquals(0.06d, percentages.get(0));
        assertEquals(0.04d, percentages.get(1));
    }

    @Test
    void aLaterPageFailureCannotPublishAPrefixAndResumeUsesTheOriginalRange() throws Exception {
        populate();
        failedOffset = 1000;
        var owner = owner();
        var plan = owner.plan("000001.SZ", DATE, DATE, LOGICAL, Mode.BACKFILL);
        var failed = owner.run(plan);
        assertNotEquals(SyncRunState.VERIFIED, failed.state());
        assertEquals(0, target.sends);
        failedOffset = -1;
        assertEquals(SyncRunState.VERIFIED, owner.resume(failed.runId()).state());
        assertEquals(1, target.sends);
    }

    @Test
    void repeatedPagesAndAnIncompleteDistributionFailBeforeAnySend() throws Exception {
        populate();
        repeat = true;
        var owner = owner();
        assertNotEquals(
                SyncRunState.VERIFIED,
                owner.run(owner.plan("000001.SZ", DATE, DATE, LOGICAL, Mode.RECONCILE)).state());
        assertEquals(0, target.sends);
        repeat = false;
        raw.subList(7, raw.size()).clear();
        assertNotEquals(
                SyncRunState.VERIFIED,
                owner.run(owner.plan("000001.SZ", DATE, DATE, LOGICAL, Mode.RECONCILE)).state());
        assertEquals(0, target.sends);
    }

    @Test
    void emptySourceCannotEraseAnExistingArrayOrBeSignedComplete() throws Exception {
        populate();
        var owner = owner();
        var plan = owner.plan("000001.SZ", DATE, DATE, LOGICAL, Mode.RECONCILE);
        assertEquals(SyncRunState.VERIFIED, owner.run(plan).state());
        var before = Map.copyOf(target.rows);
        raw.clear();
        var failed = owner.run(plan);
        assertNotEquals(SyncRunState.VERIFIED_EMPTY, failed.state());
        assertEquals(before, target.rows);
        assertEquals(1, target.sends);
    }

    final class Target implements ContractTableTarget {
        final Map<String, Map<String, Object>> rows = new HashMap<>();
        int sends;

        public String targetId() {
            return "original";
        }

        public void preflight() {}

        public boolean walSettled() {
            return true;
        }

        public void send(List<Map<String, Object>> values) {
            assertTrue(offsets.contains(1000));
            sends++;
            var codec = VerifiedMarketDataStore.codec(ContractTableDefinition.from("cyq_chips"));
            values.forEach(row -> rows.put(codec.key(row), row));
        }

        public List<Map<String, Object>> readback(List<String> keys) {
            return keys.stream().filter(rows::containsKey).map(rows::get).toList();
        }

        public List<Map<String, Object>> readScope(String code, LocalDate date) {
            return List.copyOf(rows.values());
        }
    }
}
