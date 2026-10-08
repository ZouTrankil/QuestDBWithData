package com.zoutrankil.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipFile;

/** Bounded ZIP integrity gate. Admission records immutable source identity; it does not parse or ingest features. */
public final class L2ArchiveAdmission {
    private static final Set<String> REQUIRED = Set.of("逐笔成交.csv", "逐笔委托.csv", "行情.csv");
    private static final int MAX_MEMBERS=100_000, MAX_SYMBOLS=20_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcTemplate jdbc;
    private final Path archiveRoot;
    private final Duration settleAge;
    private final long maxArchiveBytes, maxExpandedBytes;
    private final DfcfCsvInspector csvInspector=new DfcfCsvInspector();

    public L2ArchiveAdmission(JdbcTemplate jdbc, Path archiveRoot, Duration settleAge,
                              long maxArchiveBytes, long maxExpandedBytes) throws IOException {
        this.jdbc=Objects.requireNonNull(jdbc); this.archiveRoot=archiveRoot.toAbsolutePath().normalize();
        this.settleAge=Objects.requireNonNull(settleAge); this.maxArchiveBytes=maxArchiveBytes;
        this.maxExpandedBytes=maxExpandedBytes;
        if (settleAge.isNegative() || maxArchiveBytes<1 || maxExpandedBytes<1) throw new IllegalArgumentException("Invalid archive bounds");
        Files.createDirectories(this.archiveRoot);
    }

