package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.DatasetDefinition;
import com.zoutrankil.questdbwithdata.repository.MarginAllStorage;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable logical D028 identity, separate from the physical generation replaced by publication. */
public final class MarginAllTargetIdentity {
    private MarginAllTargetIdentity(){}
    public static String logical(JdbcTemplate jdbc,String table){DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbc,table,0L,"d028-logical-target-v1");}
    public static String physical(JdbcTemplate jdbc,String table,MarginAllStorage.Identity identity){return MarginAllStorage.physicalTargetId(jdbc,table,identity);}
}
