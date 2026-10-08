package com.zoutrankil.data.domain.policy;

import java.util.List;
import java.util.Map;

/** Frozen D021 source universe, matching the Python connector's eight index codes. */
public final class IndexWeightUniverse {
    private IndexWeightUniverse() {}
    public enum Route { CSINDEX_OSS_XLS, TUSHARE_INDEX_WEIGHT }
    public record Index(String code, String tushareCode, Route route, int expectedMembers) {}
    public static final List<Index> INDEXES = List.of(
            new Index("000300", "000300.SH", Route.CSINDEX_OSS_XLS, 300),
            new Index("000016", "000016.SH", Route.CSINDEX_OSS_XLS, 50),
            new Index("000688", "000688.SH", Route.CSINDEX_OSS_XLS, 50),
            new Index("000905", "000905.SH", Route.CSINDEX_OSS_XLS, 500),
            new Index("000510", "000510.SH", Route.CSINDEX_OSS_XLS, 500),
            new Index("000852", "000852.SH", Route.CSINDEX_OSS_XLS, 1000),
            new Index("932000", "932000.CSI", Route.CSINDEX_OSS_XLS, 2000),
            new Index("399673", "399673.SZ", Route.TUSHARE_INDEX_WEIGHT, 50));
    private static final Map<String,Index> BY_CODE = INDEXES.stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(Index::code, value -> value));
    public static Index resolve(String code) {
        if (code == null) return null;
        String normalized;
        try { normalized = normalizeIndexCode(code); }
        catch (IllegalArgumentException invalid) { return null; }
        return BY_CODE.get(normalized);
    }
    public static String normalizeIndexCode(String value) {
        if (value == null) throw new IllegalArgumentException("index_code required");
        String code = value.strip().toUpperCase(java.util.Locale.ROOT);
        int dot = code.indexOf('.');
        if (dot >= 0) code = code.substring(0, dot);
        if (!code.matches("[0-9]{1,6}")) throw new IllegalArgumentException("Invalid six digit index_code: " + value);
        return "0".repeat(6 - code.length()) + code;
    }
}
