package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.MarginAllStorage;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable logical D028 identity, separate from the physical generation replaced by publication. */
public final class MarginAllTargetIdentity {
    private MarginAllTargetIdentity(){}
    public static String logical(JdbcTemplate jdbc,String table){DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbc,table,0L,"d028-logical-target-v1");}
    public static String physical(JdbcTemplate jdbc,String table,MarginAllStorage.Identity identity){return MarginAllStorage.physicalTargetId(jdbc,table,identity);}
}
