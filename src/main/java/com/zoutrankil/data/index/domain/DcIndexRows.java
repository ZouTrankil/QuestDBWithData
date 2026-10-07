package com.zoutrankil.data.index.domain;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import com.zoutrankil.data.index.domain.DcIndexState.*;
public final class DcIndexRows {
private DcIndexRows(){}
public static final int MAX_ROWS=1_000_000,MAX_BYTES=256*1024*1024,SOURCE_ROW_CAP=5_000,MAX_BATCH_BYTES=1024*1024;
public static DatasetValues values(DcIndex row) {
        var m = new LinkedHashMap<String,Object>();
        m.put("ts_code",row.tsCode()); m.put("trade_date",row.tradeDate()); m.put("name",row.name());
        m.put("leading",row.leading()); m.put("leading_code",row.leadingCode()); m.put("pct_change",row.pctChange());
        m.put("leading_pct",row.leadingPct()); m.put("total_mv",row.totalMv()); m.put("turnover_rate",row.turnoverRate());
        m.put("up_num",row.upNum()); m.put("down_num",row.downNum());
        return new DatasetValues(m);
    }
public static byte[] canonicalBytes(DcIndex row) { try {return JobDefinitionJson.mapper().writeValueAsBytes(values(row).asMap());} catch(Exception failure){throw new IllegalArgumentException("Cannot canonicalize dc_index row",failure);} }

public static List<DcIndex> outside(List<DcIndex> rows,LocalDate from,LocalDate toExclusive){return rows.stream().filter(r->r.tradeDate().isBefore(from)||!r.tradeDate().isBefore(toExclusive)).toList();}
public static List<DcIndex> sourceUnique(Collection<DcIndex> values){if(values==null||values.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Null dc_index source row");
        var sorted=values.stream().sorted(Comparator.comparing(DcIndex::tradeDate).thenComparing(DcIndex::tsCode)).toList();var seen=new HashSet<DcIndexKey>();for(var row:sorted)if(!seen.add(row.key()))throw new IllegalArgumentException("Duplicate dc_index source natural key");return sorted;}
public static byte[] canonical(List<DcIndex> rows)throws Exception{
        var sorted=canonicalRows(rows);
        var out=new java.io.ByteArrayOutputStream();try(var data=new java.io.DataOutputStream(out)){for(var row:sorted){byte[] value=canonicalBytes(row);data.writeInt(value.length);data.write(value);if(out.size()>MAX_BYTES)throw new IllegalStateException("dc_index snapshot byte bound exceeded");}}
        return out.toByteArray();
    }
private static List<DcIndex> canonicalRows(Collection<DcIndex> rows){return rows.stream().sorted(Comparator.comparing(DcIndex::tradeDate).thenComparing(DcIndex::tsCode)
            .thenComparing(r->HexFormat.of().formatHex(canonicalBytes(r)))).toList();}
public static Prepared prepare(DcIndexState.Snapshot before,DcIndexState.Snapshot current,List<DcIndex> rows,LocalDate from,LocalDate to)throws Exception{
        if(before==null||current==null||from==null||to==null||from.isAfter(to)||!before.equals(current))throw new IllegalArgumentException("Ordered frozen window and unchanged D023 physical target required");
        LocalDate exclusive=to.plusDays(1);var incoming=DcIndexRows.sourceUnique(rows);
        for(var row:incoming)if(row.tradeDate().isBefore(from)||row.tradeDate().isAfter(to))throw new IllegalArgumentException("dc_index source escaped frozen replacement date window");
        var expected=new ArrayList<DcIndex>(DcIndexRows.outside(before.rows(),from,exclusive));expected.addAll(incoming);
        if(expected.size()>DcIndexRows.MAX_ROWS||DcIndexRows.canonical(expected).length>DcIndexRows.MAX_BYTES)throw new IllegalStateException("dc_index staged full snapshot exceeds bounded storage limit");
        return new Prepared(before,current,from,to,incoming,canonicalRows(expected));
    }
public static boolean sameRows(List<DcIndex> left,List<DcIndex> right)throws Exception{return Arrays.equals(DcIndexRows.canonical(left),DcIndexRows.canonical(right));}
public static Map<String,Object> snapshotProof(DcIndexState.Snapshot s){return Map.of("id",s.identity().id(),"directory",s.identity().directory(),"fingerprint",s.fingerprint(),"rowCount",s.rows().size(),"bytes",s.bytes());}
}
