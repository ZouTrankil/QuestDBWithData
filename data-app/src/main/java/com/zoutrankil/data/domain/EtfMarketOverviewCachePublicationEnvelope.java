package com.zoutrankil.data.domain;

import java.time.LocalDate;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** One frozen original-owner publication, including a known day whose INNER JOIN is empty. */
public record EtfMarketOverviewCachePublicationEnvelope(
        LocalDate tradeDate, String sourceVersion, EtfMarketOverviewDailyCache cache,
        MarketBarometerCacheCoverage receipt, Map<String,JsonNode> sources, Map<String,JsonNode> targets,
        String sourcesFingerprint, String targetsFingerprint, String targetId, String sourceFingerprint,
        long sourceRows, boolean knownSourceDate, Path previewPath, String previewSha256,
        String responseEvidence, JsonNode previewResponse) {
    public static final String DATASET = "etf_market_overview_daily";
    public static final String CACHE = "etf_market_overview_daily_cache";
    public static final String COVERAGE = "market_barometer_cache_coverage";
    public static final List<String> SOURCE_TABLES = List.of("etf_share", "etf_daily", "etf_basic");
    public static final List<String> TARGET_TABLES = List.of(CACHE, COVERAGE);
    public EtfMarketOverviewCachePublicationEnvelope {
        Objects.requireNonNull(tradeDate); EtfMarketOverviewDailyCacheKey.requireVersion(sourceVersion);
        sources = immutable(sources); targets = immutable(targets);
        if (!sources.keySet().equals(new HashSet<>(SOURCE_TABLES)) || !targets.keySet().equals(new HashSet<>(TARGET_TABLES)))
            throw new IllegalArgumentException("Exact three source and two target snapshots required");
        for (String digest : List.of(sourcesFingerprint, targetsFingerprint, sourceFingerprint, previewSha256))
            EtfMarketOverviewDailyCacheKey.requireVersion(digest);
        if (targetId == null || !targetId.matches("questdb-[0-9a-f]{64}"))
            throw new IllegalArgumentException("Original private target identity required");
        if (sourceRows < 0 || sourceRows > 50000) throw new IllegalArgumentException("Finite source row census required");
        Objects.requireNonNull(previewPath); Objects.requireNonNull(responseEvidence);
        previewResponse = Objects.requireNonNull(previewResponse).deepCopy();
        if (!knownSourceDate && (cache != null || receipt != null))
            throw new IllegalArgumentException("Absent share source day cannot invent a publication or receipt");
        if (knownSourceDate) {
            Objects.requireNonNull(receipt, "Known source day needs an exact expected receipt");
            if (!receipt.tradeDate().equals(tradeDate) || !receipt.sourceVersion().equals(sourceVersion)
                    || !DATASET.equals(receipt.datasetId()) || receipt.rowCount() != (cache == null ? 0 : 1))
                throw new IllegalArgumentException("Receipt must bind complete publication key and actual cache cardinality");
        }
        if (cache != null && !cache.key().equals(new EtfMarketOverviewDailyCacheKey(tradeDate, sourceVersion)))
            throw new IllegalArgumentException("Cache row differs from complete publication key");
    }
    private static Map<String,JsonNode> immutable(Map<String,JsonNode> input) {
        Objects.requireNonNull(input); var result = new LinkedHashMap<String,JsonNode>();
        input.forEach((key,value) -> result.put(key, Objects.requireNonNull(value).deepCopy()));
        return Collections.unmodifiableMap(result);
    }
    @Override public Map<String,JsonNode> sources() { return immutable(sources); }
    @Override public Map<String,JsonNode> targets() { return immutable(targets); }
    @Override public JsonNode previewResponse() { return previewResponse.deepCopy(); }
    public EtfMarketOverviewDailyCacheKey key() { return new EtfMarketOverviewDailyCacheKey(tradeDate, sourceVersion); }
    public String physicalVersion() { return sourcesFingerprint; }
    public EtfMarketOverviewCachePublicationEnvelope withActual(EtfMarketOverviewDailyCache actualCache,
                                                               MarketBarometerCacheCoverage actualReceipt) {
        return new EtfMarketOverviewCachePublicationEnvelope(tradeDate,sourceVersion,actualCache,actualReceipt,
                sources,targets,sourcesFingerprint,targetsFingerprint,targetId,sourceFingerprint,sourceRows,
                knownSourceDate,previewPath,previewSha256,responseEvidence,previewResponse);
    }
}
