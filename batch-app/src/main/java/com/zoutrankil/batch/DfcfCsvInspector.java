package com.zoutrankil.batch;

import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Streaming structural validator for the three raw DFCF CSV tables; it does not invent feature values. */
final class DfcfCsvInspector {
    static final Charset ENCODING=Charset.forName("GB18030");
    private static final int MAX_COLUMNS=256, MAX_RECORD_CHARS=2_000_000;
    private static final List<String> DEAL=List.of("自然日","时间","成交编号","成交价格","成交数量","BS标志","叫买序号","叫卖序号");
    private static final List<String> ORDER=List.of("自然日","时间","交易所委托号","委托类型","委托代码","委托价格","委托数量");
    private static final List<String> SNAPSHOT= snapshotColumns();

    Inspection inspect(String memberName,InputStream source,LocalDate tradeDate,long maxBytes) throws IOException {
        String canonical=memberName.replace('\\','/'); String[] path=canonical.split("/");
        if(path.length!=3) throw new IOException("Invalid DFCF member path");
        String filename=path[2]; List<String> required=switch(filename) {
            case "逐笔成交.csv" -> DEAL; case "逐笔委托.csv" -> ORDER; case "行情.csv" -> SNAPSHOT;
            default -> throw new IOException("Unsupported DFCF CSV member");
        };
        var stream=new DigestCountingInputStream(source,maxBytes);
        try(var reader=new PushbackReader(new BufferedReader(new InputStreamReader(stream,ENCODING),64*1024),1)) {
            List<String> header=readRecord(reader);
            if(header==null) throw new IOException("DFCF CSV is empty: "+filename);
            if(!header.isEmpty() && header.getFirst().startsWith("\uFEFF")) header.set(0,header.getFirst().substring(1));
            if(new HashSet<>(header).size()!=header.size()) throw new IOException("DFCF CSV contains duplicate headers: "+filename);
            List<String> missing=required.stream().filter(column -> !header.contains(column)).toList();
            if(!missing.isEmpty()) throw new IOException("DFCF CSV is missing required fields in "+filename+": "+missing);
            int dateIndex=header.indexOf("自然日"); long rows=0,dateMismatches=0;
            for(List<String> row;(row=readRecord(reader))!=null;) {
                if(row.size()!=header.size()) throw new IOException("DFCF CSV row width differs from header: "+filename);
                rows++;
                if(!row.get(dateIndex).strip().equals(tradeDate.toString().replace("-",""))) dateMismatches++;
            }
            return new Inspection(canonical,path[1],filename,rows,stream.count(),HexFormat.of().formatHex(stream.digest()),dateMismatches);
        }
    }

    private static List<String> snapshotColumns() {
        var columns=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
        for(int i=1;i<=10;i++) columns.add("申买价"+i);
        for(int i=1;i<=10;i++) columns.add("申卖价"+i);
        for(int i=1;i<=10;i++) columns.add("申买量"+i);
        for(int i=1;i<=10;i++) columns.add("申卖量"+i);
        return List.copyOf(columns);
    }
    static List<String> readRecord(PushbackReader in) throws IOException {
        var row=new ArrayList<String>(); var field=new StringBuilder(); boolean quoted=false,closedQuote=false,any=false; int recordChars=0;
        while(true) {
            int value=in.read();
            if(value<0) {
                if(quoted) throw new IOException("Unterminated quoted CSV field");
                if(!any && row.isEmpty() && field.isEmpty()) return null;
                addField(row,field); return row;
            }
            any=true; char c=(char)value;
            if(++recordChars>MAX_RECORD_CHARS) throw new IOException("DFCF CSV record exceeds configured character bound");
            if(quoted) {
                if(c=='"') {
                    int next=in.read();
                    if(next=='"') field.append('"');
                    else { quoted=false; closedQuote=true; if(next>=0) in.unread(next); }
                } else field.append(c);
                continue;
            }
            if(closedQuote) {
                if(c==',') { addField(row,field); field.setLength(0); closedQuote=false; continue; }
                if(c=='\r' || c=='\n') { consumeLf(in,c); addField(row,field); return row; }
                throw new IOException("Unexpected character after closing quote in CSV");
            }
            if(c=='"') {
                if(field.length()!=0) throw new IOException("Quote inside unquoted CSV field");
                quoted=true;
            } else if(c==',') { addField(row,field); field.setLength(0); }
            else if(c=='\r' || c=='\n') { consumeLf(in,c); addField(row,field); return row; }
            else field.append(c);
        }
    }
    private static void addField(List<String> row,StringBuilder field) throws IOException {
        if(row.size()>=MAX_COLUMNS) throw new IOException("DFCF CSV exceeds configured column-count bound");
        row.add(field.toString());
    }
    private static void consumeLf(PushbackReader in,char c) throws IOException { if(c=='\r') { int next=in.read(); if(next>=0 && next!='\n') in.unread(next); } }
    record Inspection(String memberPath,String symbol,String fileName,long rowCount,long rawBytes,String sha256,long tradeDateMismatchRows) {}

    private static final class DigestCountingInputStream extends FilterInputStream {
        private final MessageDigest digest; private final long max; private long count;
        DigestCountingInputStream(InputStream in,long max) { super(in); this.max=max; try { digest=MessageDigest.getInstance("SHA-256"); } catch(Exception e) { throw new AssertionError(e); } }
        @Override public int read() throws IOException { int b=super.read(); if(b>=0) { add(1); digest.update((byte)b); } return b; }
        @Override public int read(byte[] b,int off,int len) throws IOException { int n=super.read(b,off,len); if(n>0) { add(n); digest.update(b,off,n); } return n; }
        private void add(int n) throws IOException { count+=n; if(count>max) throw new IOException("Expanded archive exceeds configured bound"); }
        long count() { return count; }
        byte[] digest() { return digest.digest(); }
    }
}
