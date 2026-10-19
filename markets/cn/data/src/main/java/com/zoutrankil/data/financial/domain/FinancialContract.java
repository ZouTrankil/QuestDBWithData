package com.zoutrankil.data.financial.domain;

import com.zoutrankil.data.domain.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.zoutrankil.data.domain.DatasetDefinition.*;

/** 五条正式财务源的显式列契约；保留原表三列业务键，不合并不同报表类别。 */
public record FinancialContract(String dataset, List<Field> fields) {
    public record Field(String name,String type,boolean key,String source) {
        public Field {if(source==null)source=name;}
        public boolean collected(){return !source.equals("legacy:preserve");}
    }
    public record Key(String code,LocalDate announcement,String period) {}
    public record Row(Map<String,Object> values) {
        public Row { values=Collections.unmodifiableMap(new TreeMap<>(values)); }
        public Key key(){return new Key((String)values.get("ts_code"),(LocalDate)values.get("ann_date"),(String)values.get("end_date"));}
    }
    public static final Set<String> DATASETS=Set.of("cashflow","income","express","forecast","fina_indicator");
    public FinancialContract { if(!DATASETS.contains(dataset))throw new IllegalArgumentException("未知财务源");fields=List.copyOf(fields); }
    public DatasetDefinition definition() {
        var date=new TemporalContract(TemporalKind.BUSINESS_DATE,"BASIC","calendar","DAY","来源公告日期，UTC 零点仅承载日期");
        var columns=fields.stream().map(f->new Column(f.collected()?f.source():"derived:legacy_preserved",f.name(),f.name(),StorageType.valueOf(f.type()),!f.key(),
                (f.name().equals("end_date")?"报告期的 YYYYMMDD 字符串；保留日期语义，不作时区平移":f.name().equals("ann_date")?"业务公告日期；UTC 零点仅承载日期":f.collected()?"Tushare "+dataset+" 原单位字段："+f.name():"历史本地列；增量保留原值，不推断计算含义"),f.name().equals("ann_date")?date:null)).toList();
        return new DatasetDefinition(dataset,1,"tushare."+dataset,"financial_owner",dataset,ObjectKind.TABLE,columns,
                List.of("ts_code","ann_date","end_date"),List.of("ts_code","ann_date","end_date"),"ann_date",Partition.YEAR,true,
                Set.of(Capability.READ,Capability.WRITE),List.of(),"保留正式 YEAR/WAL 表；income/cashflow 只接合并报表 report_type=1，其他类别另建契约");
    }
    /** 公告区间与报告期含义不同；按证券和报告期重查才能捕获晚到及旧季度修订。 */
    public Map<String,Object> parameters(String code,LocalDate period) {
        if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")||!quarterEnd(period))throw new IllegalArgumentException("证券或季度末不合法");
        var p=new LinkedHashMap<String,Object>();p.put("ts_code",code);p.put("period",period.format(DateTimeFormatter.BASIC_ISO_DATE));
        if(Set.of("cashflow","income").contains(dataset))p.put("report_type","1");return p;
    }
    public static boolean quarterEnd(LocalDate d){return d!=null&&d.getMonthValue()%3==0&&d.getDayOfMonth()==d.lengthOfMonth();}
}
