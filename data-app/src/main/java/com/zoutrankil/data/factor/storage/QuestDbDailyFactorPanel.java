package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.DailyFactorPanel;
import com.zoutrankil.data.factor.engine.StockCodeAliases;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Whole-market snapshot, monthly SQL scans and primitive column buffers shared by every pool. */
public final class QuestDbDailyFactorPanel {
    private static final List<String> MARKET=List.of("close","open","high","low","amount","vol","up_limit","down_limit","is_st","is_suspended");
    private static final List<String> BASIC=List.of("turnover_rate","total_mv","circ_mv","pb","pe_ttm","ps_ttm","dv_ttm");
    private static final List<String> FIELDS=java.util.stream.Stream.concat(MARKET.stream(),BASIC.stream()).toList();
    private final JdbcTemplate jdbc;
    public record Snapshot(List<DailyFactorPanel.Security> securities,List<Map<String,Object>> sourceState,
            LocalDate requestedFrom,LocalDate requestedThrough,LocalDate warmupFrom,long rows,long missingBasicRows,long missingListingDates,long excludedMarketRows,long excludedBasicRows) {
        public Snapshot { securities=List.copyOf(securities);sourceState=List.copyOf(sourceState); }
    }
    public QuestDbDailyFactorPanel(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(60);
    }
    public record PriceSnapshot(List<DailyFactorPanel.Security> securities,List<Map<String,Object>> sourceState,
            LocalDate historyFrom,LocalDate through,long sourceRows,long retainedRows,long excludedRows) {}
    /** Same alias/listing rules as the full panel, without basic-data joins or unused columns. */
    public PriceSnapshot readPrices(LocalDate historyFrom,LocalDate through,int maxRows) {
        return readProjected(historyFrom,through,maxRows,"v_backtest_daily",List.of("close"),new int[]{0});
    }
    public record CsiSnapshot(PriceSnapshot market,PriceSnapshot basic,List<Map<String,Object>> sourceState) {
        public List<com.zoutrankil.data.factor.engine.EtfCsiPipeline.Security> prepare(QuestDbCsiFinancials.Snapshot financials) {
            var basicBySymbol=new java.util.HashMap<String,DailyFactorPanel.Security>();
            for(var security:basic.securities())basicBySymbol.put(security.symbol(),security);
            var result=new ArrayList<com.zoutrankil.data.factor.engine.EtfCsiPipeline.Security>();
            for(var security:market.securities()) {
                var reference=basicBySymbol.get(security.symbol());
                var basicDates=reference==null?new LocalDate[0]:reference.dates();
                var turnover=reference==null?new double[0]:reference.columns().get("turnover_rate");
                var columns=new LinkedHashMap<>(com.zoutrankil.data.factor.engine.EtfCsiTemporal.compute(
                        security.dates(),security.columns().get("close"),security.columns().get("amount"),basicDates,turnover));
                for(String field:List.of("total_mv","pb","pe_ttm","dv_ttm")) {
                    double[] values=new double[security.dates().length];Arrays.fill(values,Double.NaN);int cursor=0;
                    for(int i=0;i<values.length;i++) {
                        while(cursor<basicDates.length && basicDates[cursor].isBefore(security.dates()[i]))cursor++;
                        if(cursor<basicDates.length && basicDates[cursor].equals(security.dates()[i]))
                            values[i]=reference.columns().get(field)[cursor];
                    }
                    columns.put(field,values);
                }
                columns.putAll(financials.align(security.symbol(),security.dates()));
                result.add(new com.zoutrankil.data.factor.engine.EtfCsiPipeline.Security(security.symbol(),security.dates(),Map.copyOf(columns)));
            }
            return List.copyOf(result);
        }
    }
    /** Preserve the basic table's own observed dates for turnover rolling windows. */
    public CsiSnapshot readCsi(LocalDate historyFrom,LocalDate through,int maxRowsPerSource) {
        var before=state();
        var market=readProjected(historyFrom,through,maxRowsPerSource,"v_backtest_daily",List.of("close","amount"),new int[]{0,1});
        var basic=readProjected(historyFrom,through,maxRowsPerSource,"daily_basic",
                List.of("turnover_rate","total_mv","pb","pe_ttm","dv_ttm"),new int[0]);
        if(!before.equals(state()))throw new IllegalStateException("CSI market/basic sources changed during read");
        return new CsiSnapshot(market,basic,before);
    }
    public void verifyUnchanged(CsiSnapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("CSI market/basic sources changed after read");
    }
    private PriceSnapshot readProjected(LocalDate historyFrom,LocalDate through,int maxRows,
            String table,List<String> fields,int[] priceColumns) {
        if(historyFrom==null || through==null || historyFrom.isAfter(through)
                || ChronoUnit.DAYS.between(historyFrom,through)>com.zoutrankil.data.factor.engine.EtfHistoryWindow.MAX_DAYS || maxRows<1 || maxRows>5_000_000)
            throw new IllegalArgumentException("Bounded stock price history required");
        var before=table.equals("v_backtest_daily")?priceState():state();var listing=listingDates();var aliases=StockCodeAliases.load();
        var builders=new LinkedHashMap<String,Columns>();long[] sourceRows={0};long kept=0,excluded=0;
        for(LocalDate start=historyFrom;!start.isAfter(through);) {
            LocalDate end=start.withDayOfMonth(1).plusMonths(1);if(end.isAfter(through.plusDays(1)))end=through.plusDays(1);
            var normalized=aliases.normalize(readSource(table,fields,
                    "trade_date>='"+start+"' AND trade_date<'"+end+"'",maxRows,sourceRows,0),priceColumns);
            var ordered=new ArrayList<>(normalized);
            ordered.sort(java.util.Comparator.comparing(StockCodeAliases.Row::symbol).thenComparing(StockCodeAliases.Row::date));
            for(var row:ordered) {
                if(outsideListing(row,listing)){excluded++;continue;}
                builders.computeIfAbsent(row.symbol(),ignored->new Columns(fields)).append(row.date(),row.values());kept++;
            }
            start=end;
        }
        if(!before.equals(table.equals("v_backtest_daily")?priceState():state()))throw new IllegalStateException("Stock source changed during read");
        var securities=new ArrayList<DailyFactorPanel.Security>();
        builders.forEach((symbol,columns)->securities.add(columns.finish(symbol,listing.containsKey(symbol)?listing.get(symbol).listed():null)));
        return new PriceSnapshot(List.copyOf(securities),before,historyFrom,through,sourceRows[0],kept,excluded);
    }
    public void verifyUnchanged(PriceSnapshot snapshot) {
        if(!snapshot.sourceState().equals(priceState()))throw new IllegalStateException("Stock price source changed after read");
    }
    private List<Map<String,Object>> priceState() {
        if(!QuestDbWriteChecks.walSettled(jdbc,"backtest_daily"))throw new IllegalStateException("Stock price WAL unsettled");
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn FROM tables()"
                +" WHERE table_name IN ('backtest_daily','stock_detail_info') ORDER BY table_name");
        if(rows.size()!=2)throw new IllegalStateException("Stock price/reference source missing");
        return BacktestFactorSource.stamp(jdbc,rows);
    }
    public Snapshot read(LocalDate from,LocalDate through,int warmupDays,int maxRows) {
        if(from==null || through==null || from.isAfter(through) || ChronoUnit.DAYS.between(from,through)>30
                || warmupDays!=0 && (warmupDays<460 || warmupDays>1000) || maxRows<1 || maxRows>5_000_000)
            throw new IllegalArgumentException("At most 31 output days, snapshot-only (0) or 460..1000 warmup days and bounded row budget required");
        var before=state();LocalDate warmup=from.minusDays(warmupDays);
        var listingDates=listingDates();
        var builders=new LinkedHashMap<String,Columns>();long[] counts={0,0,0,0};
        var aliases=StockCodeAliases.load();
        long[] sourceRows={0,0};
        for(LocalDate start=warmup;!start.isAfter(through);) {
            LocalDate end=start.withDayOfMonth(1).plusMonths(1);
            if(end.isAfter(through.plusDays(1)))end=through.plusDays(1);
            String filter="trade_date>='"+start+"' AND trade_date<'"+end+"'";
            var market=aliases.normalize(readSource("v_backtest_daily",MARKET,filter,maxRows,sourceRows,0),new int[]{0,1,2,3,4,5});
            var basic=aliases.normalize(readSource("daily_basic",BASIC,filter,maxRows,sourceRows,1),new int[0]);
            var basicByKey=new java.util.HashMap<SecurityDate,double[]>();
            for(var row:basic) {
                if(outsideListing(row,listingDates)) { counts[3]++;continue; }
                basicByKey.put(new SecurityDate(row.symbol(),row.date()),row.values());
            }
            var ordered=new ArrayList<>(market);
            ordered.sort(java.util.Comparator.comparing(StockCodeAliases.Row::symbol).thenComparing(StockCodeAliases.Row::date));
            for(var row:ordered) {
                if(outsideListing(row,listingDates)) { counts[2]++;continue; }
                counts[0]++;
                var values=Arrays.copyOf(row.values(),FIELDS.size());
                var joined=basicByKey.get(new SecurityDate(row.symbol(),row.date()));
                if(joined==null) {
                    counts[1]++;Arrays.fill(values,MARKET.size(),FIELDS.size(),Double.NaN);
                } else System.arraycopy(joined,0,values,MARKET.size(),BASIC.size());
                builders.computeIfAbsent(row.symbol(),ignored->new Columns()).append(row.date(),values);
            }
            start=end;
        }
        if(!before.equals(state()))throw new IllegalStateException("Panel sources changed during read; retry");
        var securities=new ArrayList<DailyFactorPanel.Security>(builders.size());
        builders.forEach((symbol,columns)->securities.add(columns.finish(symbol,listingDates.containsKey(symbol)?listingDates.get(symbol).listed():null)));
        return new Snapshot(securities,before,from,through,warmup,counts[0],counts[1],
                securities.stream().filter(s -> s.listingDate()==null).count(),counts[2],counts[3]);
    }
    private record SecurityDate(String symbol,LocalDate date) {}
    private List<StockCodeAliases.Row> readSource(String table,List<String> fields,String filter,
            int maxRows,long[] counts,int source) {
        var rows=new ArrayList<StockCodeAliases.Row>();
        String sql="SELECT ts_code,trade_date,"+String.join(",",fields)+" FROM "+table+" WHERE "+filter
                +" ORDER BY ts_code,trade_date LIMIT "+(maxRows-counts[source]+1);
        jdbc.query(sql,(RowCallbackHandler) rs -> {
            if(++counts[source]>maxRows)throw new IllegalStateException("Panel source exceeds row budget: "+table);
            String symbol=rs.getString(1);var stamp=rs.getTimestamp(2);
            if(symbol==null || stamp==null)throw new IllegalStateException("Missing source entity/date");
            double[] values=new double[fields.size()];
            for(int c=0;c<values.length;c++) {
                values[c]=rs.getDouble(c+3);if(rs.wasNull())values[c]=Double.NaN;
                if(Double.isInfinite(values[c]))throw new IllegalStateException("Infinite panel input");
            }
            rows.add(new StockCodeAliases.Row(symbol,stamp.toLocalDateTime().toLocalDate(),values));
        });
        return rows;
    }
    private List<Map<String,Object>> state() {
        for(String table:List.of("backtest_daily","daily_basic"))
            if(!QuestDbWriteChecks.walSettled(jdbc,table))throw new IllegalStateException("Panel source WAL not settled: "+table);
        return BacktestFactorSource.stamp(jdbc,jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn FROM tables()"
                +" WHERE table_name IN ('backtest_daily','daily_basic','stock_detail_info') ORDER BY table_name"));
    }
    private Map<String,ListingInterval> listingDates() {
        var dates=new LinkedHashMap<String,ListingInterval>();
        var seen=new java.util.HashSet<String>();
        jdbc.query("SELECT ts_code,list_date,delist_date FROM stock_detail_info LIMIT 100001",(RowCallbackHandler) rs -> {
            String symbol=rs.getString(1),text=rs.getString(2);
            if(symbol==null || !seen.add(symbol))
                throw new IllegalStateException("Missing or duplicate stock reference entity");
            if(seen.size()>100000)throw new IllegalStateException("Stock reference exceeds row budget");
            LocalDate listed=parseDate(text),delisted=parseDate(rs.getString(3));
            if(listed==null)throw new IllegalStateException("Invalid listing date for "+symbol);
            dates.put(symbol,new ListingInterval(listed,delisted));

        });
        return dates;
    }
    private record ListingInterval(LocalDate listed,LocalDate delisted) {}
    private static LocalDate parseDate(String text) {
        if(text==null || text.isBlank())return null;
        try { return LocalDate.parse(text,java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
        catch(java.time.format.DateTimeParseException ignored) { return null; }
    }
    private static boolean outsideListing(StockCodeAliases.Row row,Map<String,ListingInterval> intervals) {
        var interval=intervals.get(row.symbol());
        if(interval==null)return row.symbol().matches("(?:20[0-9]{4}\\.SZ|900[0-9]{3}\\.SH)");
        return row.date().isBefore(interval.listed())
                || (interval.delisted()!=null && row.date().isAfter(interval.delisted()));
    }
    private static final class Columns {
        private final ArrayList<LocalDate> dates=new ArrayList<>();
        private final List<String> fields;
        private double[][] values;
        Columns(){this(FIELDS);}
        Columns(List<String> fields){this.fields=List.copyOf(fields);values=new double[fields.size()][512];}
        void append(LocalDate date,double[] row) {
            int n=dates.size();
            if(n>0 && !date.isAfter(dates.getLast()))throw new IllegalStateException("Duplicate or unordered security/date in panel join");
            if(n==values[0].length)for(int c=0;c<values.length;c++)values[c]=Arrays.copyOf(values[c],n*2);
            for(int c=0;c<values.length;c++) {
                double value=row[c];
                if(Double.isInfinite(value))throw new IllegalStateException("Infinite panel input");
                values[c][n]=value;
            }
            dates.add(date);
        }
        DailyFactorPanel.Security finish(String symbol,LocalDate listingDate) {
            var columns=new LinkedHashMap<String,double[]>();
            for(int c=0;c<values.length;c++)columns.put(fields.get(c),Arrays.copyOf(values[c],dates.size()));
            var security=new DailyFactorPanel.Security(symbol,dates.toArray(LocalDate[]::new),Map.copyOf(columns),listingDate);
            values=null;dates.clear();
            return security;
        }
    }
}
