package com.zoutrankil.batch;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Materializes semantically mapped DFCF rows as immutable compressed staging artifacts. */
public final class L2ArchiveMaterializer {
    public static final String PARSER_VERSION="dfcf-csv-mapper-v1";
    private static final Set<String> TABLES=Set.of("逐笔成交.csv","逐笔委托.csv","行情.csv");
    private static final int MAX_MEMBERS=100_000,MAX_SYMBOLS=20_000;
    private final Path root;
    private final long maxExpandedBytes,maxMaterializedBytes;

    public L2ArchiveMaterializer(Path archiveRoot,long maxExpandedBytes,long maxMaterializedBytes) throws IOException {
        this.root=archiveRoot.toAbsolutePath().normalize();this.maxExpandedBytes=maxExpandedBytes;this.maxMaterializedBytes=maxMaterializedBytes;
        if(maxExpandedBytes<1||maxMaterializedBytes<1)throw new IllegalArgumentException("Positive L2 materialization bounds required");
        Path parsed=this.root.resolve("parsed");Files.createDirectories(parsed);
        if(Files.isSymbolicLink(parsed))throw new IOException("Parsed artifact directory cannot be a symbolic link");
    }

    public synchronized Materialization materialize(L2ArchiveAdmission.Admission admission) throws IOException {
        Objects.requireNonNull(admission);
        Path frozen=admission.path().toAbsolutePath().normalize();String sha=admission.sha256();
        if(!sha.matches("[a-f0-9]{64}")||!frozen.getParent().equals(root.resolve("frozen"))
                ||!frozen.getFileName().toString().matches(sha+"\\.(zip|7z)")
                ||!Files.isRegularFile(frozen,LinkOption.NOFOLLOW_LINKS)||!sha.equals(sha256(frozen)))
            throw new IOException("Materializer requires the matching immutable content-addressed archive");
        Path outputRoot=root.resolve("parsed"),target=outputRoot.resolve(sha),lockPath=outputRoot.resolve("."+sha+".lock");
        if(Files.isSymbolicLink(lockPath))throw new IOException("Materialization lock cannot be a symbolic link");
        try(FileChannel lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock ignored=tryLock(lockChannel)) {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))return verifyExisting(target,admission);
            cleanAbandoned(outputRoot,sha);
            Path staging=Files.createDirectory(outputRoot.resolve("."+sha+".partial-"+UUID.randomUUID()));
            try {
                MutableSummary summary=new MutableSummary(); OutputBudget outputBudget=new OutputBudget(maxMaterializedBytes);
                Path deals=staging.resolve("deals.ndjson.gz"),orders=staging.resolve("orders.ndjson.gz"),quotes=staging.resolve("quotes.ndjson.gz");
                try(var dealWriter=writer(deals,outputBudget);var orderWriter=writer(orders,outputBudget);var quoteWriter=writer(quotes,outputBudget)) {
                    Outputs sinks=new Outputs(dealWriter,orderWriter,quoteWriter,summary);
                    if(frozen.toString().endsWith(".zip"))scanZip(frozen,admission.tradeDate(),sinks,summary);
                    else scanSevenZip(frozen,admission.tradeDate(),sinks,summary);
                }
                if(summary.members!=admission.members()||summary.symbols.size()!=admission.symbols()
                        ||summary.deals+summary.orders+summary.quotes!=admission.sourceRows())
                    throw new IOException("Semantic row/member counts differ from the integrity admission certificate");
                Map<String,Artifact> artifacts=new LinkedHashMap<>();
                artifacts.put("deals",artifact(deals,staging));artifacts.put("orders",artifact(orders,staging));artifacts.put("quotes",artifact(quotes,staging));
                Manifest manifest=new Manifest(1,PARSER_VERSION,sha,admission.tradeDate(),summary.members,summary.symbols.size(),
                        summary.deals,summary.orders,summary.quotes,summary.dealSides,summary.orderKinds,artifacts);
                Path manifestPath=staging.resolve("manifest.json");Files.writeString(manifestPath,Json.write(manifest),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                try { Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException unsupported) { throw new IOException("Atomic publication of L2 materialization is required",unsupported); }
                return new Materialization(manifest,target.resolve("manifest.json"),false);
            } catch(Exception failure) {
                deleteTree(staging);
                if(failure instanceof IOException io)throw io;
                throw new IOException("DFCF semantic materialization failed",failure);
            }
        } catch(java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Another materializer owns this archive",busy); }
    }

