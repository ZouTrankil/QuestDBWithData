package com.zoutrankil.data.financial.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.financial.domain.FinancialGrowthPatch.Key;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Frozen VIP response. Explicit fields include growth columns omitted by Tushare defaults. */
public record FinancialGrowthEvidence(String sha256,String period,int sourceRows,Map<Key,JsonNode> rows) {
    public static final List<String> FIELDS=List.of(("ts_code,ann_date,end_date,eps,dt_eps,total_revenue_ps,revenue_ps,capital_rese_ps,surplus_rese_ps,undist_profit_ps,extra_item,profit_dedt,gross_margin,current_ratio,quick_ratio,cash_ratio,ar_turn,ca_turn,fa_turn,assets_turn,op_income,ebit,ebitda,fcff,fcfe,current_exint,noncurrent_exint,interestdebt,netdebt,tangible_asset,working_capital,networking_capital,invest_capital,retained_earnings,diluted2_eps,bps,ocfps,retainedps,cfps,ebit_ps,fcff_ps,fcfe_ps,netprofit_margin,grossprofit_margin,cogs_of_sales,expense_of_sales,profit_to_gr,saleexp_to_gr,adminexp_of_gr,finaexp_of_gr,impai_ttm,gc_of_gr,op_of_gr,ebit_of_gr,roe,roe_waa,roe_dt,roa,npta,roic,roe_yearly,roa2_yearly,debt_to_assets,assets_to_eqt,dp_assets_to_eqt,ca_to_assets,nca_to_assets,tbassets_to_totalassets,int_to_talcap,eqt_to_talcapital,currentdebt_to_debt,longdeb_to_debt,ocf_to_shortdebt,debt_to_eqt,eqt_to_debt,eqt_to_interestdebt,tangibleasset_to_debt,tangasset_to_intdebt,tangibleasset_to_netdebt,ocf_to_debt,turn_days,roa_yearly,roa_dp,fixed_assets,profit_to_op,q_saleexp_to_gr,q_gc_to_gr,q_roe,q_dt_roe,q_npta,q_ocf_to_sales,basic_eps_yoy,dt_eps_yoy,cfps_yoy,op_yoy,ebt_yoy,netprofit_yoy,dt_netprofit_yoy,ocf_yoy,roe_yoy,bps_yoy,assets_yoy,eqt_yoy,tr_yoy,or_yoy,q_sales_yoy,q_op_qoq,equity_yoy,q_gr_yoy,q_profit_yoy,update_flag").split(","));
    public static FinancialGrowthEvidence read(Path path,LocalDate logicalDate) throws Exception {
        byte[] bytes=FileEvidenceStore.readBounded(path,64*1024*1024,()->new IllegalArgumentException("Financial response exceeds 64 MiB"));
        var root=JobDefinitionJson.mapper().readTree(bytes);var fields=new ArrayList<String>();root.path("fields").forEach(n->fields.add(n.asText()));
        String period=root.path("params").path("period").asText();LocalDate end=date(period);
        if(!Set.of(3,6,9,12).contains(end.getMonthValue()) || end.getDayOfMonth()!=end.lengthOfMonth() || end.isAfter(logicalDate)
                || !"fina_indicator_vip".equals(root.path("endpoint").asText()) || root.path("code").asInt(-1)!=0
                || !new HashSet<>(fields).equals(new HashSet<>(FIELDS)) || fields.size()!=FIELDS.size()
                || !root.path("rows").isArray() || root.path("rows").isEmpty() || root.path("rows").size()>20000)
            throw new IllegalArgumentException("Bounded exact-quarter VIP response and explicit field contract required");
        var selected=new LinkedHashMap<Key,JsonNode>();var ambiguous=new HashSet<Key>();
        for(var row:root.path("rows")) {
            if(!row.isObject() || row.size()!=FIELDS.size())throw new IllegalArgumentException("Incomplete financial source row");
            String symbol=row.path("ts_code").asText(),flag=row.path("update_flag").asText();
            if(!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)") || !Set.of("0","1").contains(flag) || !period.equals(row.path("end_date").asText()))
                throw new IllegalArgumentException("Invalid financial source identity or update flag");
            var announcement=date(row.path("ann_date").asText());
            if(announcement.isAfter(logicalDate))throw new IllegalArgumentException("Future announcement in frozen source");
            for(String field:FIELDS) {
                var value=row.get(field);
                if(value==null || !Set.of("ts_code","ann_date","end_date","update_flag").contains(field)
                        && !value.isNull() && (!value.isNumber() || !Double.isFinite(value.doubleValue())))
                    throw new IllegalArgumentException("Missing or invalid source field: "+field);
            }
            var key=new Key(symbol,announcement,period);var old=selected.get(key);
            // Tushare FAQ doc 122: update_flag=1 denotes revised data; never prefer a non-null old value.
            if(old==null || flag.compareTo(old.path("update_flag").asText())>0) {selected.put(key,row);ambiguous.remove(key);}
            else if(flag.equals(old.path("update_flag").asText()) && !old.equals(row))ambiguous.add(key);
        }
        if(!ambiguous.isEmpty())throw new IllegalArgumentException("Conflicting financial rows at the same revision priority: "+ambiguous.size());
        return new FinancialGrowthEvidence(FileEvidenceStore.sha256(bytes),period,root.path("rows").size(),Collections.unmodifiableMap(selected));
    }
    public static LocalDate date(String text) {
        if(!text.matches("[0-9]{8}"))throw new IllegalArgumentException("Exact YYYYMMDD required");
        return LocalDate.parse(text,DateTimeFormatter.BASIC_ISO_DATE);
    }
}
