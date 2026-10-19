package com.zoutrankil.data.margin.domain;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.margin.domain.MarginZrzState.Snapshot;
import java.util.*;
/** Pure row encoding and equality for receipt and physical snapshot comparisons. */
public final class MarginZrzRows {
    private MarginZrzRows() {}
public static DatasetValues values(MarginZrz row) {
        var v=new LinkedHashMap<String,Object>();v.put("trade_date",row.tradeDate());v.put("ob",row.ob());
        v.put("auc_amount",row.aucAmount());v.put("repo_amount",row.repoAmount());v.put("repay_amount",row.repayAmount());v.put("cb",row.cb());
        return new DatasetValues(v);
    }
public static byte[] canonicalBytes(MarginZrz row){try{return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());}catch(Exception e){throw new IllegalArgumentException("Cannot canonicalize D031 row",e);}}
public static boolean sameContent(Snapshot a,Snapshot b){return a!=null&&b!=null&&a.rows().size()==b.rows().size()&&a.fingerprint().equals(b.fingerprint());}
public static List<MarginZrz> ordered(List<MarginZrz> rows){return rows.stream().sorted(Comparator.comparing(MarginZrz::tradeDate)).toList();}
public static boolean sameRows(List<MarginZrz>a,List<MarginZrz>b){if(a.size()!=b.size())return false;var x=ordered(a);var y=ordered(b);for(int i=0;i<x.size();i++)if(!Arrays.equals(canonicalBytes(x.get(i)),canonicalBytes(y.get(i))))return false;return true;}
}
