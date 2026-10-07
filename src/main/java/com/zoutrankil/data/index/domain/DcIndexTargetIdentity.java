package com.zoutrankil.data.index.domain;


import com.zoutrankil.data.domain.DatasetDefinition;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Stable database endpoint/table identity; a D023 journal separately proves physical generations. */
public final class DcIndexTargetIdentity {
    private DcIndexTargetIdentity(){}
    public static String logical(String url,String table){
        DatasetDefinition.identifier(table);
        try{if(url==null||!url.startsWith("jdbc:postgresql://"))throw new IllegalArgumentException();URI uri=URI.create(url.substring(5).split("\\?",2)[0]);
            if(uri.getHost()==null||uri.getPath()==null||uri.getPath().length()<2)throw new IllegalArgumentException();
            String endpoint=uri.getHost().toLowerCase(Locale.ROOT)+":"+(uri.getPort()<0?5432:uri.getPort())+uri.getRawPath();
            String raw="dc_index-logical-v1\n"+endpoint+"\n"+table;
            return "static-v2-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception failure){throw new IllegalArgumentException("Cannot bind D023 logical target to explicit PGWire endpoint");}
    }
}
