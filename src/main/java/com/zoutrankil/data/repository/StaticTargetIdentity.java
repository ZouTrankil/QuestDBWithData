package com.zoutrankil.data.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read the connected endpoint before applying the static target identity policy. */
public final class StaticTargetIdentity {
    private StaticTargetIdentity() {}

    public static String identify(JdbcTemplate jdbc, String table, long id, String directory) {
        String url = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        return com.zoutrankil.data.domain.policy.StaticTargetIdentity.identify(url, table, id, directory);
    }
}
