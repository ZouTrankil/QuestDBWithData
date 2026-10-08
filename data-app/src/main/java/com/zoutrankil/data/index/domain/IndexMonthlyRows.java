package com.zoutrankil.data.index.domain;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import com.zoutrankil.data.index.domain.IndexMonthlyState.*;
public final class IndexMonthlyRows {
private IndexMonthlyRows(){}
public static final int CLIENT_ROW_CAP=1000;
public static DatasetValues values(IndexMonthly row) {
        var values=new LinkedHashMap<String,Object>(); values.put("ts_code",row.tsCode());values.put("trade_date",row.tradeDate());
        values.put("close",row.close());values.put("open",row.open());values.put("high",row.high());values.put("low",row.low());
        values.put("pre_close",row.preClose());values.put("change",row.change());values.put("pct_chg",row.pctChg());
        values.put("vol",row.vol());values.put("amount",row.amount());values.put("layer",row.layer());values.put("bucket",row.bucket());
        values.put("update_time",row.updateTime());return new DatasetValues(values);
    }
public static byte[] canonicalBytes(IndexMonthly row) { try {return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());} catch(Exception failure){throw new IllegalArgumentException("Cannot encode D022 full row",failure);} }

public static boolean sameContent(IndexMonthlyState.Snapshot left,IndexMonthlyState.Snapshot right){return left!=null&&right!=null&&left.rows().size()==right.rows().size()&&left.fingerprint().equals(right.fingerprint());}
}
