package com.zoutrankil.data.margin.domain;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.domain.MarginAllState.Snapshot;
import java.util.*;
/** Pure row encoding and equality for receipt and physical snapshot comparisons. */
public final class MarginAllRows {
    private MarginAllRows() {}
public static DatasetValues values(MarginAll row) {
        var v = new LinkedHashMap<String,Object>(); v.put("trade_date", row.tradeDate()); v.put("exchange_id", row.exchangeId());
        v.put("rzye", row.rzye()); v.put("rzmre", row.rzmre()); v.put("rzche", row.rzche()); v.put("rqye", row.rqye());
        v.put("rqmcl", row.rqmcl()); v.put("rzrqye", row.rzrqye()); v.put("rqyl", row.rqyl()); return new DatasetValues(v);
    }
public static byte[] canonicalBytes(MarginAll row){try{return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());}catch(Exception e){throw new IllegalArgumentException("Cannot canonicalize D028 row",e);}}
public static boolean sameContent(Snapshot a,Snapshot b){return a!=null&&b!=null&&a.rows().size()==b.rows().size()&&a.fingerprint().equals(b.fingerprint());}
public static List<MarginAll> ordered(List<MarginAll> rows){return rows.stream().sorted((a,b)->{int date=a.tradeDate().compareTo(b.tradeDate());if(date!=0)return date;int exchange=a.exchangeId().compareTo(b.exchangeId());return exchange!=0?exchange:Arrays.compareUnsigned(canonicalBytes(a),canonicalBytes(b));}).toList();}
public static boolean sameRows(List<MarginAll>a,List<MarginAll>b){if(a.size()!=b.size())return false;var x=ordered(a);var y=ordered(b);for(int i=0;i<x.size();i++)if(!Arrays.equals(canonicalBytes(x.get(i)),canonicalBytes(y.get(i))))return false;return true;}
}
