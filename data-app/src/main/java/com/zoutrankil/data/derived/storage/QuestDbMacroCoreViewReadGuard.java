package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.DatasetReadQuery;
import com.zoutrankil.data.domain.MacroCoreMonthlyDataset;
import com.zoutrankil.data.domain.MacroCoreMonthlyViewDataset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** D105 only: the ordinary monthly alias is pinned to its exact definition and D104 generation. */
final class QuestDbMacroCoreViewReadGuard {
    private QuestDbMacroCoreViewReadGuard() {}
    private static final Map<String,String> SOURCES = Map.of(
            "v_macro_core_monthly", "macro_core_monthly",
            "java_d105_v_macro_core_monthly_acceptance", "java_d104_macro_core_monthly_acceptance");
    private static final Pattern SQL_TOKEN = Pattern.compile(
            "\"([A-Za-z_][A-Za-z0-9_]*)\"|[A-Za-z_][A-Za-z0-9_]*|[()*]");

    static boolean applies(DatasetDefinition definition) {
        return "v_macro_core_monthly".equals(definition.datasetId()) || SOURCES.containsKey(definition.objectName());
    }
    static void validate(DatasetDefinition definition, DatasetReadQuery query) {
        if (!applies(definition)) return;
        requireDefinition(definition);
        if (!query.columns().equals(definition.storageColumns()) || query.pageSize()>12
                || !Set.of("month").containsAll(query.equalities().keySet()))
            throw new IllegalArgumentException("D105 requires the complete nine-column monthly projection and page size at most 12");
        if (query.equalities().containsKey("month")) requireMonth(query.equalities().get("month"));
        if (query.rangeColumn()!=null) {
            if (!"month".equals(query.rangeColumn()) || !query.equalities().isEmpty())
                throw new IllegalArgumentException("D105 requires an unfiltered month range");
            LocalDate from=requireMonth(query.fromInclusive()), to=requireMonth(query.toExclusive());
            if (!from.isBefore(to) || ChronoUnit.MONTHS.between(from,to)>12)
                throw new IllegalArgumentException("D105 requires an increasing range of at most 12 months");
        } else if (!query.equalities().containsKey("month")) {
            throw new IllegalArgumentException("D105 exact month or bounded monthly range required");
        }
        if (query.cursor()!=null && (query.cursor().keyValues().size()!=1
                || requireMonth(query.cursor().keyValues().getFirst())==null))
            throw new IllegalArgumentException("D105 complete month cursor required");
    }
    private static void requireDefinition(DatasetDefinition definition) {
        if (!SOURCES.containsKey(definition.objectName())
                || !definition.equals(MacroCoreMonthlyViewDataset.definition(definition.objectName())))
            throw new IllegalArgumentException("D105 requires its exact registered ordinary VIEW and single logical base dependency");
    }
    private static LocalDate requireMonth(Object value) {
        if (!(value instanceof LocalDate date) || date.getDayOfMonth()!=1 || date.getYear()<1 || date.getYear()>9999)
            throw new IllegalArgumentException("D105 month uses the first calendar day LocalDate carrier");
        return date;
    }
    static String version(JdbcTemplate jdbc, DatasetDefinition definition) {
        requireDefinition(definition);
        cancelled();
        String base=SOURCES.get(definition.objectName());
        ViewState before=viewState(jdbc,definition,base);
        cancelled();
        String baseBefore=QuestDbMacroCoreReadGuard.version(jdbc,MacroCoreMonthlyDataset.definition(base));
        ViewState after=viewState(jdbc,definition,base);
        cancelled();
        String baseAfter=QuestDbMacroCoreReadGuard.version(jdbc,MacroCoreMonthlyDataset.definition(base));
        if (!before.equals(after) || !baseBefore.equals(baseAfter))
            throw new IllegalStateException("D105 view or base generation changed while checking the complete dependency snapshot");
        cancelled();
        return "view:"+definition.objectName()+":directory:"+before.alias().directory()
                +":id:"+before.alias().id()+":definition:"+before.alias().definitionHash()+":body:"+before.alias().bodyHash()
                +":status-updated:"+before.alias().statusUpdated()+":schema:"+hash(before.schema().toString())
                +":source:"+baseBefore;
    }
    private record AliasState(long id,String directory,String definitionHash,String bodyHash,String statusUpdated) {}
    private record ColumnState(String name,String type,boolean designated,boolean upsertKey) {}
    private record ViewState(AliasState alias,List<ColumnState> schema) {}
    private static ViewState viewState(JdbcTemplate jdbc,DatasetDefinition definition,String base) {
        AliasState before=alias(jdbc,definition.objectName(),base);
        // Exact native column flags were confirmed by the D105 formal metadata preflight; they do not grant VIEW writes.
        List<ColumnState> schema=query(jdbc,"SELECT \"column\",\"type\",designated,upsertKey FROM table_columns('"
                +definition.objectName()+"') LIMIT 10",10,rs->{
            var columns=new ArrayList<ColumnState>();
            var names=new HashSet<String>();
            while(rs.next()) {
                String name=rs.getString("column"),type=rs.getString("type");
                if(name==null || type==null || !names.add(name))
                    throw new IllegalStateException("D105 view schema contains a missing or duplicate column");
                columns.add(new ColumnState(name,type,flag(rs,"designated"),flag(rs,"upsertKey")));
            }
            if(columns.size()!=definition.columns().size())
                throw new IllegalStateException("D105 view schema must contain all nine and only nine original fields");
            for(int i=0;i<columns.size();i++) {
                var actual=columns.get(i);var expected=definition.columns().get(i);
                if(!actual.name().equals(expected.storageName()) || !actual.type().equals(expected.storageType().name())
                        || actual.designated()!=actual.name().equals("month") || actual.upsertKey())
                    throw new IllegalStateException("D105 view complete column order, type or native timestamp/key flags differ");
            }
            return List.copyOf(columns);
        });
        if(!before.equals(alias(jdbc,definition.objectName(),base)))
            throw new IllegalStateException("D105 view changed while checking its native schema");
        return new ViewState(before,schema);
    }
    private static AliasState alias(JdbcTemplate jdbc,String view,String base) {
        return query(jdbc,"SELECT v.view_name,v.view_sql,v.view_table_dir_name,v.view_status,v.invalidation_reason,"
                +"v.view_status_update_time,t.id AS view_id,t.directoryName AS view_directory,"
                +"t.matView AS view_mat_view,t.partitionBy AS view_partition,t.dedup AS view_dedup,"
                +"t.designatedTimestamp AS view_timestamp FROM views() v JOIN tables() t ON t.table_name=v.view_name "
                +"WHERE v.view_name='"+view+"' LIMIT 2",2,rs->{
            if(!rs.next())throw new IllegalStateException("D105 ordinary view is absent");
            long id=counter(rs,"view_id");
            String sql=rs.getString("view_sql"), directory=rs.getString("view_table_dir_name"),
                    updated=rs.getString("view_status_update_time"), reason=rs.getString("invalidation_reason");
            List<String> tokens=sqlTokens(sql,base);
            if(!view.equals(rs.getString("view_name")) || !"valid".equals(rs.getString("view_status"))
                    || directory==null || directory.isBlank() || updated==null || updated.isBlank()
                    || reason!=null && !reason.isBlank() || !List.of("select","*","from",base).equals(tokens)
                    || !directory.equals(rs.getString("view_directory")) || flag(rs,"view_mat_view")
                    || !"N/A".equals(rs.getString("view_partition")) || flag(rs,"view_dedup")
                    || !"month".equals(rs.getString("view_timestamp")))
                throw new IllegalStateException("D105 ordinary view is invalid or differs from the fixed SELECT * base identity");
            var result=new AliasState(id,directory,hash(String.join(" ",tokens)),hash(sql),updated);
            if(rs.next())throw new IllegalStateException("Duplicate D105 ordinary view metadata");
            return result;
        });
    }
    /** Only original outer parentheses, lexical case/whitespace and quoting the exact base identifier are accepted. */
    private static List<String> sqlTokens(String sql,String base) {
        if(sql==null)return null;
        var tokens=new ArrayList<String>();
        for(int pos=0;pos<sql.length();) {
            if(Character.isWhitespace(sql.charAt(pos))){pos++;continue;}
            var match=SQL_TOKEN.matcher(sql).region(pos,sql.length());
            if(!match.lookingAt())return null;
            String token=(match.group(1)!=null?match.group(1):match.group()).toLowerCase(Locale.ROOT);
            if(match.group(1)!=null && !base.equals(token))return null;
            tokens.add(token);pos=match.end();
        }
        if(tokens.size()==6 && tokens.getFirst().equals("(") && tokens.getLast().equals(")"))
            tokens=new ArrayList<>(tokens.subList(1,5));
        return List.copyOf(tokens);
    }
    private static long counter(ResultSet rs,String column)throws SQLException {
        long result=rs.getLong(column);
        if(rs.wasNull() || result<0)throw new IllegalStateException("D105 missing or negative native view identity: "+column);
        return result;
    }
    private static boolean flag(ResultSet rs,String column)throws SQLException {
        boolean result=rs.getBoolean(column);
        if(rs.wasNull())throw new IllegalStateException("D105 missing native column status: "+column);
        return result;
    }
    private static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new CancellationException("D105 bounded read cancelled before metadata query");
    }
    private static <T>T query(JdbcTemplate jdbc,String sql,int limit,ResultSetExtractor<T> extractor) {
        cancelled();
        return jdbc.query(connection->{
            cancelled();
            var statement=connection.prepareStatement(sql);
            statement.setQueryTimeout(20);statement.setMaxRows(limit);statement.setFetchSize(limit);
            return statement;
        },extractor);
    }
}
