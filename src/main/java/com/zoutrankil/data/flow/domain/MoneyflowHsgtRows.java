package com.zoutrankil.data.flow.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.flow.domain.MoneyflowHsgtState.*;
import java.util.*;

/** Stable field order, canonical encoding and all-field comparisons. */
public final class MoneyflowHsgtRows {
 private MoneyflowHsgtRows() {}
public static DatasetValues values(MoneyflowHsgt row) {
        var values = new LinkedHashMap<String,Object>();
        values.put("trade_date", row.tradeDate()); values.put("ggt_ss", row.ggtSs());
        values.put("ggt_sz", row.ggtSz()); values.put("hgt", row.hgt()); values.put("sgt", row.sgt());
        values.put("north_money", row.northMoney()); values.put("south_money", row.southMoney());
        return new DatasetValues(values);
    }
 public static byte[] canonicalBytes(MoneyflowHsgt row) {
  try {return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());}
  catch(Exception failure) {throw new IllegalArgumentException("Cannot canonicalize D027 row",failure);}
 }
public static boolean sameContent(Snapshot a,Snapshot b){return a!=null&&b!=null&&a.rows().size()==b.rows().size()&&a.fingerprint().equals(b.fingerprint());}
public static List<MoneyflowHsgt> ordered(List<MoneyflowHsgt> rows){
        return rows.stream().sorted((a,b)->{int date=a.tradeDate().compareTo(b.tradeDate());
            return date!=0?date:Arrays.compareUnsigned(canonicalBytes(a),canonicalBytes(b));}).toList();
    }
public static boolean sameRows(List<MoneyflowHsgt> left,List<MoneyflowHsgt> right){
        if(left.size()!=right.size())return false;
        var a=ordered(left);var b=ordered(right);
        for(int i=0;i<a.size();i++)if(!Arrays.equals(canonicalBytes(a.get(i)),canonicalBytes(b.get(i))))return false;
        return true;
    }
}
