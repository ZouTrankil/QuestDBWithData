package com.zoutrankil.data.l2live.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuestDbLiveSinkTest {
    @TempDir Path root;

    @Test void independentlyDecodedIlpPreservesTenLevelsUnitsAndExplicitMicrosecondTimes() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var state=minute(event);var batch=journal.prepareBatch(List.of(event),List.of(state),Map.of("revision",1L));
            var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());
            assertEquals("precision=u",server.precision);assertEquals(1,server.posts);
            var physical=server.physical.get(QuestDbLiveSink.EVENTS_TABLE).getFirst();
            assertEquals(Instant.parse("2026-10-13T16:00:00.123456789Z").getEpochSecond()*1_000_000+123_456,physical.get("recv_ts"));
            assertEquals("2026-10-13T16:00:00.123456789Z",physical.get("recv_ts_iso"));
            assertEquals(true,physical.get("subscription_authorized"));assertEquals(false,physical.get("wire_date_inferred"));
            assertTrue(physical.get("normalized_json").toString().contains("\"wire_date_inferred\":null"));
            assertEquals(123400L,physical.get("bid_price_scaled_10"));assertEquals(12.34,physical.get("bid_price_10"));assertEquals(1000L,physical.get("ask_volume_10"));
            assertEquals(QuestDbLiveSink.micros(event.exchangeTs()),physical.get("exchange_ts"));
            assertEquals(1,journal.status().pending());assertNotNull(sink.lastReceipt().orElseThrow());
            journal.completeBatch(batch.id());assertEquals(0,journal.status().pending());
            assertTrue(server.ddl.stream().allMatch(sql->sql.contains("_v2")&&sql.contains("maxUncommittedRows=1,o3MaxLag=0us")));assertEquals(2,server.ddl.size());assertEquals(4,server.initializationParameters.size());
            sink.initializeTargets();assertEquals(2,server.ddl.size());assertEquals(4,server.initializationParameters.size());
        }
    }
    @Test void unknownAfterServerCommitCanVerifyWithoutAnAcknowledgement() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(minute(event)),Map.of("count",1));server.responseStatus=500;
            var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());
            assertEquals(1,server.posts);assertEquals(1,journal.status().completed());
        }
    }
    @Test void unknownBeforeServerCommitIsOnlyInspectedOnRepeatedAttempts() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(minute(event)),Map.of());server.apply=false;server.responseStatus=500;
            var sink=sink(server,journal);sink.initializeTargets();
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertEquals(1,server.posts);assertEquals(1,journal.status().pending());assertTrue(journal.committedAccumulatorState().isEmpty());
            assertThrows(IllegalStateException.class,()->journal.completeBatch(batch.id()));
            server.applyLastBody();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());assertEquals(1,server.posts);
        }
    }
    @Test void partiallyPublishedPairCannotAdvanceCheckpointAndRetainsFrozenRevision() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var state=minute(event);var batch=journal.prepareBatch(List.of(event),List.of(state),Map.of("revision",1L));server.eventsOnly=true;
            var sink=sink(server,journal);sink.initializeTargets();assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertEquals(1,journal.preparedBatch().orElseThrow().states().getFirst().revision());assertEquals(state.availableAt(),journal.preparedBatch().orElseThrow().states().getFirst().availableAt());
            assertThrows(IllegalStateException.class,()->journal.completeBatch(batch.id()));assertEquals(1,journal.status().pending());
            server.eventsOnly=false;server.applyLastBody();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());assertEquals(1,server.posts);
        }
    }
    @Test void pendingWalAfterPostIsPolledUntilSettled() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());server.pendingAfterPost=2;
            var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());
            assertEquals(1,server.posts);assertEquals(1,journal.status().completed());
        }
    }
    @Test void targetSchemaDriftAndSuspensionFailBeforeAnyPublication() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());var sink=sink(server,journal);
            server.existing=true;server.drift=true;assertThrows(IllegalStateException.class,sink::initializeTargets);assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            server.drift=false;server.suspended=true;assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertEquals(0,server.posts);assertEquals(1,journal.status().pending());
        }
    }
    @Test void physicalIdentityChangeAfterPostBlocksVerificationAndRebind() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());var sink=sink(server,journal);sink.initializeTargets();server.replaceAfterPost=true;
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));assertEquals(1,server.posts);assertEquals(1,journal.status().pending());
        }
    }
    @Test void duplicateReadbackOrImmutableValueConflictCannotProduceReceipt() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());var sink=sink(server,journal);sink.initializeTargets();server.duplicateReadback=true;
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));assertTrue(sink.lastReceipt().isEmpty());
            server.duplicateReadback=false;server.physical.get(QuestDbLiveSink.EVENTS_TABLE).getFirst().put("volume",999L);
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));assertEquals(1,server.posts);assertEquals(1,journal.status().pending());
        }
    }
    @Test void rejectedPayloadStillGetsAnAuditableVersionedCaptureRow() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var valid=RawJournalTest.event(journal);var rejected=new L2CleanEvent(valid.capture(),"","","",valid.tradeDate(),null,"rejected","wire_field_count",valid.fields());
            var batch=journal.prepareBatch(List.of(rejected),List.of(),Map.of());var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());
            assertEquals("rejected",server.physical.get(QuestDbLiveSink.EVENTS_TABLE).getFirst().get("parse_status"));assertNull(server.physical.get(QuestDbLiveSink.EVENTS_TABLE).getFirst().get("exchange_ts"));assertEquals(1,journal.status().completed());
        }
    }
    @Test void freshEmptyQuestDbNullCountersRequireActualZeroRowProof() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());server.emptyCounters=true;
            var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());
            assertEquals(1,server.posts);assertEquals(1,journal.status().completed());assertTrue(server.emptyCountReads>0);
        }
    }
    @Test void nullCountersDoNotExcuseNonemptyOrRewrittenExistingTargets() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());server.emptyCounters=true;server.fakeCount=1;
            var sink=sink(server,journal);assertThrows(IllegalStateException.class,sink::initializeTargets);assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));
            assertEquals(0,server.posts);assertEquals(1,journal.status().pending());
        }
    }
    @Test void incompatibleCommitLagFailsBeforePublicationWithoutAlteringExistingTargets() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());server.highLag=true;
            var sink=sink(server,journal);assertThrows(IllegalStateException.class,sink::initializeTargets);
            assertThrows(IllegalStateException.class,()->sink.write(batch.events(),batch.states()));assertEquals(0,server.posts);assertEquals(1,journal.status().pending());
            assertTrue(server.ddl.stream().allMatch(sql->sql.startsWith("CREATE TABLE")));
        }
    }
    @Test void emptyTargetWithCommittedMetadataWalRequiresExactPhysicalTxnAndZeroRows() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            server.metadataOnly=true;var event=bookEvent(journal);var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());
            var sink=sink(server,journal);sink.initializeTargets();assertTrue(server.emptyCountReads>0);
            sink.write(batch.events(),batch.states());journal.completeBatch(batch.id());assertEquals(1,server.posts);assertEquals(0,journal.status().pending());
        }
    }
    @Test void oneMiBRawCanFreezeAndPublishRejectedAuditWithoutLosingReplayBytes() throws Exception {
        try(var journal=new RawJournal(root);var server=new FixtureServer()) {
            byte[] bytes=new byte[1_048_576];bytes[bytes.length-1]=(byte)255;
            var raw=journal.append("shl2/600000/trans",bytes,Instant.parse("2026-10-14T02:00:00.123456789Z"),"s",0,false,false);
            var event=new com.zoutrankil.data.l2live.domain.AliyunL2WireParser().parse(raw);assertFalse(event.isValid());
            var batch=journal.prepareBatch(List.of(event),List.of(),Map.of());var sink=sink(server,journal);sink.initializeTargets();sink.write(batch.events(),batch.states());
            assertNull(server.physical.get(QuestDbLiveSink.EVENTS_TABLE).getFirst().get("raw_payload_base64"));
            assertArrayEquals(bytes,journal.pending(1).getFirst().payloadBytes());journal.completeBatch(batch.id());
            var export=journal.exportJsonl(root.resolve("oversized-raw.jsonl"));var restored=LiveStorageJson.read(java.nio.file.Files.readAllBytes(export.path()),com.zoutrankil.data.l2live.domain.RawCapture.class);
            assertArrayEquals(bytes,restored.payloadBytes());assertEquals(raw.captureId(),restored.captureId());assertEquals(1,journal.status().completed());
        }
    }
    private static QuestDbLiveSink sink(FixtureServer server,RawJournal journal) {
        return new QuestDbLiveSink(server.origin(),null,journal,Duration.ofSeconds(5),Duration.ofMillis(5));
    }
    private static L2CleanEvent bookEvent(RawJournal journal) {
        var event=RawJournalTest.event(journal);var fields=new LinkedHashMap<>(event.fields());fields.put("price_scaled",123400L);fields.put("price",new BigDecimal("12.3400"));fields.put("volume",100L);
        for(int i=1;i<=10;i++){fields.put("bid_price_scaled_"+i,123400L);fields.put("ask_price_scaled_"+i,123500L);fields.put("bid_price_"+i,new BigDecimal("12.3400"));fields.put("ask_price_"+i,new BigDecimal("12.3500"));fields.put("bid_volume_"+i,100L*i);fields.put("ask_volume_"+i,100L*i);}
        return new L2CleanEvent(event.capture(),event.symbol(),event.exchange(),event.channel(),event.tradeDate(),event.exchangeTs(),"ok","",fields);
    }
    private static L2MinuteState minute(L2CleanEvent event) {
        var metrics=new LinkedHashMap<String,Object>();L2MinuteAccumulator.METRIC_TYPES.keySet().forEach(key->metrics.put(key,null));metrics.put("partial",true);metrics.put("volume_1m",100L);metrics.put("amount_1m",new BigDecimal("1234.0000"));
        return new L2MinuteState(event.symbol(),event.tradeDate(),event.exchangeTs().truncatedTo(ChronoUnit.MINUTES),event.capture().recvTs().plusNanos(123),1,metrics);
    }

    /** Test-only loopback peer decodes wire ILP independently and answers physical metadata/readback. */
    private static final class FixtureServer implements AutoCloseable {
        final HttpServer server;final Map<String,List<Map<String,Object>>> physical=new HashMap<>();final List<String> ddl=new ArrayList<>();
        final List<String> initializationParameters=new ArrayList<>();
        final java.util.Set<String> created=new java.util.HashSet<>();
        int posts,responseStatus=204,pendingAfterPost,emptyCountReads,fakeCount;boolean existing,apply=true,eventsOnly,drift,suspended,replaceAfterPost,duplicateReadback,lastPending,emptyCounters,highLag,metadataOnly;
        String precision,lastBody="";
        FixtureServer() throws Exception {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/exec",this::query);server.createContext("/write",this::write);server.start();}
        URI origin(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        private void write(HttpExchange exchange) throws java.io.IOException {
            try {posts++;precision=exchange.getRequestURI().getRawQuery();lastBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(apply)applyLastBody();respond(exchange,responseStatus,new byte[0]);}
            catch(Exception e){respond(exchange,500,"fixture_error".getBytes(StandardCharsets.UTF_8));}
        }
        void applyLastBody() throws Exception {
            for(String line:lastBody.split("\n"))if(!line.isEmpty()) {
                var sections=split(line,' ');String table=split(sections.get(0),',').getFirst();if(eventsOnly&&table.equals(QuestDbLiveSink.STATES_TABLE))continue;
                List<QuestDbLiveSink.Column> card=columns(table);var row=new LinkedHashMap<String,Object>();card.forEach(c->row.put(c.name(),null));
                for(String pair:split(sections.get(0),',').subList(1,split(sections.get(0),',').size())) {int at=pair.indexOf('=');row.put(pair.substring(0,at),pair.substring(at+1));}
                for(String pair:split(sections.get(1),',')) {
                    int at=pair.indexOf('=');String name=pair.substring(0,at),value=pair.substring(at+1);Object parsed;
                    if(value.startsWith("\"")) parsed=LiveStorageJson.MAPPER.readValue(value,String.class);
                    else if(value.endsWith("i")||value.endsWith("t"))parsed=Long.parseLong(value.substring(0,value.length()-1));
                    else if(value.equals("true")||value.equals("false"))parsed=Boolean.parseBoolean(value);
                    else parsed=Double.parseDouble(value);row.put(name,parsed);
                }
                row.put(card.getFirst().name(),Long.parseLong(sections.get(2)));
                var rows=physical.computeIfAbsent(table,t->new ArrayList<>());
                rows.removeIf(old->card.stream().filter(QuestDbLiveSink.Column::key).allMatch(c->java.util.Objects.equals(old.get(c.name()),row.get(c.name()))));rows.add(row);
            }
        }
        private void query(HttpExchange exchange) throws java.io.IOException {
            try {
                String sql=URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring("query=".length()),StandardCharsets.UTF_8);
                String table=sql.contains(QuestDbLiveSink.STATES_TABLE)?QuestDbLiveSink.STATES_TABLE:QuestDbLiveSink.EVENTS_TABLE;List<QuestDbLiveSink.Column> card=columns(table);
                Object response;
                if(sql.startsWith("CREATE TABLE")){ddl.add(sql);created.add(table);response=Map.of("ddl","OK");}
                else if(sql.startsWith("ALTER TABLE")){initializationParameters.add(sql);response=Map.of("ddl","OK");}
                else if(sql.startsWith("SELECT table_name")){response=result(List.of("table_name"),existing||created.contains(table)?List.of(List.of(table)):List.of());}
                else if(sql.contains("FROM table_columns")){
                    var values=new ArrayList<List<Object>>();for(var c:card)values.add(List.of(c.name(),drift&&c.name().equals("capture_id")?"STRING":c.type(),c.key()));response=result(List.of("column","type","upsertKey"),values);
                } else if(sql.startsWith("SELECT count()")) {
                    emptyCountReads++;response=result(List.of("count"),List.of(List.of(fakeCount+physical.getOrDefault(table,List.of()).size())));
                } else if(sql.contains("FROM tables()")) {
                    lastPending=posts>0&&pendingAfterPost-->0;long id=replaceAfterPost&&posts>0?99:table.equals(QuestDbLiveSink.EVENTS_TABLE)?11:12;long base=metadataOnly?2:0;long txn=base+(posts==0||lastPending?0:1);
                    response=result(List.of("id","directoryName","designatedTimestamp","partitionBy","walEnabled","matView","maxUncommittedRows","o3MaxLag","table_suspended","wal_pending_row_count","table_txn","wal_txn"),List.of(java.util.Arrays.asList(id,table+"~"+id,card.getFirst().name(),"DAY",true,false,1,highLag?600_000_000L:0L,suspended,lastPending?1:0,emptyCounters&&posts==0?null:txn,posts==0&&(emptyCounters||metadataOnly)?null:posts==0?0:base+1)));
                } else if(sql.contains("FROM wal_tables()")) {
                    long base=metadataOnly?2:0;response=result(List.of("suspended","writerTxn","sequencerTxn","bufferedTxnSize"),List.of(List.of(suspended,base+(posts==0||lastPending?0:1),base+(posts==0?0:1),lastPending?1:0)));
                } else {
                    var values=new ArrayList<List<Object>>();for(var row:physical.getOrDefault(table,List.of())) {
                        if(card.stream().filter(QuestDbLiveSink.Column::key).allMatch(c->sql.contains(row.get(c.name()).toString()))) {
                            var cells=new ArrayList<Object>();card.forEach(c->cells.add(row.get(c.name())));values.add(cells);if(duplicateReadback)values.add(cells);
                        }
                    }
                    response=result(card.stream().map(QuestDbLiveSink.Column::name).toList(),values);
                }
                respond(exchange,200,LiveStorageJson.bytes(response));
            } catch(Exception e){respond(exchange,500,"fixture_error".getBytes(StandardCharsets.UTF_8));}
        }
        private static List<QuestDbLiveSink.Column> columns(String table){return table.equals(QuestDbLiveSink.EVENTS_TABLE)?QuestDbLiveSink.EVENT_COLUMNS:QuestDbLiveSink.STATE_COLUMNS;}
        private static Map<String,Object> result(List<String> columns,List<?> rows){return Map.of("columns",columns.stream().map(name->Map.of("name",name)).toList(),"dataset",rows);}
        private static List<String> split(String text,char separator) {
            var tokens=new ArrayList<String>();var token=new StringBuilder();boolean quotes=false,escape=false;
            for(char c:text.toCharArray()) {if(escape){token.append(c);escape=false;continue;}if(c=='\\'){token.append(c);escape=true;continue;}if(c=='\"')quotes=!quotes;if(c==separator&&!quotes){tokens.add(token.toString());token.setLength(0);}else token.append(c);}
            tokens.add(token.toString());return tokens;
        }
        private static void respond(HttpExchange exchange,int status,byte[] body) throws java.io.IOException {exchange.sendResponseHeaders(status,status==204?-1:body.length);try(var output=exchange.getResponseBody()){if(status!=204)output.write(body);}exchange.close();}
        @Override public void close(){server.stop(0);}
    }
}
