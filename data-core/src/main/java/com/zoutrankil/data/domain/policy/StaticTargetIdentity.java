package com.zoutrankil.data.domain.policy;

import com.zoutrankil.data.domain.DatasetDefinition;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Bind static publication evidence to an endpoint and physical table, without storing credentials. */
public final class StaticTargetIdentity {
    private StaticTargetIdentity() {}

    public static String identify(String jdbcUrl, String table, long id, String directory) {
        DatasetDefinition.identifier(table);
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("Explicit PGWire endpoint required for static target identity");
        try {
            // Authentication and transport options do not identify a different database.
            var address = URI.create(jdbcUrl.substring(5).split("\\?", 2)[0]);
            if (address.getHost() == null || address.getPath() == null || address.getPath().length() < 2)
                throw new IllegalArgumentException("Explicit host and database required");
            String endpoint = address.getHost().toLowerCase(Locale.ROOT) + ":"
                    + (address.getPort() < 0 ? 5432 : address.getPort()) + address.getRawPath();
            String frozen = endpoint + "\n" + table + "\n" + id + "\n" + directory;
            return "static-v2-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(frozen.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) {
            // Do not include a JDBC URL or parsing exception that might contain authentication material.
            throw new IllegalArgumentException("Cannot bind static target to an explicit PGWire endpoint");
        }
    }
}