    private void scanZip(Path file,LocalDate date,Outputs sinks,MutableSummary summary) throws IOException {
        long[] expanded={0};Set<String> members=new HashSet<>();
        try(ZipFile zip=new ZipFile(file.toFile())) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();if(entry.isDirectory())continue;
                String[] parts=member(entry.getName(),date,members,summary);
                if(entry.getSize()<0||entry.getSize()>maxExpandedBytes-expanded[0])throw new IOException("DFCF expanded member bound exceeded");
                InputStream raw=zip.getInputStream(entry);var counted=new CountingInputStream(raw,maxExpandedBytes-expanded[0]);
                long rows=scanTable(counted,parts[1],date,entry.getSize(),entry.getName(),sinks);
                if(counted.count()!=entry.getSize()||rows<0)throw new IOException("DFCF ZIP member size or parser count mismatch");
                expanded[0]+=counted.count();summary.sourceRows+=rows;
            }
        }
        verifyMembers(members,summary.symbols);
    }

    private void scanSevenZip(Path file,LocalDate date,Outputs sinks,MutableSummary summary) throws IOException {
        long expanded=0;Set<String> members=new HashSet<>();
        try(SevenZFile archive=SevenZFile.builder().setFile(file.toFile()).setMaxMemoryLimitKiB(262_144).get()) {
            SevenZArchiveEntry entry;
            while((entry=archive.getNextEntry())!=null) {
                if(entry.isDirectory())continue;
                String[] parts=member(entry.getName(),date,members,summary);
                if(entry.getSize()<0||entry.getSize()>maxExpandedBytes-expanded)throw new IOException("DFCF expanded member bound exceeded");
                InputStream current=new InputStream(){
                    @Override public int read() throws IOException{return archive.read();}
                    @Override public int read(byte[] b,int off,int len)throws IOException{return archive.read(b,off,len);}
                    @Override public void close(){}
                };
                var counted=new CountingInputStream(current,maxExpandedBytes-expanded);
                long rows=scanTable(counted,parts[1],date,entry.getSize(),entry.getName(),sinks);
                if(counted.count()!=entry.getSize()||rows<0)throw new IOException("DFCF 7z member size or parser count mismatch");
                expanded+=counted.count();summary.sourceRows+=rows;
            }
        }
        verifyMembers(members,summary.symbols);
    }

    private long scanTable(InputStream input,String symbol,LocalDate date,long memberSize,String name,Outputs sinks)throws IOException {
        String filename=name.replace('\\','/').substring(name.replace('\\','/').lastIndexOf('/')+1);
        return switch(filename) {
            case "逐笔成交.csv" -> DfcfCsvParser.scanDeals(input,symbol,date,memberSize,sinks::deal);
            case "逐笔委托.csv" -> DfcfCsvParser.scanOrders(input,symbol,date,memberSize,sinks::order);
            case "行情.csv" -> DfcfCsvParser.scanQuotes(input,symbol,date,memberSize,sinks::quote);
            default -> throw new IOException("Unsupported DFCF member table");
        };
    }

    private static String[] member(String name,LocalDate date,Set<String> seen,MutableSummary summary)throws IOException {
        String canonical=name.replace('\\','/');Path normalized=Path.of(canonical).normalize();
        if(canonical.startsWith("/")||normalized.isAbsolute()||normalized.startsWith("..")||canonical.matches("^[A-Za-z]:.*")||canonical.contains("://"))
            throw new IOException("Unsafe DFCF member path");
        String[] parts=canonical.split("/");
        if(parts.length!=3||!parts[0].equals(date.toString().replace("-",""))||!parts[1].matches("[0-9]{6}\\.(SH|SZ|BJ)")||!TABLES.contains(parts[2]))
            throw new IOException("Unexpected DFCF archive member");
        String key=parts[1]+"/"+parts[2];if(!seen.add(key))throw new IOException("Duplicate DFCF archive member");
        summary.members++;summary.symbols.add(parts[1]);
        if(summary.members>MAX_MEMBERS||summary.symbols.size()>MAX_SYMBOLS)throw new IOException("DFCF archive exceeds parser member/symbol limits");
        return parts;
    }
    private static void verifyMembers(Set<String> members,Set<String> symbols)throws IOException {
        if(symbols.isEmpty())throw new IOException("DFCF archive contains no symbol data");
        for(String symbol:symbols)for(String table:TABLES)if(!members.contains(symbol+"/"+table))throw new IOException("Incomplete DFCF symbol member set");
    }

    private Materialization verifyExisting(Path directory,L2ArchiveAdmission.Admission admission)throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))throw new IOException("Invalid parsed archive directory");
        Path path=directory.resolve("manifest.json");if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Parsed archive publication is incomplete");
        Manifest manifest=Json.read(Files.readString(path),Manifest.class);
        if(manifest.schemaVersion()!=1||!PARSER_VERSION.equals(manifest.parserVersion())||!admission.sha256().equals(manifest.archiveSha256())
                ||!admission.tradeDate().equals(manifest.tradeDate())||manifest.members()!=admission.members()||manifest.symbols()!=admission.symbols()
                ||manifest.dealRows()+manifest.orderRows()+manifest.quoteRows()!=admission.sourceRows()
                ||manifest.artifacts()==null||!manifest.artifacts().keySet().equals(Set.of("deals","orders","quotes")))
            throw new IOException("Existing parsed artifact manifest conflicts with archive admission");
        for(Artifact artifact:manifest.artifacts().values()) {
            Path file=directory.resolve(artifact.file()).normalize();
            if(!file.getParent().equals(directory)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=artifact.bytes()||!sha256(file).equals(artifact.sha256()))
                throw new IOException("Existing parsed artifact is corrupt");
        }
        return new Materialization(manifest,path,true);
    }
    private static Artifact artifact(Path path,Path directory)throws IOException{return new Artifact(directory.relativize(path).toString(),Files.size(path),sha256(path));}
    private static BufferedWriter writer(Path path,OutputBudget budget)throws IOException {
        OutputStream file=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW);
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new BudgetOutputStream(file,budget),64*1024),StandardCharsets.UTF_8),64*1024);
    }
    private static FileLock tryLock(FileChannel channel)throws IOException {
        FileLock lock=channel.tryLock();if(lock==null)throw new IOException("Another process owns L2 materialization");return lock;
    }
    private static void cleanAbandoned(Path root,String hash)throws IOException {
        try(var paths=Files.list(root)) { for(Path path:paths.filter(p->p.getFileName().toString().startsWith("."+hash+".partial-")).toList()) deleteTree(path); }
    }
    private static void deleteTree(Path path)throws IOException {
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        try(var walk=Files.walk(path)){for(Path item:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);}
    }
    private static String sha256(Path file)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[128*1024];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }

    public record Artifact(String file,long bytes,String sha256) {}
    public record Manifest(int schemaVersion,String parserVersion,String archiveSha256,LocalDate tradeDate,int members,int symbols,
                           long dealRows,long orderRows,long quoteRows,Map<String,Long> dealSides,Map<String,Long> orderKinds,
                           Map<String,Artifact> artifacts) {}
    public record Materialization(Manifest manifest,Path manifestPath,boolean reused) {}
    private static final class MutableSummary {
        int members;long sourceRows,deals,orders,quotes;final Set<String> symbols=new TreeSet<>();
        final Map<String,Long> dealSides=new TreeMap<>(),orderKinds=new TreeMap<>();
    }
    private static final class Outputs {
        final BufferedWriter deals,orders,quotes;final MutableSummary summary;
        Outputs(BufferedWriter deals,BufferedWriter orders,BufferedWriter quotes,MutableSummary summary){this.deals=deals;this.orders=orders;this.quotes=quotes;this.summary=summary;}
        void deal(DfcfCsvParser.Deal row)throws IOException {write(deals,row);summary.deals++;summary.dealSides.merge(row.side().name(),1L,Long::sum);}
        void order(DfcfCsvParser.Order row)throws IOException {write(orders,row);summary.orders++;summary.orderKinds.merge(row.kind().name(),1L,Long::sum);}
        void quote(DfcfCsvParser.Quote row)throws IOException {write(quotes,row);summary.quotes++;}
        private static void write(BufferedWriter writer,Object row)throws IOException {writer.write(Json.write(row));writer.newLine();}
    }
    private static final class CountingInputStream extends FilterInputStream {
        final long max;long count;CountingInputStream(InputStream in,long max){super(in);this.max=max;}
        private void add(int n)throws IOException{count+=n;if(count>max)throw new IOException("DFCF expanded-byte budget exceeded");}
        @Override public int read()throws IOException{int value=super.read();if(value>=0)add(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{int n=super.read(bytes,offset,length);if(n>0)add(n);return n;}
        long count(){return count;}
    }
    private static final class OutputBudget {
        final long max;long count;OutputBudget(long max){this.max=max;}
        synchronized void add(int amount)throws IOException{count+=amount;if(count>max)throw new IOException("L2 materialized artifact exceeds configured byte bound");}
    }
    private static final class BudgetOutputStream extends FilterOutputStream {
        final OutputBudget budget;BudgetOutputStream(OutputStream out,OutputBudget budget){super(out);this.budget=budget;}
        @Override public void write(int b)throws IOException{budget.add(1);out.write(b);}
        @Override public void write(byte[] b,int off,int len)throws IOException{budget.add(len);out.write(b,off,len);}
    }
}
