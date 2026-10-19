package com.zoutrankil.data.derived.storage;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.port.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Lazy target construction preserves blank-configuration startup and per-attempt writer state. */
public final class QuestDbMacroCoreMonthlyTarget implements MacroCoreMonthlyTarget {
    private final JdbcTemplate jdbc;private final QuestDbProperties properties;private final String table;
    public QuestDbMacroCoreMonthlyTarget(JdbcTemplate jdbc,QuestDbProperties properties,String table){this.jdbc=jdbc;this.properties=properties;this.table=table==null?"":table.trim();}
    public String table(){return table;}
    public MacroCoreMonthlyWriteSession newWriter(){return new MacroCoreMonthlyWritePort(jdbc,properties,table);}
}
