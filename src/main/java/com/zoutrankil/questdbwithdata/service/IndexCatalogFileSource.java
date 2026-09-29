package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.client.dto.IndexCatalogSourceRow;
import com.zoutrankil.questdbwithdata.domain.IndexCatalogEntry;
import com.zoutrankil.questdbwithdata.mapper.IndexCatalogMapper;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Finite UTF-8 CSV source; validate the complete file before admitting any write. */
public final class IndexCatalogFileSource {
    public static final int MAX_ROWS=5000,MAX_BYTES=8*1024*1024,MAX_FIELD_CHARS=32768;
    public static final List<String> HEADERS=List.of("指数代码","指数简称","指数全称","基日","基点","指数系列",
            "样本数量","最新收盘","近一个月收益率","资产类别","指数热点","指数币种","合作指数","跟踪产品","指数合规","指数类别","发布时间");
    public record Input(String path,String sha256,int bytes,List<IndexCatalogEntry> rows) {
        public Input { rows=List.copyOf(rows); }
    }
    static byte[] readBounded(Path path,int limit) throws java.io.IOException {
        if(limit<1 || limit>64*1024*1024) throw new IllegalArgumentException("Bounded catalog input limit required");
        byte[] bytes;
        try(var input=Files.newInputStream(path)) { bytes=input.readNBytes(limit+1); }
        if(bytes.length>limit) throw new IllegalArgumentException("Catalog input exceeds byte bound");
        return bytes;
    }
    public Input read(Path path,Instant importedAt,BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(importedAt);check(cancelled);byte[] bytes;
        try(var input=Files.newInputStream(path)) { bytes=input.readNBytes(MAX_BYTES+1); }
        if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("Catalog file exceeds byte bound");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        var records=parse(text,cancelled);
        if(records.isEmpty() || !records.getFirst().equals(HEADERS))
            throw new IllegalArgumentException("Exact ordered 17-column catalog header required");
        var mapper=new IndexCatalogMapper();var rows=new ArrayList<IndexCatalogEntry>();var keys=new HashSet<String>();
        for(int i=1;i<records.size();i++) {
            check(cancelled);var r=records.get(i);
            var row=mapper.fromSource(new IndexCatalogSourceRow(r.get(0),r.get(1),r.get(2),r.get(3),r.get(4),
                    r.get(5),r.get(6),r.get(7),r.get(8),r.get(9),r.get(10),r.get(11),r.get(12),r.get(13),
                    r.get(14),r.get(15),r.get(16)),importedAt);
            if(!keys.add(row.indexCode())) throw new IllegalArgumentException("Duplicate normalized catalog identity");
            rows.add(row);
        }
        check(cancelled);
        return new Input(path.toAbsolutePath().normalize().toString(),HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length,rows);
    }
    private static List<List<String>> parse(String text,BooleanSupplier cancelled) {
        var result=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();
        boolean quoted=false,closed=false,pending=false;
        for(int i=0;i<text.length();i++) {
            if(i%4096==0) check(cancelled);char c=text.charAt(i);pending=true;
            if(quoted) {
                if(c=='"') {
                    if(i+1<text.length() && text.charAt(i+1)=='"') { field.append('"');i++; }
                    else { quoted=false;closed=true; }
                } else field.append(c);
            } else if(c==',' || c=='\r' || c=='\n') {
                row.add(field.toString());field.setLength(0);closed=false;
                if(row.size()>HEADERS.size()) throw new IllegalArgumentException("Too many catalog columns");
                if(c!=',') {
                    complete(result,row);row=new ArrayList<>();pending=false;
                    if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n') i++;
                }
            } else if(c=='"' && field.isEmpty() && !closed) quoted=true;
            else {
                if(c=='"' || closed) throw new IllegalArgumentException("Malformed CSV quoting");
                field.append(c);
            }
            if(field.length()>MAX_FIELD_CHARS) throw new IllegalArgumentException("Catalog field exceeds bound");
        }
        if(quoted) throw new IllegalArgumentException("Unterminated CSV field");
        if(pending) { row.add(field.toString());complete(result,row); }
        return result;
    }
    private static void complete(List<List<String>> records,List<String> row) {
        if(row.size()!=HEADERS.size()) throw new IllegalArgumentException("Catalog row must contain 17 columns");
        if(records.size()>=MAX_ROWS+1) throw new IllegalArgumentException("Catalog row bound exceeded");
        records.add(List.copyOf(row));
    }
    private static void check(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("Catalog file read cancelled");
    }
}
