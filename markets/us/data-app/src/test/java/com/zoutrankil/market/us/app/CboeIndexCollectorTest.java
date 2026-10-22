package com.zoutrankil.market.us.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zoutrankil.data.marketdata.VerifiedMarketDataStore;
import com.zoutrankil.market.us.marketdata.CboeIndexDaily;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class CboeIndexCollectorTest {
    @TempDir Path root;

    @Test
    void blocksUnfinishedIntentEvenWhenNextSourceHasDifferentIdentity() throws Exception {
        var old = Files.createDirectories(root.resolve("cboe-index-publications/old-source"));
        Files.writeString(
                old.resolve("intent.json"), "{\"sourceSha256\":\"old\",\"missingRows\":2}");
        assertThrows(
                IllegalStateException.class, () -> CboeIndexCollector.requireNoPendingIntent(root));
        Files.writeString(
                old.resolve("receipt.json"),
                "{\"result\":{\"state\":\"VERIFIED\",\"verifiedRows\":2},\"sourceSha256\":\"old\",\"newRows\":2,\"oldRowsUnchanged\":true,\"walSettled\":true}");
        CboeIndexCollector.requireNoPendingIntent(root);
    }

    @Test
    void rejectsIncompleteSuccessReceipt() throws Exception {
        var old = Files.createDirectories(root.resolve("cboe-index-publications/old-source"));
        Files.writeString(
                old.resolve("intent.json"), "{\"sourceSha256\":\"old\",\"missingRows\":2}");
        Files.writeString(
                old.resolve("receipt.json"), "{\"result\":{\"state\":\"VERIFIED\"},\"newRows\":2}");
        assertThrows(
                IllegalStateException.class, () -> CboeIndexCollector.requireNoPendingIntent(root));
    }

    private static List<Map<String, Object>> source() {
        return CboeIndexDaily.parse(
                        ("DATE,OPEN,HIGH,LOW,CLOSE\n01/02/2026,1,2,1,12.83\n01/06/2026,1,2,1,15\n")
                                .getBytes(StandardCharsets.UTF_8),
                        "a".repeat(64),
                        Instant.parse("2026-01-07T05:00:00Z"),
                        LocalDate.of(2026, 1, 2),
                        LocalDate.of(2026, 1, 6))
                .rows()
                .stream()
                .map(row -> VerifiedMarketDataStore.normalize(CboeIndexDaily.DEFINITION, row))
                .toList();
    }

    @Test
    void fillsOnlyAbsentKeysAndKeepsOriginalObservationLineage() {
        var source = source();
        var old = new LinkedHashMap<>(source.getFirst());
        old.put("source_sha256", "b".repeat(64));
        old.put("observed_at", "2026-01-06T05:00:00.000000Z");
        assertEquals(
                List.of(source.getLast()), CboeIndexCollector.missingRows(source, List.of(old)));
        assertEquals("b".repeat(64), old.get("source_sha256"));
    }

    @Test
    void rejectsRevisionsDisappearedDatesAndDuplicateKeys() {
        var source = source();
        var revised = new LinkedHashMap<>(source.getFirst());
        revised.put("close", 12.84);
        assertThrows(
                IllegalStateException.class,
                () -> CboeIndexCollector.missingRows(source, List.of(revised)));
        assertThrows(
                IllegalStateException.class,
                () -> CboeIndexCollector.missingRows(List.of(source.getLast()), source));
        assertThrows(
                IllegalStateException.class,
                () ->
                        CboeIndexCollector.missingRows(
                                source, List.of(source.getFirst(), source.getFirst())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        CboeIndexCollector.missingRows(
                                List.of(source.getFirst(), source.getFirst()), List.of()));
    }

    @Test
    void usesNewYorkClosedDateRatherThanUtcDate() {
        var clock = Clock.fixed(Instant.parse("2026-01-07T02:00:00Z"), ZoneOffset.UTC);
        CboeIndexCollector.requireClosed(LocalDate.of(2026, 1, 5), clock);
        assertThrows(
                IllegalArgumentException.class,
                () -> CboeIndexCollector.requireClosed(LocalDate.of(2026, 1, 6), clock));
    }

    @Test
    void rejectsCredentialAndNonOfficialSourceAddresses() {
        for (String url :
                List.of(
                        "https://other.example/api/global/us_indices/daily_prices/VIX_History.csv",
                        "https://key@cdn-api.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv",
                        "https://cdn-api.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv?key=example")) {
            var env = new StandardEnvironment();
            env.getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "test", Map.of("app.market-data.cboe-vix-url", url)));
            assertThrows(IllegalArgumentException.class, () -> CboeIndexCollector.sourceUri(env));
        }
    }
}
