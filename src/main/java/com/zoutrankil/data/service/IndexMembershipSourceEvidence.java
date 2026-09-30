package com.zoutrankil.data.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.mapper.IndexMembershipMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Reconstruct all source-owned values from hashed raw responses before recovery. */
final class IndexMembershipSourceEvidence {
    private IndexMembershipSourceEvidence() {}
    static byte[] bounded(Path path,int maximum) throws Exception {
        try(var in=Files.newInputStream(path)) {
            byte[] bytes=in.readNBytes(maximum+1);
            if(bytes.length>maximum) throw new IllegalArgumentException("Membership evidence exceeds bound");
            return bytes;
        }
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static void verify(SyncJobRunner.Page<IndexMembership> source,IndexMembershipSource.Scope scope,Path folder) throws Exception {
        var json=JobDefinitionJson.mapper();Path receipt=owned(source.responseEvidence(),folder);
        byte[] bytes=bounded(receipt,8*1024*1024);
        if(!hash(bytes).equals(source.sourceFingerprint()) || source.cursor()!=null)
            throw new IllegalStateException("Membership source receipt changed");
        var proof=json.readTree(bytes);
        if(!proof.path("complete").asBoolean() || !proof.path("scope").equals(json.valueToTree(scope))
                || !proof.path("requests").isArray()) throw new IllegalStateException("Incomplete frozen membership scope");
        var modes=switch(scope.selection()) {case CURRENT->List.of("Y");case HISTORICAL->List.of("N");case BOTH->List.of("Y","N");};
        if(proof.path("requests").size()!=modes.size()) throw new IllegalStateException("Missing membership source response");
        Instant observed=Instant.parse(proof.path("observedAt").asText());
        if(observed.getNano()%1000!=0) throw new IllegalStateException("Membership observation precision changed");
        var mapper=new IndexMembershipMapper();var rows=new ArrayList<IndexMembership>();var keys=new HashSet<IndexMembership.Key>();
        for(int i=0;i<modes.size();i++) {
            var request=proof.path("requests").get(i);String mode=modes.get(i);
            byte[] part=bounded(owned(request.path("receipt").asText(),folder),4*1024*1024);
            if(!hash(part).equals(request.path("sha256").asText())) throw new IllegalStateException("Membership part hash changed");
            var response=json.readTree(part);var raw=response.path("rows");
            if(!response.equals(request.path("response")) || !response.path("endpoint").asText().equals("index_member_all")
                    || !response.path("parameters").equals(json.valueToTree(Map.of("l2_code",scope.l2Code(),"is_new",mode)))
                    || !raw.isArray() || raw.size()>=2000 || response.path("completion").path("pages").asInt()!=1
                    || response.path("completion").path("rows").asInt()!=raw.size())
                throw new IllegalStateException("Membership response scope or completeness changed");
            for(var r:raw) {
                Map<String,JsonNode> fields=json.convertValue(r,new com.fasterxml.jackson.core.type.TypeReference<>() {});
                var dto=IndexMembershipSource.decode(fields);
                if(!mode.equals(dto.isNew())) throw new IllegalStateException("Membership Y/N response changed");
                var row=mapper.fromSource(dto,scope.l2Code(),scope.industryName(),observed);
                if(!keys.add(row.key())) throw new IllegalStateException("Duplicate frozen member period");
                rows.add(row);
            }
        }
        if(!rows.equals(source.rows()) || !proof.path("rows").equals(json.valueToTree(rows)))
            throw new IllegalStateException("Frozen typed membership values differ from raw provider responses");
    }
    private static Path owned(String value,Path folder) throws Exception {
        Path path=Path.of(value).toAbsolutePath().normalize();
        if(!path.getParent().equals(folder.toAbsolutePath().normalize()) || !path.toRealPath().getParent().equals(folder.toRealPath()))
            throw new IllegalArgumentException("Membership evidence is outside owning run folder");
        return path;
    }
}
