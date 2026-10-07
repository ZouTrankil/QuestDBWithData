package com.zoutrankil.data.domain.policy;

import com.zoutrankil.data.mapper.IndexWeightMapper;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class IndexUniversePolicyTest {
    private static final List<String> CORE57 = codes("""
            000300.SH 000905.SH 000985.CSI 000510.SH 000047.SH 000906.SH 000852.SH
            932391.CSI 000016.SH 399006.SZ 000986.SH 000987.SH 000988.CSI 000989.SH
            000990.CSI 000991.SH 000992.SH 000993.SH 000994.CSI 000995.CSI 931775.CSI
            931931.CSI 931932.CSI 931935.CSI 931936.CSI 930910.CSI 930697.CSI 931528.CSI
            399975.SZ 000918.CSI 000919.CSI 000920.CSI 000921.CSI 000821.CSI 000822.CSI
            000828.CSI 000829.CSI 000830.CSI 000831.CSI 000982.SH 000984.CSI 930740.CSI
            930782.CSI 930846.CSI 930985.CSI 931155.CSI 931375.CSI 931847.CSI 932430.CSI
            932431.CSI 932432.CSI 932400.CSI 932401.CSI 932402.CSI 932403.CSI 932494.CSI 932497.CSI
            """);
    private static final List<String> SW31 = codes("""
            801010.SI 801030.SI 801040.SI 801050.SI 801080.SI 801880.SI 801110.SI
            801120.SI 801130.SI 801140.SI 801150.SI 801160.SI 801170.SI 801180.SI
            801200.SI 801210.SI 801780.SI 801790.SI 801230.SI 801710.SI 801720.SI
            801730.SI 801890.SI 801740.SI 801750.SI 801760.SI 801770.SI 801950.SI
            801960.SI 801970.SI 801980.SI
            """);

    @Test void dailyBasicKeepsItsFiveCodesInFrozenOrderAndExactSuffixes() {
        assertEquals(List.of("000300.SH", "000016.SH", "399006.SZ", "000905.SH", "000852.SH"),
                IndexDailyBasicUniverse.CORE_INDICES);
        for (String code : IndexDailyBasicUniverse.CORE_INDICES) {
            assertEquals(code, IndexDailyBasicUniverse.resolve(" " + code.toLowerCase(Locale.ROOT) + " "));
            assertTrue(IndexDailyBasicUniverse.valid(code));
        }
        for (String code : new String[] {null, "", "000300", "000300.CSI", "000688.SH"}) {
            assertNull(IndexDailyBasicUniverse.resolve(code));
            assertFalse(IndexDailyBasicUniverse.valid(code));
        }
    }

    @Test void dailyMarketKeepsAllOrderedPoolsAndTheirEndpointRoutes() {
        assertEquals(57, CORE57.size());
        assertEquals(31, SW31.size());
        assertEquals(CORE57, IndexDailyMarketUniverse.CORE57);
        assertEquals(SW31, IndexDailyMarketUniverse.SW2021_L1_31);
        assertEquals(CORE57, IndexDailyMarketUniverse.codes(IndexDailyMarketUniverse.Scope.CORE57));
        assertEquals(SW31, IndexDailyMarketUniverse.codes(IndexDailyMarketUniverse.Scope.SW2021_L1_31));
        var all = new ArrayList<>(CORE57);
        all.addAll(SW31);
        assertEquals(all, IndexDailyMarketUniverse.allCodes());
        assertEquals(88, IndexDailyMarketUniverse.allCodes().stream().distinct().count());
        for (String code : CORE57) {
            assertEquals(new IndexDailyMarketUniverse.Index(code, IndexDailyMarketUniverse.Route.INDEX_DAILY,
                    IndexDailyMarketUniverse.Scope.CORE57), IndexDailyMarketUniverse.resolve(code));
        }
        for (String code : SW31) {
            assertEquals(new IndexDailyMarketUniverse.Index(code, IndexDailyMarketUniverse.Route.SW_DAILY,
                    IndexDailyMarketUniverse.Scope.SW2021_L1_31), IndexDailyMarketUniverse.resolve(code));
        }
        assertEquals("index_daily", IndexDailyMarketUniverse.endpoint(IndexDailyMarketUniverse.Route.INDEX_DAILY));
        assertEquals("sw_daily", IndexDailyMarketUniverse.endpoint(IndexDailyMarketUniverse.Route.SW_DAILY));
    }

    @Test void dailyMarketResolvesCaseAndWhitespaceWithoutAddingAliases() {
        assertEquals(IndexDailyMarketUniverse.resolve("000985.CSI"), IndexDailyMarketUniverse.resolve(" 000985.csi "));
        assertEquals(IndexDailyMarketUniverse.resolve("801980.SI"), IndexDailyMarketUniverse.resolve(" 801980.si "));
        for (String code : new String[] {null, "", "000985.SH", "801010.SH", "000300", "000688.SH"}) {
            assertNull(IndexDailyMarketUniverse.resolve(code));
            assertFalse(IndexDailyMarketUniverse.valid(code));
        }
        assertThrows(NullPointerException.class, () -> IndexDailyMarketUniverse.codes(null));
        assertThrows(NullPointerException.class, () -> IndexDailyMarketUniverse.endpoint(null));
    }

    @Test void monthlyKeepsItsOrdered55CodesAndEveryLayerBucketBoundary() {
        List<String> expected = CORE57.stream().filter(code -> !code.equals("000016.SH") && !code.equals("399006.SZ")).toList();
        assertEquals(55, expected.size());
        assertEquals(expected, IndexMonthlyUniverse.MONTHLY.stream().map(IndexMonthlyUniverse.Index::canonicalCode).toList());
        assertMonthlyCategory(0, 5, "macro_core", "broad_base");
        assertMonthlyCategory(5, 8, "macro_core", "broad_base_plus");
        assertMonthlyCategory(8, 18, "industry_macro_map", "all_a_industry");
        assertMonthlyCategory(18, 27, "industry_macro_map", "macro_sensitive");
        assertMonthlyCategory(27, 39, "style_proxy", "classic");
        assertMonthlyCategory(39, 49, "style_proxy", "low_vol_quality");
        assertMonthlyCategory(49, 55, "style_proxy", "pure_style");
    }

    @Test void monthlyPreservesProviderAliasesAndRejectsCodesOutsideItsPool() {
        var expectedProviders = new ArrayList<String>();
        for (var index : IndexMonthlyUniverse.MONTHLY) {
            String provider = index.canonicalCode().replace(".CSI", ".SH");
            expectedProviders.add(provider);
            assertEquals(provider, index.providerCode());
            assertEquals(provider, IndexMonthlyUniverse.providerCode(" " + index.canonicalCode().toLowerCase(Locale.ROOT) + " "));
            assertEquals(index, IndexMonthlyUniverse.resolveCanonical(index.canonicalCode()));
            assertEquals(index, IndexMonthlyUniverse.resolveProvider(" " + provider.toLowerCase(Locale.ROOT) + " "));
            assertTrue(IndexMonthlyUniverse.validProviderCode(provider));
        }
        assertEquals(expectedProviders, IndexMonthlyUniverse.providerCodes());
        assertEquals(55, expectedProviders.stream().distinct().count());
        assertNull(IndexMonthlyUniverse.resolveProvider("000985.CSI"));
        assertNull(IndexMonthlyUniverse.resolveCanonical("000985.SH"));
        for (String code : new String[] {null, "", "000016.SH", "399006.SZ", "801010.SI"}) {
            assertNull(IndexMonthlyUniverse.resolveProvider(code));
            assertNull(IndexMonthlyUniverse.resolveCanonical(code));
            assertFalse(IndexMonthlyUniverse.validProviderCode(code));
            assertEquals("D022 code must belong to the frozen monthly-enabled Python universe",
                    assertThrows(IllegalArgumentException.class, () -> IndexMonthlyUniverse.providerCode(code)).getMessage());
        }
    }

    @Test void policyRecordsKeepTheirExistingValidation() {
        assertThrows(NullPointerException.class, () -> new IndexDailyMarketUniverse.Index(null,
                IndexDailyMarketUniverse.Route.INDEX_DAILY, IndexDailyMarketUniverse.Scope.CORE57));
        assertThrows(NullPointerException.class, () -> new IndexDailyMarketUniverse.Index("000300.SH", null,
                IndexDailyMarketUniverse.Scope.CORE57));
        assertThrows(NullPointerException.class, () -> new IndexDailyMarketUniverse.Index("000300.SH",
                IndexDailyMarketUniverse.Route.INDEX_DAILY, null));
        assertThrows(NullPointerException.class, () -> new IndexMonthlyUniverse.Index(null, "000300.SH", "layer", "bucket"));
        assertThrows(NullPointerException.class, () -> new IndexMonthlyUniverse.Index("000300.SH", null, "layer", "bucket"));
        for (String missing : new String[] {null, "", " "}) {
            assertEquals("Frozen monthly layer and bucket required", assertThrows(IllegalArgumentException.class,
                    () -> new IndexMonthlyUniverse.Index("000300.SH", "000300.SH", missing, "bucket")).getMessage());
            assertEquals("Frozen monthly layer and bucket required", assertThrows(IllegalArgumentException.class,
                    () -> new IndexMonthlyUniverse.Index("000300.SH", "000300.SH", "layer", missing)).getMessage());
        }
    }

    @Test void weightKeepsEightOrderedProviderRoutesAndMemberCounts() {
        var expected = List.of(
                new IndexWeightUniverse.Index("000300", "000300.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 300),
                new IndexWeightUniverse.Index("000016", "000016.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 50),
                new IndexWeightUniverse.Index("000688", "000688.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 50),
                new IndexWeightUniverse.Index("000905", "000905.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 500),
                new IndexWeightUniverse.Index("000510", "000510.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 500),
                new IndexWeightUniverse.Index("000852", "000852.SH", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 1000),
                new IndexWeightUniverse.Index("932000", "932000.CSI", IndexWeightUniverse.Route.CSINDEX_OSS_XLS, 2000),
                new IndexWeightUniverse.Index("399673", "399673.SZ", IndexWeightUniverse.Route.TUSHARE_INDEX_WEIGHT, 50));
        assertEquals(expected, IndexWeightUniverse.INDEXES);
        for (var index : expected) {
            assertEquals(index, IndexWeightUniverse.resolve(index.code()));
            assertEquals(index, IndexWeightUniverse.resolve(" " + index.tushareCode().toLowerCase(Locale.ROOT) + " "));
        }
        assertEquals(expected.getFirst(), IndexWeightUniverse.resolve("300"));
        assertEquals(expected.getFirst(), IndexWeightUniverse.resolve("300.any.suffix"));
        assertNull(IndexWeightUniverse.resolve("000000"));
        assertNull(IndexWeightUniverse.resolve("999999"));
    }

    @Test void weightNormalizationAndMapperDelegateKeepPaddingSuffixAndErrorSemantics() {
        var examples = Map.of("0", "000000", "1", "000001", " 300.sh ", "000300", "300.", "000300",
                "300.any.suffix", "000300", "399673.SZ", "399673", "999999", "999999");
        examples.forEach((input, expected) -> {
            assertEquals(expected, IndexWeightUniverse.normalizeIndexCode(input));
            assertEquals(expected, IndexWeightMapper.normalizeIndexCode(input));
        });
        for (String invalid : new String[] {null, "", " ", ".SH", "1234567", "-1", "+1", "3 00", "ABC", "300 .SH"}) {
            String message = invalid == null ? "index_code required" : "Invalid six digit index_code: " + invalid;
            assertEquals(message, assertThrows(IllegalArgumentException.class,
                    () -> IndexWeightUniverse.normalizeIndexCode(invalid)).getMessage());
            assertEquals(message, assertThrows(IllegalArgumentException.class,
                    () -> IndexWeightMapper.normalizeIndexCode(invalid)).getMessage());
            assertNull(IndexWeightUniverse.resolve(invalid));
        }
    }

    @Test void exposedUniverseCollectionsRemainImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> IndexDailyBasicUniverse.CORE_INDICES.set(0, "000001.SH"));
        assertThrows(UnsupportedOperationException.class, () -> IndexDailyMarketUniverse.CORE57.clear());
        assertThrows(UnsupportedOperationException.class, () -> IndexDailyMarketUniverse.SW2021_L1_31.clear());
        assertThrows(UnsupportedOperationException.class, () -> IndexDailyMarketUniverse.allCodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> IndexMonthlyUniverse.MONTHLY.clear());
        assertThrows(UnsupportedOperationException.class, () -> IndexMonthlyUniverse.providerCodes().clear());
        assertThrows(UnsupportedOperationException.class, () -> IndexWeightUniverse.INDEXES.clear());
    }

    private static List<String> codes(String text) { return Arrays.asList(text.strip().split("\\s+")); }

    private static void assertMonthlyCategory(int from, int to, String layer, String bucket) {
        for (var index : IndexMonthlyUniverse.MONTHLY.subList(from, to)) {
            assertEquals(layer, index.layer(), index.canonicalCode());
            assertEquals(bucket, index.bucket(), index.canonicalCode());
        }
    }
}
