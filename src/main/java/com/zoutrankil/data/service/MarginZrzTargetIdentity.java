package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.MarginZrzStorage;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable logical D031 identity, separate from the physical generation replaced by publication. */
public final class MarginZrzTargetIdentity {
    private MarginZrzTargetIdentity(){}
    public static String logical(JdbcTemplate jdbc,String table){DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbc,table,0L,"d031-logical-target-v1");}
    public static String physical(JdbcTemplate jdbc,String table,MarginZrzStorage.Identity identity){return MarginZrzStorage.physicalTargetId(jdbc,table,identity);}
}
