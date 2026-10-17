package com.zoutrankil.data.l2live.storage;

import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RawJournalTest {
    @TempDir Path root;
    private static final Instant TIME=Instant.parse("2026-10-13T16:00:00.123456789Z");

    @Test void originalBytesIdentityAndNanosecondsSurviveRestart() {
        byte[] bytes={0,(byte)255,10,13,34,92};String id;
        try(var journal=new RawJournal(root)) {
            var capture=journal.append("shl2/600000/trans",bytes,TIME,"session:one",1,true,true);id=capture.captureId();
            bytes[0]=99;assertEquals(1,capture.sequence());assertEquals("2026-10-14",capture.captureDate().toString());
            assertArrayEquals(new byte[]{0,(byte)255,10,13,34,92},capture.payloadBytes());
        }
        try(var journal=new RawJournal(root)) {
            var restored=journal.pending(1).getFirst();assertEquals(id,restored.captureId());assertEquals(TIME,restored.recvTs());
            assertArrayEquals(new byte[]{0,(byte)255,10,13,34,92},restored.payloadBytes());
            assertTrue(restored.retained());assertTrue(restored.duplicate());assertEquals(1,journal.status().pending());
        }
    }
    @Test void separateReceiveEnvelopesKeepSeparateStableIdsEvenWithIdenticalWireBytes() {
        try(var journal=new RawJournal(root)) {
            var a=journal.append("same",new byte[]{1},TIME,"s",0,false,false);
            var b=journal.append("same",new byte[]{1},TIME,"s",0,false,true);
            assertNotEquals(a.captureId(),b.captureId());assertEquals(a.sequence()+1,b.sequence());
            assertEquals(List.of(a,b),journal.pending(2));
        }
    }
    @Test void receivedSubscriptionAuthorizationIsFrozenAndLegacyAppendFailsClosed() {
        try(var journal=new RawJournal(root)) {
            var before=journal.append("same",new byte[]{1},TIME,"old-session",0,false,false);
            var after=journal.append("same",new byte[]{1},TIME,"new-session",0,false,false,true);
            assertFalse(before.subscriptionAuthorized());assertTrue(after.subscriptionAuthorized());
        }
        try(var journal=new RawJournal(root)) {
            var restored=journal.pending(2);assertFalse(restored.get(0).subscriptionAuthorized());assertTrue(restored.get(1).subscriptionAuthorized());
        }
    }
    @Test void oldJournalSchemaIsRejectedWithoutChangingItsEnvelopeHashes() throws Exception {
        Path database;
        try(var journal=new RawJournal(root)) {event(journal);database=journal.databasePath();}
        String retainedHash;
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
            s.execute("PRAGMA user_version=1");try(var r=s.executeQuery("SELECT envelope_sha256 FROM raw_capture")) {r.next();retainedHash=r.getString(1);}
        }
        assertThrows(IllegalArgumentException.class,()->new RawJournal(root));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
            try(var r=s.executeQuery("PRAGMA user_version")) {r.next();assertEquals(1,r.getInt(1));}
            try(var r=s.executeQuery("SELECT envelope_sha256 FROM raw_capture")) {r.next();assertEquals(retainedHash,r.getString(1));}
        }
    }
    @Test void preparedBatchFreezesOutputAndAccumulatorWithoutPrematureCheckpoint() {
        String id;
        try(var journal=new RawJournal(root)) {
            var event=event(journal);var snapshot=new LinkedHashMap<String,Object>();snapshot.put("count",1L);
            var batch=journal.prepareBatch(List.of(event),List.of(),snapshot);id=batch.id();snapshot.put("count",99L);
            assertEquals(1L,batch.accumulatorState().get("count"));assertEquals(batch.id(),journal.prepareBatch(List.of(event),List.of(),Map.of("count",1L)).id());
            assertThrows(IllegalStateException.class,()->journal.completeBatch(batch.id()));
            assertTrue(journal.committedAccumulatorState().isEmpty());assertEquals(1,journal.status().pending());
        }
        try(var journal=new RawJournal(root)) {
            var batch=journal.preparedBatch().orElseThrow();assertEquals(id,batch.id());assertEquals(1L,batch.accumulatorState().get("count"));
            assertTrue(batch.events().getFirst().isValid());assertEquals(1,journal.pending(2).size());
        }
    }
    @Test void verifiedCommitAtomicallyAdvancesAllRawsAndAccumulatorAndIsIdempotent() throws Exception {
        try(var journal=new RawJournal(root)) {
            var a=event(journal);var b=event(journal);var batch=journal.prepareBatch(List.of(a,b),List.of(),Map.of("count",2L));
            new LocalLiveSink(journal).write(batch.events(),batch.states());
            journal.completeBatch(batch.id());journal.completeBatch(batch.id());
            assertTrue(journal.pending(2).isEmpty());assertTrue(journal.preparedBatch().isEmpty());assertEquals(2L,journal.committedAccumulatorState().get("count"));
            assertEquals(2,journal.status().completed());assertEquals(2,journal.status().captures());
        }
        try(var journal=new RawJournal(root)) {assertEquals(2L,journal.committedAccumulatorState().get("count"));assertEquals(0,journal.status().pending());}
    }
    @Test void activePreparedBatchRejectsReplacementAndForeignCapture() {
        try(var journal=new RawJournal(root);var other=new RawJournal(root.resolve("other"))) {
            var first=event(journal);journal.prepareBatch(List.of(first),List.of(),Map.of("count",1));
            assertThrows(IllegalStateException.class,()->journal.prepareBatch(List.of(first),List.of(),Map.of("count",2)));
            assertThrows(IllegalArgumentException.class,()->other.prepareBatch(List.of(first),List.of(),Map.of()));
            var own=event(other);assertThrows(IllegalArgumentException.class,()->other.prepareBatch(List.of(own,own),List.of(),Map.of()));
        }
    }
    @Test void outboxCannotRebindEncodingOrPhysicalTargetOnRestart() {
        try(var journal=new RawJournal(root)) {
            var batch=journal.prepareBatch(List.of(event(journal)),List.of(),Map.of());
            journal.reserveOutbox(batch.id(),"a".repeat(64),"b".repeat(64),"c".repeat(64));journal.claimUnknown(batch.id());
            assertEquals("UNKNOWN",journal.reserveOutbox(batch.id(),"a".repeat(64),"b".repeat(64),"c".repeat(64)).delivery());
            assertThrows(IllegalStateException.class,()->journal.reserveOutbox(batch.id(),"d".repeat(64),"b".repeat(64),"c".repeat(64)));
            assertThrows(IllegalStateException.class,()->journal.claimUnknown(batch.id()));
        }
    }
    @Test void rawEnvelopeMutationIsDetectedEvenWhenPayloadHashIsUnchanged() throws Exception {
        Path database;
        try(var journal=new RawJournal(root)) {event(journal);database=journal.databasePath();}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {s.executeUpdate("UPDATE raw_capture SET recv_ts='2026-10-13T16:00:00.123456Z'");}
        try(var journal=new RawJournal(root)) {assertThrows(IllegalStateException.class,()->journal.pending(1));}
    }
    @Test void quotaFailsBeforeLosingOrCompletingAnEarlierRaw() {
        try(var journal=new RawJournal(root,1_048_576)) {
            journal.append("one",new byte[600_000],TIME,"s",0,false,false);
            assertThrows(IllegalStateException.class,()->journal.append("two",new byte[600_000],TIME,"s",0,false,false));
            assertEquals(1,journal.status().captures());assertEquals(600_000,journal.status().rawBytes());assertEquals(1,journal.status().pending());
        }
    }
    @Test void parquetQuotaFailureKeepsTheEarlierRawPending() {
        try(var journal=new RawJournal(root,1_048_576)) {
            byte[] wire=new byte[700_000],checkpoint=new byte[600_000];new java.util.Random(1).nextBytes(wire);new java.util.Random(2).nextBytes(checkpoint);
            var capture=journal.append("wire",wire,TIME,"s",0,false,false);
            var fields=new LinkedHashMap<String,Object>();AliyunL2WireParser.FIELD_TYPES.keySet().forEach(key->fields.put(key,null));fields.put("parser_version","v2");
            var event=new L2CleanEvent(capture,"600000.SH","SH","trans",capture.captureDate(),TIME,"ok","",fields);
            assertThrows(IllegalStateException.class,()->journal.prepareBatch(List.of(event),List.of(),Map.of("uncompressed_checkpoint",java.util.Base64.getEncoder().encodeToString(checkpoint))));
            assertEquals(capture,journal.pending(1).getFirst());assertTrue(journal.preparedBatch().isEmpty());
        }
    }
    @Test void exportRetainsReplayableRawBytesAndDoesNotOverwriteEvidence() throws Exception {
        try(var journal=new RawJournal(root)) {
            var capture=event(journal).capture();var destination=root.resolve("export/raw.parquet");var evidence=journal.exportParquet(destination);
            var restored=LiveParquetCodec.readRaw(destination).getFirst();
            assertEquals(capture,restored);assertEquals(1,evidence.captures());assertEquals(capture.sequence(),evidence.lastSequence());
            assertEquals(64,evidence.sha256().length());assertThrows(IllegalArgumentException.class,()->journal.exportParquet(destination));
            assertEquals(1,journal.status().pending());
        }
    }
    @Test void existingUnrelatedDatabaseIsRejectedBeforeChangingItsSchema() throws Exception {
        Path database=root.resolve("raw-journal.sqlite3");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {s.execute("CREATE TABLE unrelated(value TEXT)");s.execute("INSERT INTO unrelated VALUES('retained')");}
        assertThrows(IllegalArgumentException.class,()->new RawJournal(root));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement();var r=s.executeQuery("SELECT value FROM unrelated")) {assertTrue(r.next());assertEquals("retained",r.getString(1));}
    }
    static L2CleanEvent event(RawJournal journal) {
        var capture=journal.append("shl2/600000/trans","original-wire".getBytes(java.nio.charset.StandardCharsets.UTF_8),TIME,"session",0,false,false,true);
        var fields=new LinkedHashMap<String,Object>();AliyunL2WireParser.FIELD_TYPES.keySet().forEach(key->fields.put(key,null));fields.put("parser_version",AliyunL2WireParser.VERSION);
        return new L2CleanEvent(capture,"600000.SH","SH","trans",capture.captureDate(),TIME,"ok","",fields);
    }
}
