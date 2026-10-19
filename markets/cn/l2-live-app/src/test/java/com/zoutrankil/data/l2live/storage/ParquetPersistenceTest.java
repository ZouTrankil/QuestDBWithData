package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import static org.junit.jupiter.api.Assertions.*;

class ParquetPersistenceTest {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-10-14T01:30:00.123456789Z");

    static L2CleanEvent append(RawJournal journal,String topic,String wire) {
        return new AliyunL2WireParser().parse(journal.append(topic,wire.getBytes(StandardCharsets.UTF_8),NOW,"parquet-test",0,false,false,true));
    }
    static String market() {
        var fields=new ArrayList<>(List.of("510500.SH","93000000","15450","951200","1469604","63","15450","15450","15450","15430"));
        for(int i=0;i<10;i++)fields.add(""+(15460+i*10));for(int i=0;i<10;i++)fields.add(""+(1000+i));
        for(int i=0;i<10;i++)fields.add(""+(15450-i*10));for(int i=0;i<10;i++)fields.add(""+(2000+i));
        fields.addAll(List.of("15870","15150","4175384","5570300","16970","13890"));return String.join(",",fields);
    }
    @Test void realMarketTradeAndOrderBecomeColumnsAndRestoreNanosecondsAndExactNumbers() throws Exception {
        String id;
        try(var journal=new RawJournal(root)) {
            var events=List.of(append(journal,"market/510500",market()),
                    append(journal,"trans/510500","510500.SH,B,93000001,9007199254740993,15460,1000,9007199254740995,9223372036854775808"),
                    append(journal,"order/600000","600000.SH,20261014,93000002,0,46177,D,S,98500,8400"),
                    append(journal,"market/510500","invalid\0wire"));
            assertTrue(events.get(0).isValid());assertTrue(events.get(1).isValid());assertTrue(events.get(2).isValid());assertFalse(events.get(3).isValid());
            var accumulator=new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.fixed(NOW,ZoneOffset.UTC));
            var states=new ArrayList<L2MinuteState>();for(var event:events)if(event.isValid())accumulator.accept(event).ifPresent(states::add);
            var batch=journal.prepareBatch(events,states,accumulator.snapshot());id=batch.id();
            new LocalLiveSink(journal).write(batch.events(),batch.states());journal.completeBatch(id);
            Path dir=root.resolve("parquet/batches").resolve(id);
            for(String file:ParquetBatchStore.FILES)assertTrue(Files.isRegularFile(dir.resolve(file)));
            try(var reader=ParquetFileReader.open(new LocalInputFile(dir.resolve("events.parquet")))) {
                var schema=reader.getFooter().getFileMetaData().getSchema();
                assertEquals(4,reader.getRecordCount());assertFalse(schema.containsField("fields_json"));assertFalse(schema.containsField("payload"));
                assertTrue(schema.getType("price").toString().contains("DECIMAL(38,4)"));assertTrue(schema.containsField("bid_price_10"));
                assertTrue(schema.getType("exchange_ts").toString().contains("NANOS"));
            }
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+journal.databasePath());var q=c.createStatement();var r=q.executeQuery("SELECT length(payload) FROM prepared_batch UNION ALL SELECT length(payload) FROM committed_accumulator")) {
                while(r.next())assertEquals(0,r.getInt(1),"SQLite must reference Parquet instead of retaining a JSON data payload");
            }
            var restored=journal.storedBatch(id);assertEquals(NOW,restored.events().getFirst().capture().recvTs());
            assertArrayEquals("invalid\0wire".getBytes(StandardCharsets.UTF_8),restored.events().getLast().capture().payloadBytes());
            assertEquals(new BigDecimal("1.5360"),restored.events().getFirst().fields().get("bid_price_10"));
            assertEquals(9007199254740993L,restored.events().get(1).fields().get("sub_seq"));
            assertEquals("9223372036854775808",restored.events().get(1).fields().get("buy_order_id"));
            var recovered=new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.systemUTC());recovered.restore(journal.committedAccumulatorState());
            assertEquals(accumulator.snapshot(),recovered.snapshot());
        }
        try(var journal=new RawJournal(root)) {assertTrue(journal.preparedBatch().isEmpty());assertEquals(4,journal.status().completed());assertEquals(4,journal.storedBatch(id).events().size());}
    }
    @Test void publishedButUncommittedBatchIsReusedAfterRestart() throws Exception {
        String id;String hash;
        try(var journal=new RawJournal(root)) {
            var batch=journal.prepareBatch(List.of(RawJournalTest.event(journal)),List.of(),Map.of("count",1L));id=batch.id();
            new LocalLiveSink(journal).write(batch.events(),batch.states());
            hash=ParquetBatchStore.sha(root.resolve("parquet/batches").resolve(id).resolve("events.parquet"));
            assertEquals(1,journal.status().pending());
        }
        try(var journal=new RawJournal(root)) {
            var batch=journal.preparedBatch().orElseThrow();new LocalLiveSink(journal).write(batch.events(),batch.states());journal.completeBatch(id);
            assertEquals(hash,ParquetBatchStore.sha(root.resolve("parquet/batches").resolve(id).resolve("events.parquet")));
            try(var files=Files.list(root.resolve("parquet/batches"))){assertEquals(1,files.count());}
            assertEquals(1L,journal.committedAccumulatorState().get("count"));
        }
    }
    @Test void corruptionOrMissingParquetNeverAdvancesRawCheckpoint() throws Exception {
        try(var journal=new RawJournal(root)) {
            var batch=journal.prepareBatch(List.of(RawJournalTest.event(journal)),List.of(),Map.of());
            Files.write(root.resolve("parquet/.pending").resolve(batch.id()).resolve("events.parquet"),new byte[]{1,2,3});
            assertThrows(IllegalStateException.class,journal::preparedBatch);
            assertThrows(IllegalStateException.class,()->journal.completeBatch(batch.id()));
            assertEquals(1,journal.pending(1).size());assertTrue(journal.committedAccumulatorState().isEmpty());
        }
    }
    @Test void schemaTwoHistoryMigratesIdempotentlyAndDropsJsonPayloadsOnlyAfterParquetReadback() throws Exception {
        String id=legacy(true,false,true);
        try(var journal=new RawJournal(root)) {
            assertEquals(1,journal.migrateParquet());assertEquals(0,journal.migrateParquet());
            assertEquals(1,journal.status().completed());assertEquals(7L,journal.committedAccumulatorState().get("count"));
            var retained=journal.storedBatch(id);assertEquals(1,retained.events().size());assertEquals(1,retained.states().size());
            assertInstanceOf(Double.class,retained.events().getFirst().fields().get("obi_top1"));
            assertInstanceOf(Double.class,retained.states().getFirst().metrics().get("obi_top1"));
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("raw-journal.sqlite3"));var s=c.createStatement();var r=s.executeQuery("SELECT length(payload) FROM prepared_batch UNION ALL SELECT length(payload) FROM committed_accumulator")) {
            while(r.next())assertEquals(0,r.getLong(1));
        }
    }
    @Test void pendingLegacyLocalBatchResumesToParquetButRemoteOutboxRemainsUnchanged() throws Exception {
        String id=legacy(false,false);
        try(var journal=new RawJournal(root)) {
            var batch=journal.preparedBatch().orElseThrow();new LocalLiveSink(journal).write(batch.events(),batch.states());journal.completeBatch(id);
            assertEquals(7L,journal.committedAccumulatorState().get("count"));
        }
        Path other=root.resolve("remote");Files.createDirectories(other);Path prior=root;root=other;
        try {
            String remote=legacy(false,true);
            try(var journal=new RawJournal(root)) {
                assertThrows(IllegalStateException.class,journal::migrateParquet);
                assertEquals(remote,journal.preparedBatch().orElseThrow().id());assertEquals(1,journal.pending(1).size());
            }
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("raw-journal.sqlite3"));var s=c.createStatement();var r=s.executeQuery("SELECT events_identity,delivery FROM live_outbox")) {assertTrue(r.next());assertEquals("remote-target",r.getString(1));assertEquals("UNKNOWN",r.getString(2));}
        } finally {root=prior;}
    }
    @Test void excessPrecisionCannotBeSilentlyRoundedIntoADoubleColumn() throws Exception {
        try(var journal=new RawJournal(root)) {
            var event=append(journal,"market/510500",market());var fields=new LinkedHashMap<>(event.fields());
            fields.put("obi_top1",new BigDecimal("0.123456789012345678901"));
            var invalid=new L2CleanEvent(event.capture(),event.symbol(),event.exchange(),event.channel(),event.tradeDate(),event.exchangeTs(),event.parseStatus(),event.parseError(),fields);
            assertThrows(IllegalArgumentException.class,()->journal.prepareBatch(List.of(invalid),List.of(),Map.of()));
            assertEquals(1,journal.pending(1).size());assertTrue(journal.preparedBatch().isEmpty());
        }
    }
    @Test void rawExportStreamsBeyondProcessingBatchLimitAndSupportsAnEmptyJournal() throws Exception {
        try(var journal=new RawJournal(root)) {
            var empty=journal.exportParquet(root.resolve("empty.parquet"));assertEquals(0,empty.captures());
            for(int i=0;i<257;i++)journal.append("topic",new byte[]{(byte)i},NOW,"export",0,false,false,true);
            var output=journal.exportParquet(root.resolve("all.parquet"));assertEquals(257,output.captures());assertEquals(257,output.lastSequence());
            try(var reader=ParquetFileReader.open(new LocalInputFile(output.path()))){assertEquals(257,reader.getRecordCount());}
            assertEquals(257,journal.status().pending());
        }
    }
    @Test void receptionCanAppendWhileTheProcessorFreezesAndPublishesParquet() throws Exception {
        try(var journal=new RawJournal(root);var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var event=RawJournalTest.event(journal);
            var producer=executor.submit(()->{for(int i=0;i<64;i++)journal.append("concurrent",new byte[]{(byte)i},NOW,"receive",0,false,false,true);});
            var batch=journal.prepareBatch(List.of(event),List.of(),Map.of("count",1L));
            new LocalLiveSink(journal).write(batch.events(),batch.states());journal.completeBatch(batch.id());producer.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(65,journal.status().captures());assertEquals(1,journal.status().completed());assertEquals(64,journal.status().pending());
            assertEquals(64,journal.pending(200).stream().map(RawCapture::sequence).distinct().count());
        }
    }
    private String legacy(boolean completed,boolean remote) throws Exception {return legacy(completed,remote,false);}
    private String legacy(boolean completed,boolean remote,boolean realMarket) throws Exception {
        Path db;L2CleanEvent event;var states=new ArrayList<L2MinuteState>();
        try(var journal=new RawJournal(root)){db=journal.databasePath();event=realMarket?append(journal,"market/510500",market()):RawJournalTest.event(journal);}
        if(realMarket)new L2MinuteAccumulator(Duration.ofSeconds(2),10,Clock.fixed(NOW,ZoneOffset.UTC)).accept(event).ifPresent(states::add);
        var body=new RawJournal.FrozenData(List.of(event),states,Map.of("count",7L));byte[] bytes=LiveStorageJson.bytes(body);
        String hash=LiveStorageJson.sha256(bytes),id="live_"+hash;
        String identity=remote?"remote-target":LiveStorageJson.sha256(LiveStorageJson.bytes(List.of("local-sqlite-v1",db.toString())));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            try(var s=c.createStatement()){s.execute("DROP TABLE parquet_batches");s.execute("PRAGMA user_version=2");}
            try(var s=c.prepareStatement("INSERT INTO prepared_batch VALUES(?,?,?,?,?,?,?)")) {s.setString(1,id);s.setBytes(2,bytes);s.setString(3,hash);s.setInt(4,completed?0:1);s.setInt(5,completed?1:0);s.setString(6,NOW.toString());s.setString(7,completed?NOW.toString():null);s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO live_outbox VALUES(?,?,?,?,?)")) {s.setString(1,id);s.setString(2,LiveStorageJson.sha256(LiveStorageJson.bytes(List.of(body.events(),body.states()))));s.setString(3,identity);s.setString(4,identity);s.setString(5,completed?"VERIFIED":"UNKNOWN");s.executeUpdate();}
            if(completed) {
                byte[] checkpoint=LiveStorageJson.bytes(new RawJournal.Snapshot(body.accumulatorState()));
                try(var s=c.prepareStatement("INSERT INTO committed_accumulator VALUES(1,?,?,?)")){s.setBytes(1,checkpoint);s.setString(2,LiveStorageJson.sha256(checkpoint));s.setString(3,id);s.executeUpdate();}
                try(var s=c.createStatement()){s.executeUpdate("UPDATE raw_capture SET completed=1");}
            }
        }
        return id;
    }
}
