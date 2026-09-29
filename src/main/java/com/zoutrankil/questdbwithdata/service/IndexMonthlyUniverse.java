package com.zoutrankil.questdbwithdata.service;

import java.util.*;

/** Exact monthly_enabled=True Python research pool and its historical index_monthly provider aliases. */
public final class IndexMonthlyUniverse {
    private IndexMonthlyUniverse() {}
    public record Index(String providerCode, String canonicalCode, String layer, String bucket) {
        public Index {
            Objects.requireNonNull(providerCode); Objects.requireNonNull(canonicalCode);
            if (layer == null || layer.isBlank() || bucket == null || bucket.isBlank())
                throw new IllegalArgumentException("Frozen monthly layer and bucket required");
        }
    }
    private static final String[] DEFINITIONS = {
            "000300.SH|macro_core|broad_base", "000905.SH|macro_core|broad_base", "000985.CSI|macro_core|broad_base",
            "000510.SH|macro_core|broad_base", "000047.SH|macro_core|broad_base", "000906.SH|macro_core|broad_base_plus",
            "000852.SH|macro_core|broad_base_plus", "932391.CSI|macro_core|broad_base_plus",
            "000986.SH|industry_macro_map|all_a_industry", "000987.SH|industry_macro_map|all_a_industry",
            "000988.CSI|industry_macro_map|all_a_industry", "000989.SH|industry_macro_map|all_a_industry",
            "000990.CSI|industry_macro_map|all_a_industry", "000991.SH|industry_macro_map|all_a_industry",
            "000992.SH|industry_macro_map|all_a_industry", "000993.SH|industry_macro_map|all_a_industry",
            "000994.CSI|industry_macro_map|all_a_industry", "000995.CSI|industry_macro_map|all_a_industry",
            "931775.CSI|industry_macro_map|macro_sensitive", "931931.CSI|industry_macro_map|macro_sensitive",
            "931932.CSI|industry_macro_map|macro_sensitive", "931935.CSI|industry_macro_map|macro_sensitive",
            "931936.CSI|industry_macro_map|macro_sensitive", "930910.CSI|industry_macro_map|macro_sensitive",
            "930697.CSI|industry_macro_map|macro_sensitive", "931528.CSI|industry_macro_map|macro_sensitive",
            "399975.SZ|industry_macro_map|macro_sensitive",
            "000918.CSI|style_proxy|classic", "000919.CSI|style_proxy|classic", "000920.CSI|style_proxy|classic",
            "000921.CSI|style_proxy|classic", "000821.CSI|style_proxy|classic", "000822.CSI|style_proxy|classic",
            "000828.CSI|style_proxy|classic", "000829.CSI|style_proxy|classic", "000830.CSI|style_proxy|classic",
            "000831.CSI|style_proxy|classic", "000982.SH|style_proxy|classic", "000984.CSI|style_proxy|classic",
            "930740.CSI|style_proxy|low_vol_quality", "930782.CSI|style_proxy|low_vol_quality",
            "930846.CSI|style_proxy|low_vol_quality", "930985.CSI|style_proxy|low_vol_quality",
            "931155.CSI|style_proxy|low_vol_quality", "931375.CSI|style_proxy|low_vol_quality",
            "931847.CSI|style_proxy|low_vol_quality", "932430.CSI|style_proxy|low_vol_quality",
            "932431.CSI|style_proxy|low_vol_quality", "932432.CSI|style_proxy|low_vol_quality",
            "932400.CSI|style_proxy|pure_style", "932401.CSI|style_proxy|pure_style",
            "932402.CSI|style_proxy|pure_style", "932403.CSI|style_proxy|pure_style",
            "932494.CSI|style_proxy|pure_style", "932497.CSI|style_proxy|pure_style"
    };
    public static final List<Index> MONTHLY = build();
    private static final Map<String,Index> BY_PROVIDER;
    private static final Map<String,Index> BY_CANONICAL;
    static {
        var providers = new LinkedHashMap<String,Index>(); var canonicals = new LinkedHashMap<String,Index>();
        for (var index : MONTHLY) {
            if (providers.putIfAbsent(index.providerCode(), index) != null
                    || canonicals.putIfAbsent(index.canonicalCode(), index) != null)
                throw new ExceptionInInitializerError("Duplicate D022 monthly universe identity");
        }
        BY_PROVIDER = Collections.unmodifiableMap(providers); BY_CANONICAL = Collections.unmodifiableMap(canonicals);
    }
    private static List<Index> build() {
        var result = new ArrayList<Index>(DEFINITIONS.length);
        for (String row : DEFINITIONS) {
            String[] fields = row.split("\\|", -1);
            String canonical = fields[0], suffix = canonical.substring(canonical.lastIndexOf('.') + 1);
            String raw = canonical.substring(0, canonical.lastIndexOf('.'));
            String provider = raw + (suffix.equals("SZ") ? ".SZ" : ".SH");
            result.add(new Index(provider, canonical, fields[1], fields[2]));
        }
        if (result.size() != 55) throw new ExceptionInInitializerError("D022 monthly-enabled universe must contain exactly 55 entries");
        return List.copyOf(result);
    }
    public static Index resolveProvider(String code) { return code == null ? null : BY_PROVIDER.get(code.strip().toUpperCase(Locale.ROOT)); }
    public static Index resolveCanonical(String code) { return code == null ? null : BY_CANONICAL.get(code.strip().toUpperCase(Locale.ROOT)); }
    public static boolean validProviderCode(String code) { return resolveProvider(code) != null; }
    public static List<String> providerCodes() { return MONTHLY.stream().map(Index::providerCode).toList(); }
    public static String providerCode(String canonicalCode) {
        var value = resolveCanonical(canonicalCode);
        if (value == null) throw new IllegalArgumentException("D022 code must belong to the frozen monthly-enabled Python universe");
        return value.providerCode();
    }
}
