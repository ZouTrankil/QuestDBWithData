package com.zoutrankil.data.service;

import java.util.*;

/** Frozen Python index universe used to route endpoint and enforce canonical source identity. */
public final class IndexDailyMarketUniverse {
    private IndexDailyMarketUniverse() {}
    public enum Route { INDEX_DAILY, SW_DAILY }
    public enum Scope { CORE57, SW2021_L1_31 }
    public record Index(String tsCode, Route route, Scope scope) {
        public Index { Objects.requireNonNull(tsCode); Objects.requireNonNull(route); Objects.requireNonNull(scope); }
    }
    public static final List<String> CORE57 = List.of(
            "000300.SH", "000905.SH", "000985.CSI", "000510.SH", "000047.SH", "000906.SH", "000852.SH",
            "932391.CSI", "000016.SH", "399006.SZ", "000986.SH", "000987.SH", "000988.CSI", "000989.SH",
            "000990.CSI", "000991.SH", "000992.SH", "000993.SH", "000994.CSI", "000995.CSI", "931775.CSI",
            "931931.CSI", "931932.CSI", "931935.CSI", "931936.CSI", "930910.CSI", "930697.CSI", "931528.CSI",
            "399975.SZ", "000918.CSI", "000919.CSI", "000920.CSI", "000921.CSI", "000821.CSI", "000822.CSI",
            "000828.CSI", "000829.CSI", "000830.CSI", "000831.CSI", "000982.SH", "000984.CSI", "930740.CSI",
            "930782.CSI", "930846.CSI", "930985.CSI", "931155.CSI", "931375.CSI", "931847.CSI", "932430.CSI",
            "932431.CSI", "932432.CSI", "932400.CSI", "932401.CSI", "932402.CSI", "932403.CSI", "932494.CSI", "932497.CSI");
    public static final List<String> SW2021_L1_31 = List.of(
            "801010.SI", "801030.SI", "801040.SI", "801050.SI", "801080.SI", "801880.SI", "801110.SI",
            "801120.SI", "801130.SI", "801140.SI", "801150.SI", "801160.SI", "801170.SI", "801180.SI",
            "801200.SI", "801210.SI", "801780.SI", "801790.SI", "801230.SI", "801710.SI", "801720.SI",
            "801730.SI", "801890.SI", "801740.SI", "801750.SI", "801760.SI", "801770.SI", "801950.SI",
            "801960.SI", "801970.SI", "801980.SI");
    private static final Map<String,Index> INDEXES;
    static {
        if (CORE57.size() != 57 || SW2021_L1_31.size() != 31) throw new ExceptionInInitializerError("D019 source universe cardinality changed");
        var values = new LinkedHashMap<String,Index>();
        CORE57.forEach(code -> add(values, code, Route.INDEX_DAILY, Scope.CORE57));
        SW2021_L1_31.forEach(code -> add(values, code, Route.SW_DAILY, Scope.SW2021_L1_31));
        INDEXES = Collections.unmodifiableMap(values);
    }
    private static void add(Map<String,Index> values, String code, Route route, Scope scope) {
        if (!code.matches("[0-9]{6}\\.(?:SH|SZ|CSI|SI)")
                || values.putIfAbsent(code, new Index(code, route, scope)) != null)
            throw new ExceptionInInitializerError("Duplicate or invalid D019 source index code: " + code);
    }
    public static Index resolve(String code) {
        if (code == null) return null;
        return INDEXES.get(code.strip().toUpperCase(Locale.ROOT));
    }
    public static boolean valid(String code) { return resolve(code) != null; }
    public static List<String> codes(Scope scope) {
        return switch (Objects.requireNonNull(scope)) { case CORE57 -> CORE57; case SW2021_L1_31 -> SW2021_L1_31; };
    }
    public static List<String> allCodes() { return List.copyOf(INDEXES.keySet()); }
    public static String endpoint(Route route) { return switch (route) { case INDEX_DAILY -> "index_daily"; case SW_DAILY -> "sw_daily"; }; }
}
