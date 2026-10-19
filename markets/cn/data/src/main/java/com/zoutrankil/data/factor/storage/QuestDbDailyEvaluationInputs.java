package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.engine.*;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bounded read-only price/calendar snapshot shared by all factor and pool evaluations. */
public final class QuestDbDailyEvaluationInputs {
    private final JdbcTemplate jdbc;
    public QuestDbDailyEvaluationInputs(JdbcTemplate jdbc) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(60);
    }
    public record Snapshot(DailyMetricCalendar.Pair dates, List<DailyFactorLabels.Price> prices,
            List<DailyFactorLabels.Suspension> suspensions, List<Map<String,Object>> sourceState) {
        public Snapshot {
            prices=List.copyOf(prices);
            suspensions=List.copyOf(suspensions);
            sourceState=sourceState.stream()
                    .map(row->Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        }
    }
    public Snapshot read(LocalDate signal, LocalDate asOf) {
        if(signal==null || asOf==null || !asOf.isAfter(signal)
                || java.time.temporal.ChronoUnit.DAYS.between(signal,asOf)>40)
            throw new IllegalArgumentException("Evaluation requires signal < asOf within 40 days");
        var before=state();
        var calendar=jdbc.query("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=? AND cal_date<? ORDER BY cal_date LIMIT 42",
                (rs,n)-> {
                    Object flag=rs.getObject(2);
                    if(!(flag instanceof Number value) || !(value.doubleValue()==0 || value.doubleValue()==1))
                        throw new IllegalStateException("Official calendar requires an explicit 0/1 open flag");
                    return new DailyMetricCalendar.Day("SSE",rs.getDate(1).toLocalDate(),value.intValue()==1);
                },
                utcDay(signal),utcDay(asOf.plusDays(1)));
        var pair=DailyMetricCalendar.maturePairs(calendar,signal,asOf).stream()
                .filter(p->p.signal().equals(signal)).findFirst()
                .orElseThrow(()->new IllegalStateException("Signal has no mature adjacent official session"));
        var raw=jdbc.query("SELECT trade_date,ts_code,close_hfq FROM stk_factor WHERE trade_date IN (?,?) ORDER BY trade_date,ts_code LIMIT 20001",
                (rs,n)->new StockCodeAliases.Row(rs.getString(2),rs.getDate(1).toLocalDate(),
                        new double[]{rs.getObject(3)==null?Double.NaN:rs.getDouble(3)}),
                utcDay(signal),utcDay(pair.maturity()));
        if(raw.size()>20000)throw new IllegalStateException("Evaluation price row budget exceeded");
        var aliases=StockCodeAliases.load();
        var prices=aliases.normalize(raw,new int[]{0}).stream()
                .map(r->new DailyFactorLabels.Price(r.date(),r.symbol(),r.values()[0])).toList();
        if(prices.stream().noneMatch(p->p.date().equals(signal)) || prices.stream().noneMatch(p->p.date().equals(pair.maturity())))
            throw new IllegalStateException("Signal or maturity adjusted prices missing: "+pair);
        var status=jdbc.query("SELECT timestamp,ts_code,is_suspended FROM stk_suspend WHERE timestamp>=? AND timestamp<? ORDER BY ts_code LIMIT 10001",
                (rs,n)->new StockCodeAliases.Row(rs.getString(2),rs.getDate(1).toLocalDate(),
                        new double[]{rs.getObject(3)==null?Double.NaN:rs.getDouble(3)}),
                utcDay(pair.maturity()),utcDay(pair.maturity().plusDays(1)));
        if(status.size()>10000)throw new IllegalStateException("Evaluation suspension row budget exceeded");
        var suspensions=aliases.normalize(status,new int[]{0}).stream()
                .map(r->new DailyFactorLabels.Suspension(r.date(),r.symbol(),r.values()[0])).toList();
        if(!before.equals(state()))throw new IllegalStateException("Evaluation sources changed during read");
        return new Snapshot(pair,prices,suspensions,before);
    }
    public void requireUnchanged(Snapshot snapshot) {
        if(!snapshot.sourceState().equals(state()))throw new IllegalStateException("Evaluation sources changed after read");
    }
    private static String utcDay(LocalDate date) { return date + "T00:00:00.000000Z"; }
    private List<Map<String,Object>> state() {
        for(String table:List.of("exchange_calendar","stk_factor","stk_suspend"))
            if(!QuestDbWriteChecks.walSettled(jdbc,table))throw new IllegalStateException("Evaluation source WAL unsettled: "+table);
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name IN ('exchange_calendar','stk_factor','stk_suspend') ORDER BY table_name");
        if(rows.size()!=3)throw new IllegalStateException("Evaluation source table missing");
        return rows;
    }
}