    public synchronized Admission inspect(Path candidate, java.time.Instant now) throws IOException {
        Path file=candidate.toAbsolutePath().normalize();
        if (!file.startsWith(archiveRoot) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Archive must be a regular file under configured archive root");
        String filename=file.getFileName().toString();
        String stem=filename.replaceFirst("(?i)\\.(7z|zip)$", "");
        if (stem.equals(filename)) throw new IllegalArgumentException("Only .7z and .zip L2 archives are supported");
        var dateMatcher=java.util.regex.Pattern.compile("(?<![0-9])([0-9]{8})(?![0-9])").matcher(stem);
        if(!dateMatcher.find()) throw new IllegalArgumentException("Archive filename must contain a YYYYMMDD date");
        LocalDate tradeDate;
        try { tradeDate=LocalDate.parse(dateMatcher.group(1),DATE); } catch (RuntimeException e) { throw new IllegalArgumentException("Archive filename contains an invalid YYYYMMDD date",e); }
        BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if (before.size()==0 || before.size()>maxArchiveBytes) throw new IllegalArgumentException("Archive size is empty or exceeds configured bound");
        if (now.toEpochMilli()-before.lastModifiedTime().toMillis()<settleAge.toMillis())
            throw new NotStableException("Archive is still inside settle-age window; partial files are not admitted");
        String observedHash=sha256(file);
        var observed=jdbc.query("SELECT size_bytes,modified_millis,content_sha256,observed_at_millis FROM l2_archive_observation WHERE source_path=?", rs ->
                rs.next()?new Object[]{rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getLong(4)}:null,file.toString());
        if(observed==null || (long)observed[0]!=before.size() || (long)observed[1]!=before.lastModifiedTime().toMillis()
                || !observedHash.equals(observed[2])) {
            jdbc.update("INSERT INTO l2_archive_observation(source_path,size_bytes,modified_millis,content_sha256,observed_at_millis) VALUES(?,?,?,?,?) ON CONFLICT(source_path) DO UPDATE SET size_bytes=excluded.size_bytes,modified_millis=excluded.modified_millis,content_sha256=excluded.content_sha256,observed_at_millis=excluded.observed_at_millis",
                    file.toString(),before.size(),before.lastModifiedTime().toMillis(),observedHash,now.toEpochMilli());
            throw new NotStableException("Archive identity has not remained stable for the configured settle-age window");
        }
        if(now.toEpochMilli()-(long)observed[3]<settleAge.toMillis())
            throw new NotStableException("Archive identity has not remained stable for the configured settle-age window");
        Path incoming=archiveRoot.resolve(".incoming"); Files.createDirectories(incoming);
        Path staged=Files.createTempFile(incoming,"l2-archive-",".partial");
        try {
        String hash=copyAndFingerprint(file,staged,before,observedHash,now);
        Scan scan;
        if (filename.toLowerCase(Locale.ROOT).endsWith(".zip")) scan=scanZip(staged,tradeDate);
        else scan=scanSevenZip(staged,tradeDate);
        Path frozen=archiveRoot.resolve("frozen"); Files.createDirectories(frozen);
        Path frozenPath=frozen.resolve(hash+(filename.toLowerCase(Locale.ROOT).endsWith(".zip")?".zip":".7z"));
        if(Files.exists(frozenPath,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(frozenPath,LinkOption.NOFOLLOW_LINKS) || !hash.equals(sha256(frozenPath)))
                throw new IOException("Content-addressed frozen archive conflicts with stored bytes");
            Files.delete(staged);
        } else Files.move(staged,frozenPath,StandardCopyOption.ATOMIC_MOVE);
        List<String> sameContent=jdbc.queryForList("SELECT trade_date FROM l2_archive_ledger WHERE archive_sha256=?",String.class,hash);
        boolean duplicate=!sameContent.isEmpty();
        if(duplicate && !sameContent.getFirst().equals(tradeDate.toString())) throw new IOException("Archive content fingerprint conflicts with a different trading date");
        List<String> previous=duplicate?List.of():jdbc.queryForList("SELECT archive_sha256 FROM l2_archive_ledger WHERE trade_date=? ORDER BY rowid DESC LIMIT 1",String.class,tradeDate.toString());
        String revisionOf=previous.isEmpty()?null:previous.getFirst();
        String status=duplicate?"DUPLICATE":scan.dateMismatches()>0?"DATA_QUALITY_FAILED":revisionOf==null?"INTEGRITY_VERIFIED":"REVISION";
        jdbc.update("INSERT INTO l2_archive_ledger(archive_sha256,source_path,frozen_path,trade_date,archive_size_bytes,archive_modified_millis,member_count,symbol_count,status,revision_of_sha256) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(archive_sha256) DO NOTHING",
                hash,file.toString(),frozenPath.toString(),tradeDate.toString(),before.size(),before.lastModifiedTime().toMillis(),scan.members(),scan.symbols(),status,revisionOf);
        for(DfcfCsvInspector.Inspection member:scan.memberEvidence()) jdbc.update("INSERT INTO l2_archive_member(archive_sha256,member_path,symbol,file_name,row_count,raw_bytes,member_sha256,trade_date_mismatch_rows) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(archive_sha256,member_path) DO NOTHING",
                hash,member.memberPath(),member.symbol(),member.fileName(),member.rowCount(),member.rawBytes(),member.sha256(),member.tradeDateMismatchRows());
        return new Admission(hash,tradeDate,frozenPath,before.size(),scan.members(),scan.symbols(),scan.totalRows(),scan.dateMismatches(),duplicate,scan.kind(),status,revisionOf);
        } finally { Files.deleteIfExists(staged); }
    }

    private String copyAndFingerprint(Path source,Path destination,BasicFileAttributes before,String expectedHash,java.time.Instant now) throws IOException {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        long copied=0;
        try(InputStream in=Files.newInputStream(source); OutputStream out=Files.newOutputStream(destination,StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer=new byte[128*1024];
            for(int n;(n=in.read(buffer))!=-1;) { copied+=n; if(copied>maxArchiveBytes) throw new IOException("Archive exceeds configured compressed-size bound"); digest.update(buffer,0,n); out.write(buffer,0,n); }
        }
        String hash=HexFormat.of().formatHex(digest.digest());
        BasicFileAttributes after=Files.readAttributes(source,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(copied!=before.size() || before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(),after.fileKey()) || !hash.equals(expectedHash)) {
            jdbc.update("UPDATE l2_archive_observation SET size_bytes=?,modified_millis=?,content_sha256=?,observed_at_millis=? WHERE source_path=?",
                    after.size(),after.lastModifiedTime().toMillis(),hash,now.toEpochMilli(),source.toString());
            throw new NotStableException("Archive changed while being frozen; settle interval restarted");
        }
        return hash;
    }

    private Scan scanZip(Path file, LocalDate date) throws IOException {
        Set<String> symbols=new HashSet<>(), tables=new HashSet<>(); int members=0; long expanded=0,totalRows=0,dateMismatches=0;
        var evidence=new ArrayList<DfcfCsvInspector.Inspection>();
        try (ZipFile zip=new ZipFile(file.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement(); String name=entry.getName(); validateArchiveName(name);
                if (entry.isDirectory()) continue;
                String[] parts=validateMember(name,date);
                if(members>=MAX_MEMBERS) throw new IOException("Archive exceeds configured member-count bound");
                if(entry.getSize()<0 || entry.getSize()>maxExpandedBytes-expanded) throw new IOException("Expanded archive exceeds configured bound");
                DfcfCsvInspector.Inspection parsed;
                try(InputStream in=zip.getInputStream(entry)) { parsed=csvInspector.inspect(name,in,date,maxExpandedBytes-expanded); }
                if(parsed.rawBytes()!=entry.getSize()) throw new IOException("Archive member size mismatch");
                expanded+=parsed.rawBytes(); totalRows+=parsed.rowCount(); dateMismatches+=parsed.tradeDateMismatchRows();
                evidence.add(parsed); registerMember(parts,symbols,tables); members++;
            }
        }
        verifyCompleteMembers(symbols,tables);
        return new Scan(members,symbols.size(),"ZIP_CRC_AND_CSV_VERIFIED",totalRows,dateMismatches,evidence);
    }

    private Scan scanSevenZip(Path file, LocalDate date) throws IOException {
        Set<String> symbols=new HashSet<>(), tables=new HashSet<>(); int members=0; long expanded=0,totalRows=0,dateMismatches=0;
        var evidence=new ArrayList<DfcfCsvInspector.Inspection>();
        try(SevenZFile archive=SevenZFile.builder().setFile(file.toFile()).setMaxMemoryLimitKiB(262_144).get()) {
            SevenZArchiveEntry entry;
            while((entry=archive.getNextEntry())!=null) {
                String name=entry.getName(); validateArchiveName(name);
                if(entry.isDirectory()) continue;
                String[] parts=validateMember(name,date);
                if(members>=MAX_MEMBERS) throw new IOException("Archive exceeds configured member-count bound");
                if(entry.getSize()<0 || entry.getSize()>maxExpandedBytes-expanded) throw new IOException("Expanded archive exceeds configured bound");
                InputStream currentEntry=new InputStream() {
                    @Override public int read() throws IOException { return archive.read(); }
                    @Override public int read(byte[] bytes,int off,int len) throws IOException { return archive.read(bytes,off,len); }
                    @Override public void close() { /* Archive lifetime belongs to SevenZFile. */ }
                };
                DfcfCsvInspector.Inspection parsed=csvInspector.inspect(name,currentEntry,date,maxExpandedBytes-expanded);
                if(parsed.rawBytes()!=entry.getSize()) throw new IOException("Archive member size mismatch");
                expanded+=parsed.rawBytes(); totalRows+=parsed.rowCount(); dateMismatches+=parsed.tradeDateMismatchRows();
                evidence.add(parsed); registerMember(parts,symbols,tables); members++;
            }
        }
        verifyCompleteMembers(symbols,tables);
        return new Scan(members,symbols.size(),"SEVEN_ZIP_CRC_AND_CSV_VERIFIED",totalRows,dateMismatches,evidence);
    }

    private static String[] validateMember(String name,LocalDate date) throws IOException {
        String[] parts=canonicalMemberName(name).split("/");
        if(parts.length!=3 || !parts[0].equals(date.format(DATE)) || !parts[1].matches("[0-9]{6}\\.(SH|SZ|BJ)"))
            throw new IOException("Unexpected DFCF archive layout: expected YYYYMMDD/symbol/file.csv");
        if(!REQUIRED.contains(parts[2])) throw new IOException("Unexpected file in DFCF symbol directory: "+parts[2]);
        return parts;
    }
    private static void validateArchiveName(String name) throws IOException {
        String canonical=canonicalMemberName(name);
        Path normalized=Path.of(canonical).normalize();
        if(canonical.startsWith("/") || normalized.isAbsolute() || normalized.startsWith("..")
                || canonical.matches("^[A-Za-z]:.*") || canonical.contains("://"))
            throw new IOException("Unsafe archive member path");
    }
    private static String canonicalMemberName(String name) { return name.replace('\\','/'); }
    private static void verifyCompleteMembers(Set<String> symbols,Set<String> tables) throws IOException {
        if(symbols.isEmpty()) throw new IOException("Archive contains no symbol data");
        for(String symbol:symbols) for(String table:REQUIRED) if(!tables.contains(symbol+"/"+table)) throw new IOException("Incomplete symbol archive: missing "+symbol+"/"+table);
    }
    private static void registerMember(String[] parts,Set<String> symbols,Set<String> tables) throws IOException {
        String key=parts[1]+"/"+parts[2];
        if(!tables.add(key)) throw new IOException("Duplicate archive member: "+key);
        symbols.add(parts[1]);
        if(symbols.size()>MAX_SYMBOLS) throw new IOException("Archive exceeds configured symbol-count bound");
    }

    private static String sha256(Path file) throws IOException {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(file)) { byte[] b=new byte[128*1024]; int n; while((n=in.read(b))!=-1) digest.update(b,0,n); } return HexFormat.of().formatHex(digest.digest()); }
        catch(java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public record Admission(String sha256,LocalDate tradeDate,Path path,long sizeBytes,int members,int symbols,long sourceRows,long tradeDateMismatches,boolean duplicate,String verification,String status,String revisionOfSha256) {}
    public static final class NotStableException extends IllegalStateException {
        public NotStableException(String message) { super(message); }
    }
    private record Scan(int members,int symbols,String kind,long totalRows,long dateMismatches,List<DfcfCsvInspector.Inspection> memberEvidence) {}
}
