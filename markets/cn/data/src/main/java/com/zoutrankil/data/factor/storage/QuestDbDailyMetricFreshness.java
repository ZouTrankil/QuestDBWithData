package com.zoutrankil.data.factor.storage;

import com.zoutrankil.data.factor.application.DailyMetricFreshness;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only formal metric and complete SSE calendar snapshot for one declared signal date. */
public final class QuestDbDailyMetricFreshness {
    private final JdbcTemplate jdbc;
    public QuestDbDailyMetricFreshness(JdbcTemplate template) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(template.getDataSource()));jdbc.setQueryTimeout(60);
    }
    public record Request(LocalDate signalDate,LocalDate asOf,String labelDefinition,
                          List<DailyMetricFreshness.Key> expected) {
        public Request {
            Objects.requireNonNull(asOf);expected=List.copyOf(expected);
            if(signalDate!=null&&(asOf.isBefore(signalDate)||ChronoUnit.DAYS.between(signalDate,asOf)>366)
                    ||expected.isEmpty()||expected.size()>10000||labelDefinition==null||labelDefinition.isBlank())
                throw new IllegalArgumentException("Explicit bounded date, label and metric matrix required");
        }
    }
    public record Result(LocalDate signalDate,DailyMetricFreshness.Report assessment,boolean calendarComplete,
            int physicalRows,List<Map<String,Object>> sourceState) {}
    public Result inspect(Request request) {
        if(request.signalDate()==null) {
            var planner=new QuestDbDailyMetricPlan(jdbc);
            var plan=planner.plan(null,request.asOf());
            var result=inspect(new Request(plan.pairs().getLast().signal(),request.asOf(),
                    request.labelDefinition(),request.expected()));
            planner.requireUnchanged(plan);
            return result;
        }
        var before=state();
        var rows=new QuestDbDailyMetrics(jdbc).read(request.signalDate()).stream()
                .filter(row->request.labelDefinition().equals(row.labelDefinition())
                        &&Integer.valueOf(1).equals(row.horizon())&&"base_ic".equals(row.evaluationProfile())).toList();
        var calendar=new TreeMap<LocalDate,Boolean>();
        jdbc.query("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>=? AND cal_date<? ORDER BY cal_date LIMIT 369",
                rs->{
                    LocalDate day=rs.getDate(1).toLocalDate();Object flag=rs.getObject(2);
                    if(!(flag instanceof Number number)||(number.doubleValue()!=0&&number.doubleValue()!=1)
                            ||calendar.putIfAbsent(day,number.doubleValue()==1)!=null)
                        throw new IllegalStateException("Invalid or duplicate SSE calendar day");
                },request.signalDate()+"T00:00:00.000000Z",request.asOf().plusDays(1)+"T00:00:00.000000Z");
        boolean complete=true;
        for(LocalDate day=request.signalDate();!day.isAfter(request.asOf());day=day.plusDays(1))
            complete&=calendar.containsKey(day);
        if(!before.equals(state()))throw new IllegalStateException("Freshness inputs changed during read");
        var open=calendar.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        var assessment=DailyMetricFreshness.assess(rows,request.expected(),request.asOf(),null,
                complete?request.asOf():null,open);
        return new Result(request.signalDate(),assessment,complete,rows.size(),before);
    }
    private List<Map<String,Object>> state() {
        for(String table:List.of("factor_daily_metric","exchange_calendar"))
            if(!QuestDbWriteChecks.walSettled(jdbc,table))throw new IllegalStateException("Freshness source WAL unsettled: "+table);
        var rows=jdbc.queryForList("SELECT id,table_name,directoryName,table_txn,wal_txn,table_suspended FROM tables() WHERE table_name IN ('factor_daily_metric','exchange_calendar') ORDER BY table_name");
        if(rows.size()!=2)throw new IllegalStateException("Formal freshness source missing");
        return rows;
    }
}
