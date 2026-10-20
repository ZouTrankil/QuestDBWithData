package com.zoutrankil.market.us.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.market.us.marketdata.TiingoPriceMapper;
import com.zoutrankil.market.us.marketdata.UsHistoricalSecurityCatalog;
import com.zoutrankil.market.us.marketdata.UsMarketDatasets;
import com.zoutrankil.market.us.marketdata.UsSecurityIdentifiers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 永久代码映射只引用已经全字段验收的原价格运行，不根据搜索命中或目录日期宣称价格存在。 */
final class UsSecurityIdentifierImporter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private UsSecurityIdentifierImporter() {}

    static Object publish(Path root, VerifiedMarketDataStore store, Path source) throws Exception {
        byte[] bytes = read(source, 1024 * 1024);
        String sha = VerifiedMarketDataStore.sha(bytes);
        Instant observed =
                Files.getLastModifiedTime(source).toInstant().truncatedTo(ChronoUnit.MICROS);
        var rows = UsSecurityIdentifiers.rows(bytes, sha, observed);
        var document = JSON.readTree(bytes);
        var originals = new HashMap<String, byte[]>();
        long originalBytes = 0;
        for (var entry : document.path("sources")) {
            byte[] raw = read(Path.of(entry.path("path").asText()), 64 * 1024 * 1024);
            if (!VerifiedMarketDataStore.sha(raw).equals(entry.path("sha256").asText()))
                throw new IllegalStateException("永久代码核对原件已改变");
            originalBytes += raw.length;
            if (originalBytes > 64L * 1024 * 1024)
                throw new IllegalArgumentException("身份核对的全部原件合计超过 64 MiB");
            originals.put(entry.path("kind").asText() + "|" + entry.path("sha256").asText(), raw);
        }
        var history = new HashMap<String, Map<Long, Map<String, Object>>>();
        var ledger = SyncRunLedger.openExisting(root.resolve("sync-ledger.sqlite3"));
        var physical =
                store.query(
                        "SELECT id,directoryName FROM tables() WHERE table_name='us_equity_daily'");
        if (physical.size() != 1) throw new IllegalStateException("原价格物理表缺失");
        String target =
                "market-"
                        + VerifiedMarketDataStore.sha(JSON.writeValueAsBytes(physical.getFirst()));
        for (var row : rows) {
            verifySearch(
                    JSON.readTree(
                            originals.get("TIINGO_SEARCH|" + row.get("metadata_source_sha256"))),
                    row);
            String historySha = row.get("history_source_sha256").toString();
            if (!history.containsKey(historySha)) {
                var indexed = new HashMap<Long, Map<String, Object>>();
                for (var record :
                        UsHistoricalSecurityCatalog.rows(
                                originals.get("HISTORY_CATALOG|" + historySha),
                                historySha,
                                observed,
                                LocalDate.of(1900, 1, 1),
                                LocalDate.of(2200, 1, 1)))
                    indexed.put(((Number) record.get("source_row")).longValue(), record);
                history.put(historySha, indexed);
            }
            verifyHistory(
                    history.get(historySha)
                            .get(((Number) row.get("history_source_row")).longValue()),
                    row);
            String run = row.get("price_run_id").toString();
            var original = ledger.getRun(run);
            byte[] preparedBytes =
                    read(root.resolve("prepared/" + run + ".json"), 64 * 1024 * 1024);
            var prepared = JSON.readTree(preparedBytes);
            var request = JSON.readTree(original.frozenJson());
            if (!VerifiedMarketDataStore.sha(preparedBytes)
                            .equals(request.path("parameters").path("prepared_sha256").asText())
                    || !request.path("parameters")
                            .path("source_sha256")
                            .asText()
                            .equals(row.get("price_source_sha256")))
                throw new IllegalStateException("原价格运行的冻结摘要不符");
            // 只对原单证券成功运行建立映射；年度组或多个证券不能借部分行冒充这个证券的原运行。
            var expected = priceRows(prepared, row);
            String priceSha = row.get("price_source_sha256").toString();
            byte[] raw = originals.get("PRICE|" + priceSha);
            if (!VerifiedMarketDataStore.sha(
                            read(Path.of(prepared.path("raw").asText()), 64 * 1024 * 1024))
                    .equals(priceSha)) throw new IllegalStateException("原运行价格文件已改变");
            var from = LocalDate.parse(row.get("price_from").toString().substring(0, 10));
            var through = LocalDate.parse(row.get("price_through").toString().substring(0, 10));
            var projected =
                    TiingoPriceMapper.daily(
                            raw,
                            row.get("price_symbol").toString(),
                            priceSha,
                            Instant.parse(expected.getFirst().get("observed_at").toString()),
                            through);
            if (!canonical(expected).equals(canonical(projected)))
                throw new IllegalStateException("原价格运行不是完整原文投影");
            String sql =
                    "SELECT "
                            + String.join(
                                    ",",
                                    UsMarketDatasets.DAILY.storageColumns().stream()
                                            .map(VerifiedMarketDataStore::q)
                                            .toList())
                            + " FROM us_equity_daily WHERE symbol="
                            + VerifiedMarketDataStore.literal(row.get("price_symbol").toString())
                            + " AND provider="
                            + VerifiedMarketDataStore.literal(row.get("provider").toString())
                            + " AND trade_date>="
                            + VerifiedMarketDataStore.literal(from.toString())
                            + " AND trade_date<="
                            + VerifiedMarketDataStore.literal(through.toString())
                            + " ORDER BY trade_date LIMIT 10001";
            verifyPriceProof(
                    prepared,
                    row,
                    ledger.get(run).state(),
                    original.targetId(),
                    target,
                    store.query(sql));
            if (!store.query(sql).stream()
                    .map(r -> VerifiedMarketDataStore.normalize(UsMarketDatasets.DAILY, r))
                    .toList()
                    .equals(canonical(expected))) throw new IllegalStateException("原价格核对期间发生变化");
        }
        if (!physical.equals(
                store.query(
                        "SELECT id,directoryName FROM tables() WHERE table_name='us_equity_daily'")))
            throw new IllegalStateException("核对期间原价格物理表改变");
        var exists =
                store.query(
                        "SELECT table_name FROM tables() WHERE table_name='us_security_identifiers'");
        if (!exists.isEmpty())
            for (var row : rows) {
                String key =
                        "observed_at="
                                + VerifiedMarketDataStore.literal(row.get("observed_at").toString())
                                + " AND provider='TIINGO' AND security_id="
                                + VerifiedMarketDataStore.literal(
                                        row.get("security_id").toString());
                verifyExistingSnapshot(
                        row,
                        store.query(
                                "SELECT * FROM us_security_identifiers WHERE " + key + " LIMIT 2"));
                var older =
                        store.query(
                                "SELECT security_id,price_from,price_through FROM us_security_identifiers WHERE provider='TIINGO' AND price_symbol="
                                        + VerifiedMarketDataStore.literal(
                                                row.get("price_symbol").toString())
                                        + " LIMIT 10001");
                if (older.size() >= 10001) throw new IllegalStateException("已有映射版本超过明确核对边界");
                for (var old : older)
                    if (!old.get("security_id").equals(row.get("security_id"))
                            && Instant.parse(old.get("price_from").toString())
                                            .compareTo(
                                                    Instant.parse(
                                                            row.get("price_through").toString()))
                                    <= 0
                            && Instant.parse(old.get("price_through").toString())
                                            .compareTo(
                                                    Instant.parse(row.get("price_from").toString()))
                                    >= 0) throw new IllegalStateException("不同永久证券在同一价格键上发生重叠");
            }
        return store.publish(
                UsSecurityIdentifiers.IDENTIFIERS,
                rows,
                source,
                sha,
                Map.of(
                        "scope",
                        "原搜索、目录行、原成功价格运行及完整字段回读共同支持的映射；不是全市场证券身份或上市退市完整证明",
                        "verifiedPriceSources",
                        rows.size(),
                        "pricePhysicalIdentity",
                        physical));
    }

    static void verifySearch(JsonNode search, Map<String, Object> row) {
        if (!search.isArray()) throw new IllegalArgumentException("永久代码搜索原文不是数组");
        int found = 0;
        for (var item : search)
            if (item.path("permaTicker").asText().equals(row.get("security_id"))) {
                // 缺字段、文本 "null" 与明确的 JSON null 不能混同；FIGI 为空也不能跳过其他永久身份核对。
                var figi = item.get("openFIGIComposite");
                boolean matchesFigi =
                        figi != null
                                && (figi.isNull()
                                        ? row.get("openfigi_composite") == null
                                        : figi.isTextual()
                                                && figi.asText()
                                                        .equals(row.get("openfigi_composite")));
                found++;
                if (!item.path("ticker").asText().equals(row.get("source_ticker"))
                        || !item.path("name").asText().equals(row.get("security_name"))
                        || !item.path("assetType").asText().equals(row.get("asset_type"))
                        || !matchesFigi
                        || !item.path("isActive").isBoolean()
                        || !Boolean.valueOf(item.path("isActive").booleanValue())
                                .equals(row.get("is_active")))
                    throw new IllegalStateException("原搜索中的发行人、原代码或 FIGI 不符");
            }
        if (found != 1) throw new IllegalStateException("原搜索没有唯一对应永久代码");
    }

    static void verifyHistory(Map<String, Object> original, Map<String, Object> row) {
        if (original == null
                || !"USD".equals(original.get("currency"))
                || !original.get("symbol").equals(row.get("source_ticker"))
                || !original.get("exchange").equals(row.get("catalog_exchange"))
                || !original.get("asset_type").equals(row.get("asset_type"))
                || !original.get("source_sha256").equals(row.get("history_source_sha256"))
                || original.get("source_start_date") == null
                || original.get("source_end_date") == null
                || Instant.parse(original.get("source_start_date").toString())
                        .isAfter(Instant.parse(row.get("price_from").toString()))
                || Instant.parse(original.get("source_end_date").toString())
                        .isBefore(Instant.parse(row.get("price_through").toString())))
            throw new IllegalStateException("原目录行、币种、场所或来源窗口不符");
    }

    static void verifyPriceProof(
            JsonNode prepared,
            Map<String, Object> row,
            SyncRunState state,
            String originalTarget,
            String currentTarget,
            List<Map<String, Object>> actual) {
        if (state != SyncRunState.VERIFIED || !originalTarget.equals(currentTarget))
            throw new IllegalStateException("原价格运行未验收或物理目标改变");
        if (!canonical(priceRows(prepared, row)).equals(canonical(actual)))
            throw new IllegalStateException("原价格完整键和值与正式表不符");
    }

    static void verifyExistingSnapshot(
            Map<String, Object> expected, List<Map<String, Object>> actual) {
        if (actual.isEmpty()) return;
        if (actual.size() != 1
                || !VerifiedMarketDataStore.normalize(UsSecurityIdentifiers.IDENTIFIERS, expected)
                        .equals(
                                VerifiedMarketDataStore.normalize(
                                        UsSecurityIdentifiers.IDENTIFIERS, actual.getFirst())))
            throw new IllegalStateException("原映射快照同键已有不同值，禁止覆盖");
    }

    private static List<Map<String, Object>> priceRows(JsonNode prepared, Map<String, Object> row) {
        if (!prepared.path("definition").path("datasetId").asText().equals("us_equity_daily")
                || !prepared.path("sourceSha256").asText().equals(row.get("price_source_sha256"))
                || !prepared.path("rows").isArray()
                || prepared.path("rows").isEmpty()
                || prepared.path("rows").size() > 10000)
            throw new IllegalStateException("原单证券价格运行范围或摘要不符");
        var result = new ArrayList<Map<String, Object>>();
        for (var value : prepared.path("rows")) {
            Map<String, Object> raw = JSON.convertValue(value, new TypeReference<>() {});
            var price = VerifiedMarketDataStore.normalize(UsMarketDatasets.DAILY, raw);
            if (!price.get("symbol").equals(row.get("price_symbol"))
                    || !price.get("provider").equals(row.get("provider"))
                    || !price.get("source_sha256").equals(row.get("price_source_sha256")))
                throw new IllegalStateException("原价格运行混入其他证券或来源");
            result.add(price);
        }
        var dates = result.stream().map(r -> r.get("trade_date").toString()).sorted().toList();
        if (!dates.getFirst().equals(row.get("price_from"))
                || !dates.getLast().equals(row.get("price_through")))
            throw new IllegalStateException("映射价格窗口不是原运行的完整范围");
        return result;
    }

    private static List<Map<String, Object>> canonical(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(r -> VerifiedMarketDataStore.normalize(UsMarketDatasets.DAILY, r))
                .sorted(Comparator.comparing(r -> r.get("trade_date").toString()))
                .toList();
    }

    private static byte[] read(Path path, long maximum) throws Exception {
        if (Files.size(path) > maximum) throw new IllegalArgumentException("核对原件超过明确读取边界");
        return Files.readAllBytes(path);
    }
}
