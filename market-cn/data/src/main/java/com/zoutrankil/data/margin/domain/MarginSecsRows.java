package com.zoutrankil.data.margin.domain;

import com.zoutrankil.data.domain.*;
import java.util.*;
import java.security.MessageDigest;

/** Stable field order, canonical encoding and all-field comparisons. */
public final class MarginSecsRows {
 private MarginSecsRows() {}
public static DatasetValues values(MarginSecs row) {
        var values = new LinkedHashMap<String, Object>();
        values.put("trade_date", row.tradeDate()); values.put("ts_code", row.tsCode());
        values.put("name", row.name()); values.put("exchange", row.exchange());
        return new DatasetValues(values);
    }
 public static byte[] canonicalBytes(MarginSecs row) {
  try {return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());}
  catch(Exception failure) {throw new IllegalArgumentException("Cannot canonicalize D030 row",failure);}
 }
public static boolean sameRows(List<MarginSecs> expected,List<MarginSecs> actual) {
        if(expected==null||actual==null||expected.size()!=actual.size())return false;
        var left=expected.stream().sorted(Comparator.comparing(MarginSecs::tradeDate).thenComparing(MarginSecs::tsCode)).toList();
        var right=actual.stream().sorted(Comparator.comparing(MarginSecs::tradeDate).thenComparing(MarginSecs::tsCode)).toList();
        return left.equals(right);
    }
public static String fingerprint(List<MarginSecs> rows) {
        try { var md=MessageDigest.getInstance("SHA-256");for(var row:rows){md.update(canonicalBytes(row));md.update((byte)'\n');}return HexFormat.of().formatHex(md.digest()); }
        catch(Exception failure){throw new IllegalStateException("Cannot fingerprint D030 target",failure);}
    }
}
