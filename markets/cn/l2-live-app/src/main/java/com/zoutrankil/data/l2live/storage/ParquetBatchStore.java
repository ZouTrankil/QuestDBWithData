package com.zoutrankil.data.l2live.storage;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/** 四个 Parquet 文件作为一个不可变目录原子发布，SQLite 仅引用已同步的内容摘要。 */
final class ParquetBatchStore {
    static final List<String> FILES=List.of("raw.parquet","events.parquet","minutes.parquet","checkpoint.parquet");
    record Receipt(String raw,String events,String minutes,String checkpoint) {
        List<String> hashes(){return List.of(raw,events,minutes,checkpoint);}
        String fingerprint(){return LiveValueIdentity.hash(hashes());}
    }
    private final Path root,pending,ready;
    private final long quota;
    private long bytes;
    synchronized void recount() throws Exception {
        try(var files=Files.walk(root)){long total=0;for(Path p:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())total=Math.addExact(total,Files.size(p));bytes=total;}
        if(bytes>quota)throw new IllegalStateException("Parquet archive byte quota exceeded");
    }
    ParquetBatchStore(Path root,long quota) throws Exception {
        this.root=root;this.pending=root.resolve(".pending");this.ready=root.resolve("batches");this.quota=quota;
        for(Path dir:List.of(root,pending,ready)) {if(Files.isSymbolicLink(dir))throw new IllegalStateException("Linked Parquet root rejected");Files.createDirectories(dir);}
        try(var files=Files.walk(root)){for(Path p:files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())bytes=Math.addExact(bytes,Files.size(p));}
        if(bytes>quota)throw new IllegalStateException("Parquet archive byte quota exceeded");
    }
    private Path path(Path base,String id) {
        if(!id.matches("live_(?:pq_)?[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid Parquet batch identity");return base.resolve(id);
    }
    Path directory(String id) {Path result=path(ready,id);return Files.exists(result,LinkOption.NOFOLLOW_LINKS)?result:path(pending,id);}
    boolean published(String id){return Files.isDirectory(path(ready,id),LinkOption.NOFOLLOW_LINKS);}
    synchronized Receipt freeze(String id,RawJournal.FrozenData data) throws Exception {
        Path existing=directory(id);String logical=LiveValueIdentity.batch(data);
        if(Files.exists(existing,LinkOption.NOFOLLOW_LINKS)) {
            if(!logical.equals(LiveValueIdentity.batch(read(id,receipt(existing)))))throw new IllegalStateException("Frozen Parquet identity collision");return receipt(existing);
        }
        Path temporary=Files.createTempDirectory(pending,".partial-");
        try {
            LiveParquetCodec.writeBatch(temporary,id,data);
            long size=0;
            for(String file:FILES){Path p=temporary.resolve(file);size=Math.addExact(size,Files.size(p));try(var c=FileChannel.open(p,WRITE)){c.force(true);}}
            if(size>quota-bytes)throw new IllegalStateException("Parquet archive byte quota exceeded");
            if(!logical.equals(LiveValueIdentity.batch(LiveParquetCodec.readBatch(temporary,id))))throw new IllegalStateException("Parquet readback values differ");
            syncDirectory(temporary);
            var receipt=receipt(temporary);Files.move(temporary,path(pending,id),StandardCopyOption.ATOMIC_MOVE);temporary=null;bytes=Math.addExact(bytes,size);syncDirectory(pending);return receipt;
        } finally {if(temporary!=null)removeTemporary(temporary);}
    }
    RawJournal.FrozenData read(String id,Receipt expected) throws Exception {
        Path dir=directory(id);if(!expected.equals(receipt(dir)))throw new IllegalStateException("Retained Parquet file hash changed");return LiveParquetCodec.readBatch(dir,id);
    }
    void publish(String id,Receipt receipt) throws Exception {
        read(id,receipt);
        if(!published(id)){Files.move(path(pending,id),path(ready,id),StandardCopyOption.ATOMIC_MOVE);syncDirectory(pending);syncDirectory(ready);}
        verifyPublished(id,receipt);
    }
    void verifyPublished(String id,Receipt expected) throws Exception {
        if(!published(id)||!expected.equals(receipt(path(ready,id))))throw new IllegalStateException("Published Parquet file hashes differ");
    }
    static Receipt receipt(Path dir) throws Exception {
        if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Missing or linked Parquet directory");
        try(var entries=Files.list(dir)){if(!entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(new HashSet<>(FILES)))throw new IllegalStateException("Unexpected Parquet batch file closure");}
        var hashes=new ArrayList<String>();for(String file:FILES){Path p=dir.resolve(file);if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>16L*1024*1024)throw new IllegalStateException("Invalid retained Parquet file");hashes.add(sha(p));}return new Receipt(hashes.get(0),hashes.get(1),hashes.get(2),hashes.get(3));
    }
    static String sha(Path file) throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    static void syncDirectory(Path dir) throws Exception {if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))try(var c=FileChannel.open(dir,READ)){c.force(true);}}
    private static void removeTemporary(Path dir) throws Exception {try(var files=Files.list(dir)){for(Path p:files.toList())Files.delete(p);}Files.delete(dir);}
}
