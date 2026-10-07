package com.zoutrankil.data.margin.domain;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.policy.StaticTargetIdentity;
/** Stable endpoint-aware identity independent of the replaced physical generation. */
public final class MarginAllTargetIdentity {
    private MarginAllTargetIdentity() {}
    public static String logical(String jdbcUrl,String table) {DatasetDefinition.identifier(table);return StaticTargetIdentity.identify(jdbcUrl,table,0L,"d028-logical-target-v1");}
}
