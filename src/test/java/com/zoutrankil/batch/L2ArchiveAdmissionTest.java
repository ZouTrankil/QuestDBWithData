package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class L2ArchiveAdmissionTest {
    @TempDir Path temp;
    private L2ArchiveAdmission service(Path root) throws Exception {
        var ds=new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("test.sqlite"),null,null);
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE l2_archive_ledger(archive_sha256 TEXT PRIMARY KEY,source_path TEXT,frozen_path TEXT,trade_date TEXT,archive_size_bytes INTEGER,archive_modified_millis INTEGER,member_count INTEGER,symbol_count INTEGER,status TEXT,revision_of_sha256 TEXT,first_seen_at TEXT DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE l2_archive_observation(source_path TEXT PRIMARY KEY,size_bytes INTEGER,modified_millis INTEGER,content_sha256 TEXT,observed_at_millis INTEGER)");
        jdbc.execute("CREATE TABLE l2_archive_member(archive_sha256 TEXT,member_path TEXT,symbol TEXT,file_name TEXT,row_count INTEGER,raw_bytes INTEGER,member_sha256 TEXT,trade_date_mismatch_rows INTEGER,PRIMARY KEY(archive_sha256,member_path))");
        return new L2ArchiveAdmission(jdbc,root,Duration.ofMinutes(5),1024*1024,4*1024*1024);
    }
    private Path zip(String date, boolean complete) throws Exception {
        return zip(date,complete,"dfcf-"+date+".zip","1",false);
    }
    private Path zip(String date, boolean complete,String filename,String version) throws Exception {
        return zip(date,complete,filename,version,false);
    }
    private Path zip(String date, boolean complete,String filename,String version,boolean huge) throws Exception {
        Path root=Files.createDirectories(temp.resolve("archives")); Path file=root.resolve(filename);
        try(var out=new ZipOutputStream(Files.newOutputStream(file))) {
            String[] names={"逐笔成交.csv","逐笔委托.csv","行情.csv"};
            for(int i=0;i<(complete?3:2);i++) { out.putNextEntry(new ZipEntry(date+"\\000001.SZ\\"+names[i])); out.write(csv(date,names[i],version,huge).getBytes(DfcfCsvInspector.ENCODING)); out.closeEntry(); }
        }
        Files.setLastModifiedTime(file,FileTime.from(Instant.now().minus(Duration.ofMinutes(10)))); return file;
    }
    private Path sevenZip(String date, boolean complete) throws Exception {
        Path root=Files.createDirectories(temp.resolve("archives")); Path file=root.resolve("dfcf-"+date+".7z");
        try(var out=new SevenZOutputFile(file.toFile())) {
            String[] names={"逐笔成交.csv","逐笔委托.csv","行情.csv"};
            for(int i=0;i<(complete?3:2);i++) {
                byte[] data=csv(date,names[i],"1",false).getBytes(DfcfCsvInspector.ENCODING);
                var entry=new SevenZArchiveEntry(); entry.setName(date+"\\000001.SZ\\"+names[i]); entry.setSize(data.length);
                out.putArchiveEntry(entry); out.write(data); out.closeArchiveEntry();
            }
        }
        Files.setLastModifiedTime(file,FileTime.from(Instant.now().minus(Duration.ofMinutes(10)))); return file;
    }
    static String csv(String date,String fileName,String version,boolean huge) {
        List<String> header=switch(fileName) {
            case "逐笔成交.csv" -> new ArrayList<>(List.of("自然日","时间","成交编号","成交价格","成交数量","BS标志","叫买序号","叫卖序号"));
            case "逐笔委托.csv" -> new ArrayList<>(List.of("自然日","时间","交易所委托号","委托类型","委托代码","委托价格","委托数量"));
            default -> {
                var names=new ArrayList<>(List.of("自然日","时间","成交价","成交量","成交额","当日累计成交量","当日成交额","叫买总量","叫卖总量","加权平均叫买价","加权平均叫卖价"));
                for(int i=1;i<=10;i++) names.add("申买价"+i);
                for(int i=1;i<=10;i++) names.add("申卖价"+i);
                for(int i=1;i<=10;i++) names.add("申买量"+i);
                for(int i=1;i<=10;i++) names.add("申卖量"+i);
                yield names;
            }
        };
        var values=new ArrayList<>(Collections.nCopies(header.size(),"0")); values.set(0,date); values.set(1,"93000000");
        if(fileName.equals("逐笔成交.csv")) { values.set(2,version); values.set(3,"100"); values.set(4,"10"); values.set(5,"B"); values.set(6,"1"); values.set(7,"2"); }
        if(fileName.equals("逐笔委托.csv")) { values.set(2,version); values.set(3,"0"); values.set(4,"B"); values.set(5,"100"); values.set(6,"10"); }
        if(huge) { header.add("备注"); values.add("x".repeat(1_500_000)); }
        return String.join(",",header)+"\n"+String.join(",",values)+"\n";
    }
    @Test void verifiesBoundedCompleteZipAndDeduplicatesByContentFingerprint() throws Exception {
        Path file=zip("20260928",true); var service=service(file.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        var first=service.inspect(file,now.plus(Duration.ofMinutes(6))); var second=service.inspect(file,now.plus(Duration.ofMinutes(6)));
        assertEquals("2026-09-28",first.tradeDate().toString()); assertEquals(3,first.members()); assertEquals(1,first.symbols());
        assertEquals(3,first.sourceRows()); assertEquals(0,first.tradeDateMismatches());
        assertEquals(3,new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("test.sqlite"),null,null)).queryForObject("SELECT count(*) FROM l2_archive_member",Integer.class));
        assertEquals(64,first.sha256().length()); assertFalse(first.duplicate()); assertTrue(second.duplicate());
        assertTrue(Files.isRegularFile(first.path())); assertTrue(first.path().getParent().endsWith("frozen"));
    }
    @Test void rejectsUnsettledIncompleteAndPathTraversalArchives() throws Exception {
        Path file=zip("20260928",false); var service=service(file.getParent());
        Files.setLastModifiedTime(file,FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        assertThrows(IllegalStateException.class,()->service.inspect(file,Instant.now()));
        assertThrows(java.io.IOException.class,()->service.inspect(file,Instant.now().plus(Duration.ofMinutes(6))));
        Path bad=file.getParent().resolve("dfcf-20260929.zip");
        try(var out=new ZipOutputStream(Files.newOutputStream(bad))) { out.putNextEntry(new ZipEntry("../evil")); out.write(1); out.closeEntry(); }
        Files.setLastModifiedTime(bad,FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));
        assertThrows(IllegalStateException.class,()->service.inspect(bad,Instant.now()));
        assertThrows(java.io.IOException.class,()->service.inspect(bad,Instant.now().plus(Duration.ofMinutes(6))));
    }
    @Test void rejectsArchivesOutsideConfiguredRootAndWrongNames() throws Exception {
        Path root=Files.createDirectories(temp.resolve("root")); var service=service(root);
        assertThrows(IllegalArgumentException.class,()->service.inspect(temp.resolve("20260928.zip"),Instant.now()));
        Path bad=Files.createFile(root.resolve("archive.zip"));
        assertThrows(IllegalArgumentException.class,()->service.inspect(bad,Instant.now()));
    }
    @Test void validatesSevenZipMembersAndBoundedExpansionWithoutExternalExecutable() throws Exception {
        Path file=sevenZip("20260928",true); var service=service(file.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        var accepted=service.inspect(file,now.plus(Duration.ofMinutes(6)));
        assertEquals("SEVEN_ZIP_CRC_AND_CSV_VERIFIED",accepted.verification()); assertEquals(3,accepted.members());
        assertEquals(1,accepted.symbols());
    }
    @Test void rejectsIncompleteSevenZipWithoutAdmittingIt() throws Exception {
        Path file=sevenZip("20260928",false); var service=service(file.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        assertThrows(java.io.IOException.class,()->service.inspect(file,now.plus(Duration.ofMinutes(6))));
    }
    @Test void rejectsCorruptedSevenZipAfterMemberCrcCheck() throws Exception {
        Path file=sevenZip("20260928",true); var service=service(file.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        try(var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE)) {
            long position=channel.size()-8; channel.position(position); java.nio.ByteBuffer one=java.nio.ByteBuffer.allocate(1);
            channel.read(one); one.flip(); one.put(0,(byte)(one.get(0)^0x40)); one.position(0); channel.position(position); channel.write(one);
        }
        assertThrows(IllegalStateException.class,()->service.inspect(file,now.plus(Duration.ofMinutes(6))));
        assertThrows(java.io.IOException.class,()->service.inspect(file,now.plus(Duration.ofMinutes(12))));
    }
    @Test void recordsChangedSameDateArchiveAsExplicitRevision() throws Exception {
        Path original=zip("20260928",true); var service=service(original.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(original,now));
        var first=service.inspect(original,now.plus(Duration.ofMinutes(6)));
        Path revision=zip("20260928",true,"revised-20260928.zip","revision");
        assertThrows(IllegalStateException.class,()->service.inspect(revision,now.plus(Duration.ofMinutes(6))));
        var second=service.inspect(revision,now.plus(Duration.ofMinutes(12)));
        assertEquals("REVISION",second.status()); assertEquals(first.sha256(),second.revisionOfSha256());
        assertFalse(second.duplicate());
    }
    @Test void rejectsZipBombByExpandedByteBoundWithoutBufferingArchiveInMemory() throws Exception {
        Path file=zip("20260928",true,"oversized-20260928.zip","1",true); var service=service(file.getParent()); Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        assertThrows(java.io.IOException.class,()->service.inspect(file,now.plus(Duration.ofMinutes(6))));
    }

    @Test void semanticMaterializationWritesThreeBoundedArtifactsAndReusesVerifiedManifest() throws Exception {
        Path file=zip("20260928",true);var service=service(file.getParent());Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        var admission=service.inspect(file,now.plus(Duration.ofMinutes(6)));
        var materializer=new L2ArchiveMaterializer(file.getParent(),4*1024*1024,4*1024*1024);
        var first=materializer.materialize(admission);
        assertFalse(first.reused());assertEquals(L2ArchiveMaterializer.PARSER_VERSION,first.manifest().parserVersion());
        assertEquals(1,first.manifest().dealRows());assertEquals(1,first.manifest().orderRows());assertEquals(1,first.manifest().quoteRows());
        assertEquals(1L,first.manifest().dealSides().get("BUY"));assertEquals(1L,first.manifest().orderKinds().get("ADD_BUY"));
        assertTrue(Files.isRegularFile(first.manifestPath()));
        Path parsedRoot=first.manifestPath().getParent();
        assertTrue(gzip(parsedRoot.resolve("deals.ndjson.gz")).contains("\"side\":\"BUY\""));
        assertTrue(gzip(parsedRoot.resolve("orders.ndjson.gz")).contains("\"kuakeOrderType\":1"));
        assertTrue(gzip(parsedRoot.resolve("quotes.ndjson.gz")).contains("\"levels\":["));
        var replay=materializer.materialize(admission);assertTrue(replay.reused());assertEquals(first.manifest(),replay.manifest());
        Files.writeString(parsedRoot.resolve("orders.ndjson.gz"),"tampered");
        assertThrows(java.io.IOException.class,()->materializer.materialize(admission));
    }

    @Test void semanticMaterializationSupportsSevenZipWithoutExternalTools() throws Exception {
        Path file=sevenZip("20260928",true);var service=service(file.getParent());Instant now=Instant.now();
        assertThrows(IllegalStateException.class,()->service.inspect(file,now));
        var admission=service.inspect(file,now.plus(Duration.ofMinutes(6)));
        var parsed=new L2ArchiveMaterializer(file.getParent(),4*1024*1024,4*1024*1024).materialize(admission);
        assertEquals(1,parsed.manifest().dealRows());assertEquals(1,parsed.manifest().orderRows());assertEquals(1,parsed.manifest().quoteRows());
        assertTrue(gzip(parsed.manifestPath().getParent().resolve("deals.ndjson.gz")).contains("000001.SZ"));
    }

    private static String gzip(Path file)throws Exception {
        try(var in=new java.util.zip.GZIPInputStream(Files.newInputStream(file))) { return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
    }
}
