package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.market.us.marketdata.TiingoPriceMapper;
import com.zoutrankil.market.us.marketdata.UsSecurityIdentifiers;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class UsSecurityIdentifierImporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    @Test
    void anExistingMappingSnapshotCanBeRepeatedButCannotBeOverwrittenWithDifferentValues() {
        var expected = new LinkedHashMap<String, Object>();
        for (var column : UsSecurityIdentifiers.IDENTIFIERS.columns())
            expected.put(
                    column.storageName(),
                    switch (column.storageType()) {
                        case TIMESTAMP -> Instant.EPOCH.toString();
                        case LONG -> 11844L;
                        case BOOLEAN -> false;
                        default -> "original";
                    });
        assertDoesNotThrow(
                () ->
                        UsSecurityIdentifierImporter.verifyExistingSnapshot(
                                expected, List.of(expected)));
        var changed = new LinkedHashMap<>(expected);
        changed.put("security_name", "another issuer");
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyExistingSnapshot(
                                expected, List.of(changed)));
        changed.put("security_name", expected.get("security_name"));
        changed.put("openfigi_composite", null);
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyExistingSnapshot(
                                expected, List.of(changed)));
    }

    @Test
    void matchingTickerCannotSubstituteTheOtherIssuersPermanentIdentity() throws Exception {
        var search =
                JSON.readTree(
                        "[{\"permaTicker\":\"US000000080416\",\"ticker\":\"AMTD\",\"name\":\"AMTD IDEA Group\",\"assetType\":\"Stock\",\"openFIGIComposite\":\"BBG00PH297Y9\",\"isActive\":true}]");
        var record =
                Map.<String, Object>of(
                        "security_id",
                        "US000000000721",
                        "source_ticker",
                        "AMTD",
                        "security_name",
                        "TD Ameritrade Holding Corp",
                        "asset_type",
                        "Stock",
                        "openfigi_composite",
                        "BBG000BMFPK4",
                        "is_active",
                        false);
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
    }

    @Test
    void explicitNullFigiStillRequiresTheExactPermanentIssuerAndRejectsMissingOrTextNull()
            throws Exception {
        var search =
                JSON.readTree(
                        "[{\"permaTicker\":\"US000000112506\",\"ticker\":\"ATTT\",\"name\":\"Atlas Trinity Tech Ltd\",\"assetType\":\"Stock\",\"openFIGIComposite\":null,\"isActive\":true}]");
        var record =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "security_id",
                                "US000000112506",
                                "source_ticker",
                                "ATTT",
                                "security_name",
                                "Atlas Trinity Tech Ltd",
                                "asset_type",
                                "Stock",
                                "is_active",
                                true));
        record.put("openfigi_composite", null);
        assertDoesNotThrow(() -> UsSecurityIdentifierImporter.verifySearch(search, record));
        record.put("security_name", "another issuer");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
        record.put("security_name", "Atlas Trinity Tech Ltd");
        var item = (ObjectNode) search.get(0);
        item.remove("openFIGIComposite");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
        item.put("openFIGIComposite", "null");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
        item.put("openFIGIComposite", 123);
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
        item.putNull("openFIGIComposite");
        record.put("openfigi_composite", "null");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifySearch(search, record));
    }

    @Test
    void currentSameTickerHistoryCannotStandInForOlderExchangeAndWindow() {
        var current =
                Map.<String, Object>of(
                        "symbol",
                        "AMTD",
                        "exchange",
                        "NYSE",
                        "asset_type",
                        "Stock",
                        "source_sha256",
                        SHA,
                        "source_start_date",
                        "2019-08-05T00:00:00Z",
                        "source_end_date",
                        "2026-10-16T00:00:00Z");
        var old =
                Map.<String, Object>of(
                        "source_ticker",
                        "AMTD",
                        "catalog_exchange",
                        "NASDAQ",
                        "asset_type",
                        "Stock",
                        "history_source_sha256",
                        SHA,
                        "price_from",
                        "2006-01-03T00:00:00Z",
                        "price_through",
                        "2020-10-05T00:00:00Z");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifyHistory(current, old));
    }

    @Test
    void aNonUsdCatalogCannotCertifyPricesThatWereNormalizedAsUsd() {
        var history =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "symbol",
                                "AMTD",
                                "exchange",
                                "NASDAQ",
                                "asset_type",
                                "Stock",
                                "currency",
                                "EUR",
                                "source_sha256",
                                SHA,
                                "source_start_date",
                                "1997-03-04T00:00:00Z",
                                "source_end_date",
                                "2020-10-07T00:00:00Z"));
        var record =
                Map.<String, Object>of(
                        "source_ticker",
                        "AMTD",
                        "catalog_exchange",
                        "NASDAQ",
                        "asset_type",
                        "Stock",
                        "history_source_sha256",
                        SHA,
                        "price_from",
                        "2006-01-03T00:00:00Z",
                        "price_through",
                        "2020-10-05T00:00:00Z");
        assertThrows(
                IllegalStateException.class,
                () -> UsSecurityIdentifierImporter.verifyHistory(history, record));
        history.put("currency", "USD");
        assertDoesNotThrow(() -> UsSecurityIdentifierImporter.verifyHistory(history, record));
    }

    @Test
    void unresolvedRunChangedPhysicalObjectAndWrongValuesCannotProducePriceMapping()
            throws Exception {
        String raw =
                "[{\"date\":\"2020-10-05T00:00:00Z\",\"open\":39.1,\"high\":40,\"low\":39,\"close\":39.97,\"volume\":10,\"adjOpen\":39.1,\"adjHigh\":40,\"adjLow\":39,\"adjClose\":39.97,\"adjVolume\":10,\"divCash\":0,\"splitFactor\":1}]";
        var prices =
                TiingoPriceMapper.daily(
                        raw.getBytes(StandardCharsets.UTF_8),
                        "US000000000721",
                        SHA,
                        Instant.EPOCH,
                        LocalDate.of(2020, 10, 5));
        var prepared =
                JSON.valueToTree(
                        Map.of(
                                "definition",
                                Map.of("datasetId", "us_equity_daily"),
                                "sourceSha256",
                                SHA,
                                "rows",
                                prices));
        var record =
                Map.<String, Object>of(
                        "provider",
                        "TIINGO",
                        "price_symbol",
                        "US000000000721",
                        "price_source_sha256",
                        SHA,
                        "price_from",
                        "2020-10-05T00:00:00Z",
                        "price_through",
                        "2020-10-05T00:00:00Z");
        assertDoesNotThrow(
                () ->
                        UsSecurityIdentifierImporter.verifyPriceProof(
                                prepared,
                                record,
                                SyncRunState.VERIFIED,
                                "original",
                                "original",
                                prices));
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyPriceProof(
                                prepared,
                                record,
                                SyncRunState.IN_DOUBT,
                                "original",
                                "original",
                                prices));
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyPriceProof(
                                prepared,
                                record,
                                SyncRunState.VERIFIED,
                                "original",
                                "recreated",
                                prices));
        var changed = new LinkedHashMap<>(prices.getFirst());
        changed.put("close", 40.0);
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyPriceProof(
                                prepared,
                                record,
                                SyncRunState.VERIFIED,
                                "original",
                                "original",
                                List.of(changed)));
        assertThrows(
                IllegalStateException.class,
                () ->
                        UsSecurityIdentifierImporter.verifyPriceProof(
                                prepared,
                                record,
                                SyncRunState.VERIFIED,
                                "original",
                                "original",
                                List.of()));
    }
}
