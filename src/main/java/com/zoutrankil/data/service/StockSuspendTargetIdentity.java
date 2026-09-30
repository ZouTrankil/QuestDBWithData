package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** D011 logical target identity survives a journaled physical table replacement. */
public final class StockSuspendTargetIdentity {
    private StockSuspendTargetIdentity() {}

    public static String logical(JdbcTemplate jdbc, String table) {
        DatasetDefinition.identifier(table);
        Objects.requireNonNull(jdbc);
        String url = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        try {
            if (url == null || !url.startsWith("jdbc:postgresql://"))
                throw new IllegalArgumentException("Explicit PGWire endpoint required");
            URI address = URI.create(url.substring(5).split("\\?", 2)[0]);
            if (address.getHost() == null || address.getPath() == null || address.getPath().length() < 2)
                throw new IllegalArgumentException("Explicit host and database required");
            String endpoint = address.getHost().toLowerCase(Locale.ROOT) + ":"
                    + (address.getPort() < 0 ? 5432 : address.getPort()) + address.getRawPath();
            String value = "stk_suspend-logical-v1\n" + endpoint + "\n" + table;
            return "static-v2-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot bind D011 logical target to an explicit PGWire endpoint");
        }
    }
}
