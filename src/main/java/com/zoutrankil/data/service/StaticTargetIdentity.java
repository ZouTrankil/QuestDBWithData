package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.repository.StockDetailInfoStorage;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Bind static publication evidence to its database endpoint and physical table, without storing credentials. */
public final class StaticTargetIdentity {
    private StaticTargetIdentity() {}
    public static String identify(JdbcTemplate jdbc,String table,StockDetailInfoStorage.Identity identity) {
        return identify(jdbc,table,identity.id(),identity.directory());
    }
    public static String identify(JdbcTemplate jdbc,String table,long id,String directory) {
        String url=jdbc.execute((ConnectionCallback<String>)c->c.getMetaData().getURL());
        return identify(url,table,new StockDetailInfoStorage.Identity(id,directory));
    }
    static String identify(String jdbcUrl,String table,StockDetailInfoStorage.Identity identity) {
        DatasetDefinition.identifier(table);Objects.requireNonNull(identity);
        if(jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("Explicit PGWire endpoint required for static target identity");
        try {
            // Authentication and transport options do not identify a different database.
            var address=URI.create(jdbcUrl.substring(5).split("\\?",2)[0]);
            if(address.getHost()==null || address.getPath()==null || address.getPath().length()<2)
                throw new IllegalArgumentException("Explicit host and database required");
            String endpoint=address.getHost().toLowerCase(Locale.ROOT)+":"
                    +(address.getPort()<0?5432:address.getPort())+address.getRawPath();
            String frozen=endpoint+"\n"+table+"\n"+identity.id()+"\n"+identity.directory();
            return "static-v2-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(frozen.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception invalid) {
            // Do not include a JDBC URL or parsing exception that might contain authentication material.
            throw new IllegalArgumentException("Cannot bind static target to an explicit PGWire endpoint");
        }
    }
}
