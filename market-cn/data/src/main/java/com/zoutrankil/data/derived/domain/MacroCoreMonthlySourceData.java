package com.zoutrankil.data.derived.domain;
import com.zoutrankil.data.domain.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable source census and physical identity; component order is evidence. */
public final class MacroCoreMonthlySourceData {
    private MacroCoreMonthlySourceData() {}
    public static final int MAX_MONTHS=12, MAX_SOURCE_ROWS=84;
    public static final List<String> SOURCE_TABLES=List.of("cn_cpi","cn_ppi","cn_pmi","cn_m","cn_gdp","sf_month");
    public static final List<String> REQUIRED_MONTHLY_FIELDS=List.of("cpi_yoy","ppi_yoy","pmi_mfg","m2_yoy","social_financing_stock","new_rmb_loan");
    public record SourceSpec(String table,String timestamp,List<String> fields) {
        public SourceSpec { fields=List.copyOf(fields); }
        public String type(String column){return column.equals(timestamp)?"TIMESTAMP":column.equals("quarter")?"STRING":"DOUBLE";}
    }
    public static final Map<String,SourceSpec> SOURCE_SPECS=specs();
    private static Map<String,SourceSpec> specs(){var specs=new LinkedHashMap<String,SourceSpec>();
        specs.put("cn_cpi", new SourceSpec("cn_cpi", "month", List.of("month", "nt_val", "nt_yoy", "nt_mom", "nt_accu", "town_val", "town_yoy", "town_mom", "town_accu", "cnt_val", "cnt_yoy", "cnt_mom", "cnt_accu")));
        specs.put("cn_ppi", new SourceSpec("cn_ppi", "month", List.of("month", "ppi_yoy", "ppi_mp_yoy", "ppi_mp_qm_yoy", "ppi_mp_rm_yoy", "ppi_mp_p_yoy", "ppi_cg_yoy", "ppi_cg_f_yoy", "ppi_cg_c_yoy", "ppi_cg_adu_yoy", "ppi_cg_dcg_yoy", "ppi_mom", "ppi_mp_mom", "ppi_mp_qm_mom", "ppi_mp_rm_mom", "ppi_mp_p_mom", "ppi_cg_mom", "ppi_cg_f_mom", "ppi_cg_c_mom", "ppi_cg_adu_mom", "ppi_cg_dcg_mom", "ppi_accu", "ppi_mp_accu", "ppi_mp_qm_accu", "ppi_mp_rm_accu", "ppi_mp_p_accu", "ppi_cg_accu", "ppi_cg_f_accu", "ppi_cg_c_accu", "ppi_cg_adu_accu", "ppi_cg_dcg_accu")));
        specs.put("cn_pmi", new SourceSpec("cn_pmi", "month", List.of("month", "pmi010000", "pmi010100", "pmi010200", "pmi010300", "pmi010400", "pmi010401", "pmi010402", "pmi010403", "pmi010500", "pmi010501", "pmi010502", "pmi010503", "pmi010600", "pmi010601", "pmi010602", "pmi010603", "pmi010700", "pmi010701", "pmi010702", "pmi010703", "pmi010800", "pmi010801", "pmi010802", "pmi010803", "pmi010900", "pmi011000", "pmi011100", "pmi011200", "pmi011300", "pmi011400", "pmi011500", "pmi011600", "pmi011700", "pmi011800", "pmi011900", "pmi012000", "pmi020100", "pmi020101", "pmi020102", "pmi020200", "pmi020201", "pmi020202", "pmi020300", "pmi020301", "pmi020302", "pmi020400", "pmi020401", "pmi020402", "pmi020500", "pmi020501", "pmi020502", "pmi020600", "pmi020601", "pmi020602", "pmi020700", "pmi020800", "pmi020900", "pmi021000", "pmi030000")));
        specs.put("cn_m", new SourceSpec("cn_m", "month", List.of("month", "m0", "m0_yoy", "m0_mom", "m1", "m1_yoy", "m1_mom", "m2", "m2_yoy", "m2_mom")));
        specs.put("cn_gdp", new SourceSpec("cn_gdp", "report_date", List.of("quarter", "report_date", "gdp", "gdp_yoy", "pi", "pi_yoy", "si", "si_yoy", "ti", "ti_yoy")));
        specs.put("sf_month", new SourceSpec("sf_month", "month", List.of("month", "inc_month", "inc_cumval", "stk_endval")));
        return Collections.unmodifiableMap(specs);
    }
    public record PhysicalSnapshot(String table,long tableId,String directory,Long physicalTxn,Long walTxn,long sequenceTxn,long writerTxn,long pendingRows,long bufferedTxns,Long metadataRowCount,String schemaHash) {
        public PhysicalSnapshot {
            if(!SOURCE_TABLES.contains(table)||tableId<0||directory==null||directory.isBlank()||physicalTxn!=null&&physicalTxn<0||walTxn!=null&&walTxn<0||sequenceTxn<0||writerTxn<0||pendingRows<0||bufferedTxns<0||metadataRowCount!=null&&metadataRowCount<0||schemaHash==null||!schemaHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("D104 complete source physical identity required");
        }
    }
    public record Snapshot(List<PhysicalSnapshot> sources) {
        public Snapshot {sources=List.copyOf(sources);if(!sources.stream().map(PhysicalSnapshot::table).toList().equals(SOURCE_TABLES))throw new IllegalArgumentException("D104 fixed six-source vector required");}
        public String version(){return hash(sources.toString());}
        public String identity(){var text=new StringBuilder();for(var s:sources)text.append(s.table()).append(':').append(s.tableId()).append(':').append(s.directory()).append(':').append(s.schemaHash()).append('\n');return hash(text.toString());}
    }
    public record Derived(List<MacroCoreMonthly> rows,List<YearMonth> candidateMonths,List<YearMonth> withheldMonths) {
        public Derived {rows=List.copyOf(rows);candidateMonths=List.copyOf(candidateMonths);withheldMonths=List.copyOf(withheldMonths);}
    }
    public record Batch(Snapshot snapshot,String rawFingerprint,int rawRows,List<MacroCoreMonthly> rows,
            List<YearMonth> candidateMonths,List<YearMonth> withheldMonths,int sfContextRows,Map<String,Integer> windowRowsBySource) {
        public Batch { Objects.requireNonNull(snapshot);rows=List.copyOf(rows);candidateMonths=List.copyOf(candidateMonths);withheldMonths=List.copyOf(withheldMonths);windowRowsBySource=Collections.unmodifiableMap(new LinkedHashMap<>(windowRowsBySource));
            if(rawFingerprint==null||!rawFingerprint.matches("[0-9a-f]{64}")||rawRows<0||rawRows>MAX_SOURCE_ROWS||rows.size()>MAX_MONTHS||sfContextRows<0||sfContextRows>12||candidateMonths.size()>12||!windowRowsBySource.keySet().equals(new LinkedHashSet<>(SOURCE_TABLES)))throw new IllegalArgumentException("D104 finite complete source batch required");
        }
        public String fingerprint(){return hash(snapshot.version()+"\n"+rawFingerprint);}
    }
    static String hash(String text){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
