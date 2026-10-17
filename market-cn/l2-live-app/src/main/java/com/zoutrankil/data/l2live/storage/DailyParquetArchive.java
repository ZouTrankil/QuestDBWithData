package com.zoutrankil.data.l2live.storage;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import org.apache.parquet.hadoop.*;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.*;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;

/** 按接收日期合并已完成的小文件；先核验、再切换 SQLite 引用，最后才清理旧文件。 */
final class DailyParquetArchive {
    record Member(String id,String logical,ParquetBatchStore.Receipt original,List<Long> rows) {}
    record Manifest(String schema,LocalDate date,List<Member> members,Map<String,String> files) {}
    record Result(LocalDate date,int batches,long filesRemoved,String manifestSha256) {}
    private static final String SCHEMA="java.l2.daily_archive.v1";
    private static Path directory(Path root,LocalDate date){return root.resolve("archives").resolve(date.toString());}
    static void initialize(Connection c)throws Exception {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS parquet_archives(archive_date TEXT PRIMARY KEY,manifest_sha256 TEXT NOT NULL,manifest_json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS parquet_archive_members(batch_id TEXT PRIMARY KEY REFERENCES prepared_batch(batch_id),archive_date TEXT NOT NULL REFERENCES parquet_archives(archive_date))");
        }
    }
    static Result seal(RawJournal journal,Connection c,Path root,LocalDate date,LocalDate today)throws Exception {
        if(!date.isBefore(today))throw new IllegalArgumentException("只能封存已结束的接收日期");
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM prepared_batch WHERE active=1")){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("存在未完成批次，先重放或对账");}
        try(var s=c.prepareStatement("SELECT count(*) FROM raw_capture WHERE completed=0 AND capture_date<=?")){s.setString(1,date.toString());try(var r=s.executeQuery()){r.next();if(r.getLong(1)!=0)throw new IllegalStateException("封存日期仍有原始报文待处理");}}
        try(var s=c.prepareStatement("SELECT manifest_json,manifest_sha256 FROM parquet_archives WHERE archive_date=?")){
            s.setString(1,date.toString());try(var r=s.executeQuery()){if(r.next()){var m=load(root,r.getString(1),r.getString(2));return new Result(date,m.members().size(),cleanup(root,m),r.getString(2));}}
        }
        var members=new ArrayList<Member>();
        int scanned=0;
        try(var s=c.createStatement();var r=s.executeQuery("SELECT p.batch_id FROM prepared_batch p WHERE p.active=0 AND p.write_verified=1 AND length(p.payload)=0 AND p.batch_id NOT IN (SELECT batch_id FROM parquet_archive_members) AND p.batch_id NOT IN (SELECT batch_id FROM committed_accumulator) ORDER BY p.created_at,p.batch_id LIMIT 200001")) {
            while(r.next()){
                if(++scanned>200000)throw new IllegalStateException("候选批次超过 200000，先封存较早日期，不能把截断扫描当成空日");
                if(members.size()>=200000)throw new IllegalStateException("单日封存批次数超过 200000");String id=r.getString(1);var stored=journal.storedBatch(id);
                if(stored.events().stream().anyMatch(e->!e.capture().captureDate().equals(date)))continue;
                var source=root.resolve("batches").resolve(id);var original=ParquetBatchStore.receipt(source);var counts=new ArrayList<Long>();
                for(String file:ParquetBatchStore.FILES)try(var reader=ParquetFileReader.open(new LocalInputFile(source.resolve(file)))){counts.add(reader.getRecordCount());}
                members.add(new Member(id,LiveValueIdentity.batch(new RawJournal.FrozenData(stored.events(),stored.states(),stored.accumulatorState())),original,List.copyOf(counts)));
            }
        }
        if(members.isEmpty())return new Result(date,0,0,"");
        Path archives=root.resolve("archives");if(Files.isSymbolicLink(archives))throw new IllegalStateException("归档目录不能是链接");Files.createDirectories(archives);
        Path target=directory(root,date);Manifest manifest;
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            manifest=readManifest(target);
            if(!manifest.members().equals(members)||!manifest.date().equals(date))throw new IllegalStateException("已冻结的归档范围发生变化");verify(root,manifest);
        }else {
            Path temporary=Files.createTempDirectory(archives,".partial-"+date+"-");
            try {
                var hashes=new TreeMap<String,String>();
                for(String file:ParquetBatchStore.FILES){append(root,temporary.resolve(file),date,members,file);try(var f=FileChannel.open(temporary.resolve(file),StandardOpenOption.WRITE)){f.force(true);}hashes.put(file,ParquetBatchStore.sha(temporary.resolve(file)));}
                manifest=new Manifest(SCHEMA,date,List.copyOf(members),Map.copyOf(hashes));Files.write(temporary.resolve("manifest.json"),LiveStorageJson.bytes(manifest),StandardOpenOption.CREATE_NEW);
                try(var f=FileChannel.open(temporary.resolve("manifest.json"),StandardOpenOption.WRITE)){f.force(true);}ParquetBatchStore.syncDirectory(temporary);
                // 每个原批次逐列回读，包含空值、纳秒和累计器；不是仅比较合并行数。
                for(var member:members){var data=extract(temporary,manifest,member);if(!member.logical().equals(LiveValueIdentity.batch(data)))throw new IllegalStateException("合并后的批次值不符");}
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);temporary=null;ParquetBatchStore.syncDirectory(archives);
            }finally{if(temporary!=null)removeTemporary(temporary);}
        }
        byte[] bytes=LiveStorageJson.bytes(manifest);String hash=LiveStorageJson.sha256(bytes);verify(root,manifest);
        c.setAutoCommit(false);
        try {
            try(var s=c.prepareStatement("INSERT INTO parquet_archives VALUES(?,?,?)")){s.setString(1,date.toString());s.setString(2,hash);s.setString(3,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));s.executeUpdate();}
            for(var member:members)try(var s=c.prepareStatement("INSERT INTO parquet_archive_members VALUES(?,?)")){s.setString(1,member.id());s.setString(2,date.toString());s.executeUpdate();}
            c.commit();
        }catch(Exception e){try{c.rollback();}catch(Exception suppressed){e.addSuppressed(suppressed);}throw e;}finally{c.setAutoCommit(true);}
        return new Result(date,members.size(),cleanup(root,manifest),hash);
    }
    /** 不改编码或精度，直接复制原行组；归档文件保留同一类型 schema。 */
    private static void append(Path root,Path output,LocalDate date,List<Member> members,String file)throws Exception {
        var first=root.resolve("batches").resolve(members.getFirst().id()).resolve(file);
        try(var reader=ParquetFileReader.open(new LocalInputFile(first));var writer=new ParquetFileWriter(new LocalOutputFile(output),reader.getFooter().getFileMetaData().getSchema(),ParquetFileWriter.Mode.CREATE,1024*1024,0)) {
            writer.start();for(var member:members)writer.appendFile(new LocalInputFile(root.resolve("batches").resolve(member.id()).resolve(file)));
            writer.end(Map.of("l2.format.version","1","l2.dataset",file.replace(".parquet",""),"l2.batch.id","archive-"+date));
        }
    }
    static Optional<RawJournal.FrozenData> read(Connection c,Path root,String id,ParquetBatchStore.Receipt expected)throws Exception {
        try(var s=c.prepareStatement("SELECT a.manifest_json,a.manifest_sha256 FROM parquet_archive_members m JOIN parquet_archives a USING(archive_date) WHERE m.batch_id=?")){
            s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())return Optional.empty();var m=load(root,r.getString(1),r.getString(2));
                var member=m.members().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();if(!member.original().equals(expected))throw new IllegalStateException("归档原始批次引用已改变");
                var data=extract(directory(root,m.date()),m,member);if(!member.logical().equals(LiveValueIdentity.batch(data)))throw new IllegalStateException("归档批次逻辑摘要不符");return Optional.of(data);
            }
        }
    }
    private static RawJournal.FrozenData extract(Path dir,Manifest manifest,Member member)throws Exception {
        Path temporary=Files.createTempDirectory(dir.getParent(),".restore-");
        try {
            long[] offset={0,0,0,0};for(var preceding:manifest.members()){if(preceding.id().equals(member.id()))break;for(int i=0;i<4;i++)offset[i]=Math.addExact(offset[i],preceding.rows().get(i));}
            for(int i=0;i<4;i++)copyRange(dir.resolve(ParquetBatchStore.FILES.get(i)),temporary.resolve(ParquetBatchStore.FILES.get(i)),member.id(),offset[i],member.rows().get(i));
            return LiveParquetCodec.readBatch(temporary,member.id());
        }finally{removeTemporary(temporary);}
    }
    private static void copyRange(Path source,Path target,String id,long offset,long count)throws Exception {
        try(var reader=ParquetFileReader.open(new LocalInputFile(source))) {
            var metadata=reader.getFooter().getFileMetaData();var schema=metadata.getSchema();String kind=target.getFileName().toString().replace(".parquet","");
            try(var writer=LiveParquetCodec.writer(target,schema,kind,id)) {
                long position=0,written=0;var column=new ColumnIOFactory().getColumnIO(schema);
                for(var block:reader.getRowGroups()) {
                    long next=Math.addExact(position,block.getRowCount());if(next<=offset||position>=offset+count){reader.skipNextRowGroup();position=next;continue;}
                    try(var page=reader.readNextRowGroup()) {var records=column.getRecordReader(page,new GroupRecordConverter(schema));for(long j=position;j<next;j++){var row=records.read();if(j>=offset&&j<offset+count){writer.write(row);written++;}}}position=next;
                }
                if(written!=count)throw new IllegalStateException("归档行组范围缺失");
            }
        }
    }
    private static Manifest readManifest(Path directory)throws Exception {
        Path file=directory.resolve("manifest.json");if(Files.isSymbolicLink(directory)||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>128L*1024*1024)throw new IllegalStateException("归档清单缺失或越界");
        var manifest=LiveStorageJson.read(Files.readAllBytes(file),Manifest.class);
        if(!SCHEMA.equals(manifest.schema())||manifest.members().isEmpty()||manifest.members().size()>200000||!manifest.files().keySet().equals(new HashSet<>(ParquetBatchStore.FILES)))throw new IllegalStateException("归档清单格式错误");
        var ids=new HashSet<String>();for(var m:manifest.members())if(!m.id().matches("live_(?:pq_)?[a-f0-9]{64}")||!ids.add(m.id())||m.rows().size()!=4||m.rows().stream().anyMatch(n->n<0))throw new IllegalStateException("归档批次范围错误");return manifest;
    }
    private static Manifest load(Path root,String json,String sha)throws Exception {
        byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);if(!LiveStorageJson.sha256(bytes).equals(sha))throw new IllegalStateException("归档 SQLite 引用摘要不符");
        var expected=LiveStorageJson.read(bytes,Manifest.class);var actual=readManifest(directory(root,expected.date()));
        if(!expected.equals(actual)||!ParquetBatchStore.sha(directory(root,expected.date()).resolve("manifest.json")).equals(sha))throw new IllegalStateException("归档清单与提交记录不同");verify(root,actual);return actual;
    }
    private static void verify(Path root,Manifest m)throws Exception {
        Path dir=directory(root,m.date());
        for(String file:ParquetBatchStore.FILES){Path p=dir.resolve(file);if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!ParquetBatchStore.sha(p).equals(m.files().get(file)))throw new IllegalStateException("归档文件摘要不符");}
    }
    static void recoverCleanup(Connection c,Path root)throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT manifest_json,manifest_sha256 FROM parquet_archives")){while(r.next()){
            var preview=LiveStorageJson.read(r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8),Manifest.class);
            if(preview.members().stream().anyMatch(m->Files.exists(root.resolve("batches").resolve(m.id()),LinkOption.NOFOLLOW_LINKS)))cleanup(root,load(root,r.getString(1),r.getString(2)));
        }}
    }
    static void verifyAll(Connection c,Path root)throws Exception {try(var s=c.createStatement();var r=s.executeQuery("SELECT manifest_json,manifest_sha256 FROM parquet_archives")){while(r.next())load(root,r.getString(1),r.getString(2));}}
    static List<Path> directories(Connection c,Path root)throws Exception {
        var result=new ArrayList<Path>();try(var s=c.createStatement();var r=s.executeQuery("SELECT manifest_json,manifest_sha256 FROM parquet_archives ORDER BY archive_date")){while(r.next()){var m=load(root,r.getString(1),r.getString(2));result.add(directory(root,m.date()));}}return List.copyOf(result);
    }
    private static long cleanup(Path root,Manifest m)throws Exception {
        long removed=0;
        for(var member:m.members()) {
            Path dir=root.resolve("batches").resolve(member.id());if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))continue;
            if(Files.isSymbolicLink(dir))throw new IllegalStateException("旧批次目录是链接");
            try(var entries=Files.list(dir)){if(entries.anyMatch(p->!ParquetBatchStore.FILES.contains(p.getFileName().toString())))throw new IllegalStateException("旧批次含未知文件，停止清理");}
            for(int i=0;i<4;i++){Path file=dir.resolve(ParquetBatchStore.FILES.get(i));if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                if(Files.isSymbolicLink(file)||!ParquetBatchStore.sha(file).equals(member.original().hashes().get(i)))throw new IllegalStateException("旧文件被修改，停止清理");Files.delete(file);removed++;
            }}Files.delete(dir);
        }
        ParquetBatchStore.syncDirectory(root.resolve("batches"));return removed;
    }
    private static void removeTemporary(Path directory)throws Exception {try(var files=Files.list(directory)){for(var file:files.toList())Files.delete(file);}Files.delete(directory);}
}
