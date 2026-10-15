package com.zoutrankil.data.l2live.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.l2live.domain.AliyunL2WireParser;
import com.zoutrankil.data.l2live.domain.L2CleanEvent;
import com.zoutrankil.data.l2live.domain.L2MinuteAccumulator;
import com.zoutrankil.data.l2live.domain.L2MinuteState;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Fixed v2 owners: retained raw/frozen output, exact readback, then a local checkpoint can commit. */
public final class QuestDbLiveSink {
    public static final String EVENTS_TABLE="l2_live_events_v2";
    public static final String STATES_TABLE="l2_live_micro_state_1m_v2";
    public static final int MAX_BATCH_BYTES=1024*1024;
    private static final int READBACK_KEYS=20, MAX_QUERY_BYTES=4*1024*1024;
    public record Column(String name,String type,boolean key) {}
    public record Receipt(String batchId,int eventRows,int stateRows,String encodingSha256,
            String eventsIdentity,String statesIdentity,Instant verifiedAt) {}
    private record Target(String identity,List<Column> columns,boolean settled) {}
    public static final List<Column> EVENT_COLUMNS=eventColumns();
    public static final List<Column> STATE_COLUMNS=stateColumns();
    private final URI endpoint;
    private final String authorization;
    private final RawJournal journal;
    private final HttpClient http;
    private final Duration visibilityTimeout,pollInterval,requestTimeout;
    private volatile Receipt lastReceipt;

    public QuestDbLiveSink(URI endpoint,String authorization,RawJournal journal,
            Duration visibilityTimeout,Duration pollInterval) {
        this(endpoint,authorization,journal,visibilityTimeout,pollInterval,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }
    public QuestDbLiveSink(URI endpoint,String authorization,RawJournal journal,
            Duration visibilityTimeout,Duration pollInterval,HttpClient http) {
        this.endpoint=Objects.requireNonNull(endpoint);this.authorization=authorization;this.journal=Objects.requireNonNull(journal);
        this.http=Objects.requireNonNull(http);this.visibilityTimeout=Objects.requireNonNull(visibilityTimeout);this.pollInterval=Objects.requireNonNull(pollInterval);
        if(!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null
                ||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!(endpoint.getPath().isEmpty()||endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("QuestDB HTTP origin required");
        if(visibilityTimeout.isZero()||visibilityTimeout.isNegative()||visibilityTimeout.compareTo(Duration.ofMinutes(5))>0
                ||pollInterval.toMillis()<1||pollInterval.compareTo(visibilityTimeout)>0)
            throw new IllegalArgumentException("Finite positive live visibility bounds required");
        requestTimeout=visibilityTimeout.compareTo(Duration.ofSeconds(15))<0?visibilityTimeout:Duration.ofSeconds(15);
    }

    /** Explicit initialization creates only the two reviewed v2 tables, never changes older objects. */
    public synchronized void initializeTargets() throws Exception {
        var identities=new LinkedHashMap<String,String>();
        for(String table:List.of(EVENTS_TABLE,STATES_TABLE)) {
            if(query("SELECT table_name FROM tables() WHERE table_name="+literal(table)).path("dataset").isEmpty()) {
                ddl(createSql(table));
                var created=preflight(table,table.equals(EVENTS_TABLE)?EVENT_COLUMNS:STATE_COLUMNS);
                // 10.0.1 can defer a new table's first ILP transaction; explicit metadata commits establish its writer.
                ddl("ALTER TABLE \""+table+"\" SET PARAM maxUncommittedRows=1");
                ddl("ALTER TABLE \""+table+"\" SET PARAM o3MaxLag=0us");
                requireIdentity(table,table.equals(EVENTS_TABLE)?EVENT_COLUMNS:STATE_COLUMNS,created.identity());
                identities.put(table,created.identity());
            } else identities.put(table,preflight(table,table.equals(EVENTS_TABLE)?EVENT_COLUMNS:STATE_COLUMNS).identity());
        }
        long deadline=System.nanoTime()+visibilityTimeout.toNanos();
        while(true) {
            boolean eventsSettled=requireIdentity(EVENTS_TABLE,EVENT_COLUMNS,identities.get(EVENTS_TABLE)).settled();
            boolean statesSettled=requireIdentity(STATES_TABLE,STATE_COLUMNS,identities.get(STATES_TABLE)).settled();
            if(eventsSettled&&statesSettled)return;
            if(System.nanoTime()>=deadline)throw new IllegalStateException("Live target initialization WAL frontier must settle before publication");
            Thread.sleep(pollInterval.toMillis());
        }
    }
    public Optional<Receipt> lastReceipt() { return Optional.ofNullable(lastReceipt); }

    public synchronized void write(List<L2CleanEvent> events,List<L2MinuteState> states) throws Exception {
        lastReceipt=null;
        var batch=journal.preparedBatch().orElseThrow(()->new IllegalStateException("Freeze a prepared normalization batch before live writes"));
        if(!Arrays.equals(LiveStorageJson.bytes(events),LiveStorageJson.bytes(batch.events()))
                ||!Arrays.equals(LiveStorageJson.bytes(states),LiveStorageJson.bytes(batch.states())))
            throw new IllegalArgumentException("Live output differs from the retained prepared normalization batch");
        var eventRows=events.stream().map(QuestDbLiveSink::eventRow).toList();
        var stateRows=states.stream().map(QuestDbLiveSink::stateRow).toList();
        byte[] encoded=encode(eventRows,stateRows);String encodingSha=LiveStorageJson.sha256(encoded);
        var eventTarget=preflight(EVENTS_TABLE,EVENT_COLUMNS);var stateTarget=preflight(STATES_TABLE,STATE_COLUMNS);
        if(!eventTarget.settled()||!stateTarget.settled()) throw new IllegalStateException("Live target WAL frontier must settle before publication");
        var outbox=journal.reserveOutbox(batch.id(),encodingSha,eventTarget.identity(),stateTarget.identity());
        boolean alreadyVisible=inspect(EVENTS_TABLE,EVENT_COLUMNS,eventRows)&&inspect(STATES_TABLE,STATE_COLUMNS,stateRows);
        if(!alreadyVisible && outbox.delivery().equals("INTENT")) {
            journal.claimUnknown(batch.id()); // FULL SQLite commit precedes all network publication.
            try {
                send(encoded);journal.acknowledge(batch.id());
            } catch(Exception uncertain) {
                if(uncertain instanceof InterruptedException) { Thread.currentThread().interrupt();throw uncertain; }
                // One immutable request may have arrived. Inspect; never send it again automatically.
            }
        }
        long deadline=System.nanoTime()+visibilityTimeout.toNanos();
        while(true) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Live verification cancelled; raw and outbox retained");
            var currentEvents=requireIdentity(EVENTS_TABLE,EVENT_COLUMNS,eventTarget.identity());var currentStates=requireIdentity(STATES_TABLE,STATE_COLUMNS,stateTarget.identity());
            // Physical SELECT refreshes QuestDB's reader telemetry; final WAL checks follow the exact values.
            boolean verified=inspect(EVENTS_TABLE,EVENT_COLUMNS,eventRows)&&inspect(STATES_TABLE,STATE_COLUMNS,stateRows);
            if(verified) {
                // Recheck physical identity/WAL after actual values, including the empty minute-output case.
                if(requireIdentity(EVENTS_TABLE,EVENT_COLUMNS,eventTarget.identity()).settled()
                        &&requireIdentity(STATES_TABLE,STATE_COLUMNS,stateTarget.identity()).settled()) {
                    journal.markWriteVerified(batch.id());
                    lastReceipt=new Receipt(batch.id(),events.size(),states.size(),encodingSha,eventTarget.identity(),stateTarget.identity(),Instant.now());
                    return;
                }
            }
            if(System.nanoTime()>=deadline) throw new IllegalStateException("Live write remains unverified; original raw and prepared checkpoint retained, no automatic resend");
            Thread.sleep(pollInterval.toMillis());
        }
    }

    private void send(byte[] bytes) throws Exception {
        var response=http.send(request(endpoint.resolve("/write?precision=u"))
                .header("Content-Type","text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            body.readNBytes(4096);
            if(response.statusCode()!=204) throw new IllegalStateException("QuestDB live publication outcome unknown: HTTP "+response.statusCode());
        }
    }
    private Target preflight(String table,List<Column> expected) throws Exception {
        var meta=one(query("SELECT id,directoryName,designatedTimestamp,partitionBy,walEnabled,matView,maxUncommittedRows,o3MaxLag,table_suspended,wal_pending_row_count,table_txn,wal_txn FROM tables() WHERE table_name="+literal(table)));
        if(!number(meta,"id").isIntegralNumber()||number(meta,"id").longValue()<0||text(meta,"directoryName").isBlank()
                ||!text(meta,"designatedTimestamp").equals(expected.getFirst().name())||!text(meta,"partitionBy").equals("DAY")
                ||!bool(meta,"walEnabled")||bool(meta,"matView")||bool(meta,"table_suspended")||number(meta,"wal_pending_row_count").longValue()<0)
            throw new IllegalStateException("Live target identity, partition, designated timestamp or WAL is not healthy");
        if(number(meta,"maxUncommittedRows").longValue()!=1||number(meta,"o3MaxLag").longValue()!=0)
            throw new IllegalStateException("Live target low-latency commit parameters differ from reviewed v2 contract");
        var schema=query("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns("+literal(table)+")");
        var indexes=indexes(schema);var rows=schema.path("dataset");var actual=new LinkedHashMap<String,Column>();
        if(!rows.isArray()||rows.size()!=expected.size()) throw new IllegalStateException("Live target schema column count drift");
        for(var row:rows) {
            String name=row.get(indexes.get("column")).textValue(),type=row.get(indexes.get("type")).textValue();
            var key=row.get(indexes.get("upsertKey"));
            if(name==null||type==null||!key.isBoolean()||actual.put(name,new Column(name,type,key.booleanValue()))!=null)
                throw new IllegalStateException("Malformed live target schema");
        }
        for(var column:expected) if(!column.equals(actual.get(column.name()))) throw new IllegalStateException("Live target column/type/upsert key drift: "+column.name());
        var wal=one(query("SELECT suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name="+literal(table)));
        long writer=number(wal,"writerTxn").longValue();
        if(bool(wal,"suspended")||writer<0||number(wal,"sequencerTxn").longValue()<0||number(wal,"bufferedTxnSize").longValue()<0)
            throw new IllegalStateException("Live target WAL is suspended or invalid");
        boolean walSettled=writer==number(wal,"sequencerTxn").longValue()&&number(wal,"bufferedTxnSize").longValue()==0&&number(meta,"wal_pending_row_count").longValue()==0;
        boolean physicalCounters=exactCounter(meta.get("table_txn"),writer)&&exactCounter(meta.get("wal_txn"),writer);
        // 10.0.1 leaves row-transaction telemetry null until rows exist, including after metadata-only WAL commits.
        // Actual emptiness, the settled WAL and the physical reader transaction must all prove this exception.
        if(!physicalCounters&&walSettled) {
            var count=query("SELECT count() FROM \""+table+"\"").path("dataset");
            var refreshed=one(query("SELECT id,directoryName,table_txn,wal_txn FROM tables() WHERE table_name="+literal(table)));
            if(!number(refreshed,"id").equals(number(meta,"id"))||!text(refreshed,"directoryName").equals(text(meta,"directoryName")))
                throw new IllegalStateException("Physical live target changed while refreshing reader telemetry");
            physicalCounters=exactCounter(refreshed.get("table_txn"),writer)&&exactCounter(refreshed.get("wal_txn"),writer);
            if(!physicalCounters&&(exactCounter(refreshed.get("table_txn"),writer)||writer==0&&nullOrZero(refreshed.get("table_txn")))&&nullOrZero(refreshed.get("wal_txn")))
                physicalCounters=count.size()==1&&count.get(0).size()==1&&count.get(0).get(0).isIntegralNumber()&&count.get(0).get(0).longValue()==0;
        }
        boolean settled=walSettled&&physicalCounters;
        String identity=LiveStorageJson.sha256(LiveStorageJson.bytes(List.of(endpoint.toString(),table,
                number(meta,"id").longValue(),text(meta,"directoryName"),expected)));
        return new Target(identity,expected,settled);
    }
    private Target requireIdentity(String table,List<Column> columns,String identity) throws Exception {
        var target=preflight(table,columns);
        if(!target.identity().equals(identity)) throw new IllegalStateException("Physical live target changed after outbox preparation");return target;
    }
    private boolean inspect(String table,List<Column> columns,List<Map<String,Object>> expected) throws Exception {
        boolean complete=true;
        for(int from=0;from<expected.size();from+=READBACK_KEYS) {
            var batch=expected.subList(from,Math.min(expected.size(),from+READBACK_KEYS));
            var byKey=new HashMap<String,Map<String,Object>>();
            for(var row:batch) if(byKey.put(key(columns,row),row)!=null) throw new IllegalArgumentException("Duplicate complete live key");
            String projection=String.join(",",columns.stream().map(c->c.type().equals("TIMESTAMP")
                    ?"cast(\""+c.name()+"\" as long) AS \""+c.name()+"\"":"\""+c.name()+"\"").toList());
            String predicate=String.join(" OR ",batch.stream().map(row->"("+String.join(" AND ",columns.stream().filter(Column::key)
                    .map(c->"\""+c.name()+"\"="+(c.type().equals("TIMESTAMP")?"cast("+row.get(c.name())+" as TIMESTAMP)":Set.of("LONG","INT").contains(c.type())?row.get(c.name()).toString():literal(row.get(c.name()).toString()))).toList())+")").toList());
            var result=query("SELECT "+projection+" FROM \""+table+"\" WHERE "+predicate+" LIMIT "+(batch.size()+1));
            var found=new HashSet<String>();
            if(result.path("dataset").size()>batch.size()) throw new IllegalStateException("Duplicate physical live keys");
            for(var row:result.path("dataset")) {
                if(!row.isArray()||row.size()!=columns.size()) throw new IllegalStateException("Live readback projection drift");
                var values=new LinkedHashMap<String,Object>();
                for(int i=0;i<columns.size();i++) values.put(columns.get(i).name(),readValue(columns.get(i),row.get(i)));
                String key=key(columns,values);var source=byKey.get(key);
                if(source==null||!found.add(key)) throw new IllegalStateException("Unexpected or duplicate live readback key");
                for(var column:columns) if(!Objects.equals(source.get(column.name()),values.get(column.name())))
                    throw new IllegalStateException("Immutable live value conflict: "+column.name());
            }
            if(found.size()!=batch.size()) complete=false;
        }
        return complete;
    }
    private static Object readValue(Column column,JsonNode value) {
        if(value.isNull()) return null;
        return switch(column.type()) {
            case "TIMESTAMP","LONG","INT"->{if(!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalStateException("Exact live integer readback required");yield value.longValue();}
            case "DOUBLE"->{if(!value.isNumber()||!Double.isFinite(value.doubleValue()))throw new IllegalStateException("Finite live numeric readback required");yield value.doubleValue();}
            case "BOOLEAN"->{if(!value.isBoolean())throw new IllegalStateException("Boolean live readback required");yield value.booleanValue();}
            case "STRING","SYMBOL"->{if(!value.isTextual())throw new IllegalStateException("Text live readback required");yield value.textValue();}
            default->throw new IllegalStateException("Unsupported live storage type");
        };
    }
    static Map<String,Object> eventRow(L2CleanEvent event) {
        var raw=event.capture();var row=empty(EVENT_COLUMNS);
        if(!event.fields().keySet().equals(AliyunL2WireParser.FIELD_TYPES.keySet())) throw new IllegalArgumentException("Normalized event field card drift");
        row.put("recv_ts",micros(raw.recvTs()));row.put("capture_id",raw.captureId());row.put("parser_version",AliyunL2WireParser.VERSION);
        row.put("capture_sequence",raw.sequence());row.put("session_id",raw.sessionId());row.put("raw_topic",raw.topic());
        row.put("raw_payload_base64",raw.payloadBytes().length>AliyunL2WireParser.MAX_WIRE_PAYLOAD_BYTES?null:raw.payloadBase64());
        row.put("payload_sha256",LiveStorageJson.sha256(raw.payloadBytes()));row.put("recv_ts_iso",raw.recvTs().toString());row.put("capture_date",raw.captureDate().toString());
        row.put("qos",(long)raw.qos());row.put("retained",raw.retained());row.put("duplicate",raw.duplicate());
        row.put("subscription_authorized",raw.subscriptionAuthorized());
        row.put("symbol",event.symbol().isEmpty()?null:event.symbol());row.put("exchange",event.exchange().isEmpty()?null:event.exchange());row.put("channel",event.channel().isEmpty()?null:event.channel());row.put("trade_date",event.tradeDate().toString());
        row.put("exchange_ts",event.exchangeTs()==null?null:micros(event.exchangeTs()));row.put("exchange_ts_iso",event.exchangeTs()==null?null:event.exchangeTs().toString());
        row.put("parse_status",event.parseStatus());row.put("parse_error",event.parseError());
        row.put("normalized_json",new String(LiveStorageJson.bytes(event.fields()),StandardCharsets.UTF_8));
        for(var c:EVENT_COLUMNS) if(event.fields().containsKey(c.name())) {
            if(c.name().equals("parser_version")&&!Objects.equals(event.fields().get(c.name()),AliyunL2WireParser.VERSION))throw new IllegalArgumentException("Parser version drift");
            row.put(c.name(),normalize(c,event.fields().get(c.name())));
        }
        return row;
    }
    static Map<String,Object> stateRow(L2MinuteState state) {
        if(!state.metrics().keySet().equals(L2MinuteAccumulator.METRIC_TYPES.keySet())) throw new IllegalArgumentException("Minute metric card drift");
        var row=empty(STATE_COLUMNS);row.put("minute_start",micros(state.minuteStart()));row.put("symbol",state.symbol());
        row.put("feature_version",AliyunL2WireParser.VERSION);row.put("revision",state.revision());row.put("trade_date",state.tradeDate().toString());
        row.put("available_at",micros(state.availableAt()));row.put("available_at_iso",state.availableAt().toString());
        row.put("metrics_json",new String(LiveStorageJson.bytes(state.metrics()),StandardCharsets.UTF_8));
        for(var c:STATE_COLUMNS) if(state.metrics().containsKey(c.name())) row.put(c.name(),normalize(c,state.metrics().get(c.name())));
        return row;
    }
    private static Object normalize(Column column,Object value) {
        // QuestDB BOOLEAN has no null representation. JSON keeps nullable wire semantics separately.
        if(value==null)return column.type().equals("BOOLEAN")?false:null;
        return switch(column.type()) {
            case "LONG","INT"->{if(!(value instanceof Number n))throw new IllegalArgumentException("Integer live field required: "+column.name());long v=new BigDecimal(n.toString()).longValueExact();if(column.type().equals("INT")&&(v<Integer.MIN_VALUE||v>Integer.MAX_VALUE))throw new IllegalArgumentException("INT range");yield v;}
            case "DOUBLE"->{if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Finite live field required: "+column.name());yield n.doubleValue();}
            case "STRING","SYMBOL"->{if(!(value instanceof String s)||s.length()>4096||s.indexOf('\0')>=0)throw new IllegalArgumentException("Bounded live text required");yield s;}
            case "BOOLEAN"->{if(!(value instanceof Boolean))throw new IllegalArgumentException("Boolean live field required");yield value;}
            default->throw new IllegalArgumentException("Unknown live field type");
        };
    }
    static byte[] encode(List<Map<String,Object>> events,List<Map<String,Object>> states) {
        if(events.isEmpty()||events.size()>RawJournal.MAX_BATCH_ROWS||states.size()>RawJournal.MAX_BATCH_ROWS) throw new IllegalArgumentException("Bounded live write batch required");
        var result=new StringBuilder();lines(result,EVENTS_TABLE,EVENT_COLUMNS,events);lines(result,STATES_TABLE,STATE_COLUMNS,states);
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BATCH_BYTES)throw new IllegalArgumentException("Live batch exceeds one MiB transport byte budget");return bytes;
    }
    private static void lines(StringBuilder output,String table,List<Column> columns,List<Map<String,Object>> rows) {
        var seen=new HashSet<String>();
        for(var row:rows) {
            if(!seen.add(key(columns,row)))throw new IllegalArgumentException("Duplicate complete live key");
            output.append(table);var tags=new ArrayList<String>();var fields=new ArrayList<String>();
            for(var column:columns) {
                Object value=row.get(column.name());
                if(value==null) { if(column.key())throw new IllegalArgumentException("Null live key");continue; }
                if(column.name().equals(columns.getFirst().name()))continue;
                String encoded=switch(column.type()) {
                    case "SYMBOL"->column.name()+"="+tag(value.toString());
                    case "STRING"->column.name()+"=\""+quoted(value.toString())+"\"";
                    case "LONG","INT"->column.name()+"="+value+"i";
                    case "TIMESTAMP"->column.name()+"="+value+"t";
                    case "DOUBLE"->column.name()+"="+value;
                    case "BOOLEAN"->column.name()+"="+value;
                    default->throw new IllegalArgumentException("Unsupported live field");
                };
                (column.type().equals("SYMBOL")?tags:fields).add(encoded);
            }
            output.append(tags.isEmpty()?"":","+String.join(",",tags)).append(' ').append(String.join(",",fields)).append(' ')
                    .append(row.get(columns.getFirst().name())).append('\n');
        }
    }
    public static String createSql(String table) {
        List<Column> columns=table.equals(EVENTS_TABLE)?EVENT_COLUMNS:table.equals(STATES_TABLE)?STATE_COLUMNS:null;
        if(columns==null)throw new IllegalArgumentException("Only reviewed live v2 tables are allowed");
        return "CREATE TABLE IF NOT EXISTS \""+table+"\" ("+String.join(",",columns.stream().map(c->"\""+c.name()+"\" "+c.type()+(c.name().equals("capture_id")?" NOCACHE":"")).toList())
                +") TIMESTAMP("+columns.getFirst().name()+") PARTITION BY DAY WAL WITH maxUncommittedRows=1,o3MaxLag=0us DEDUP UPSERT KEYS("+String.join(",",columns.stream().filter(Column::key).map(Column::name).toList())+")";
    }
    private static List<Column> eventColumns() {
        var result=new ArrayList<Column>(List.of(new Column("recv_ts","TIMESTAMP",true),new Column("capture_id","SYMBOL",true),new Column("parser_version","SYMBOL",true),
                new Column("capture_sequence","LONG",false),new Column("session_id","STRING",false),new Column("raw_topic","STRING",false),new Column("raw_payload_base64","STRING",false),
                new Column("payload_sha256","STRING",false),new Column("recv_ts_iso","STRING",false),new Column("capture_date","STRING",false),new Column("qos","INT",false),new Column("retained","BOOLEAN",false),new Column("duplicate","BOOLEAN",false),new Column("subscription_authorized","BOOLEAN",false),
                new Column("symbol","SYMBOL",false),new Column("exchange","SYMBOL",false),new Column("channel","SYMBOL",false),new Column("trade_date","STRING",false),new Column("exchange_ts","TIMESTAMP",false),new Column("exchange_ts_iso","STRING",false),
                new Column("parse_status","SYMBOL",false),new Column("parse_error","STRING",false),new Column("normalized_json","STRING",false)));
        AliyunL2WireParser.FIELD_TYPES.forEach((name,type)->{if(!name.equals("parser_version"))result.add(new Column(name,type,false));});
        return checked(result);
    }
    private static List<Column> stateColumns() {
        var result=new ArrayList<Column>(List.of(new Column("minute_start","TIMESTAMP",true),new Column("symbol","SYMBOL",true),new Column("feature_version","SYMBOL",true),new Column("revision","LONG",true),
                new Column("trade_date","STRING",false),new Column("available_at","TIMESTAMP",false),new Column("available_at_iso","STRING",false),new Column("metrics_json","STRING",false)));
        L2MinuteAccumulator.METRIC_TYPES.forEach((name,type)->result.add(new Column(name,type,false)));return checked(result);
    }
    private static List<Column> checked(List<Column> columns) {
        var names=new HashSet<String>();for(var c:columns)if(!c.name().matches("[a-z][a-z0-9_]*")||!names.add(c.name())||!Set.of("TIMESTAMP","SYMBOL","STRING","LONG","INT","DOUBLE","BOOLEAN").contains(c.type()))throw new IllegalArgumentException("Invalid fixed live field card");return List.copyOf(columns);
    }
    private static LinkedHashMap<String,Object> empty(List<Column> columns) { var result=new LinkedHashMap<String,Object>();columns.forEach(c->result.put(c.name(),null));return result; }
    static long micros(Instant value) { return Math.addExact(Math.multiplyExact(value.getEpochSecond(),1_000_000L),value.getNano()/1000); }
    private static String key(List<Column> columns,Map<String,Object> row) { return new String(LiveStorageJson.bytes(columns.stream().filter(Column::key).map(c->Objects.requireNonNull(row.get(c.name()),"Null live key")).toList()),StandardCharsets.UTF_8); }
    private JsonNode query(String sql) throws Exception {return query(sql,true);}
    private JsonNode ddl(String sql) throws Exception {return query(sql,false);}
    private JsonNode query(String sql,boolean datasetRequired) throws Exception {
        var response=http.send(request(endpoint.resolve("/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream body=response.body()) {
            byte[] bytes=body.readNBytes(MAX_QUERY_BYTES+1);
            if(response.statusCode()!=200||bytes.length>MAX_QUERY_BYTES) throw new IllegalStateException("QuestDB live query failed or exceeded budget: HTTP "+response.statusCode());
            var result=LiveStorageJson.MAPPER.readTree(bytes);
            if(result==null||result.has("error")||(datasetRequired?!result.path("dataset").isArray():!result.path("ddl").asText().equals("OK")))
                throw new IllegalStateException("QuestDB live query rejected");return result;
        }
    }
    private HttpRequest.Builder request(URI uri) { var builder=HttpRequest.newBuilder(uri).timeout(requestTimeout);if(authorization!=null&&!authorization.isBlank())builder.header("Authorization",authorization);return builder; }
    private static Map<String,Integer> indexes(JsonNode result) {var indexes=new HashMap<String,Integer>();for(int i=0;i<result.path("columns").size();i++){String name=result.path("columns").get(i).path("name").textValue();if(name==null||indexes.put(name,i)!=null)throw new IllegalStateException("Invalid query projection metadata");}return indexes;}
    private static Map<String,JsonNode> one(JsonNode result) {if(result.path("dataset").size()!=1)throw new IllegalStateException("Exactly one live target metadata row required");var indexes=indexes(result);var row=result.path("dataset").get(0);var value=new HashMap<String,JsonNode>();indexes.forEach((name,index)->value.put(name,row.get(index)));return value;}
    private static JsonNode number(Map<String,JsonNode> row,String name) {var value=row.get(name);if(value==null||!value.isIntegralNumber()||!value.canConvertToLong())throw new IllegalStateException("Missing exact live counter: "+name);return value;}
    private static String text(Map<String,JsonNode> row,String name) {var value=row.get(name);if(value==null||!value.isTextual())throw new IllegalStateException("Missing live metadata text: "+name);return value.textValue();}
    private static boolean bool(Map<String,JsonNode> row,String name) {var value=row.get(name);if(value==null||!value.isBoolean())throw new IllegalStateException("Missing live metadata boolean: "+name);return value.booleanValue();}
    private static boolean exactCounter(JsonNode value,long expected) {return value!=null&&value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()==expected;}
    private static boolean nullOrZero(JsonNode value) {return value!=null&&(value.isNull()||exactCounter(value,0));}
    private static String literal(String value) {return "'"+value.replace("'","''")+"'";}
    private static String quoted(String value) {if(value.indexOf('\n')>=0||value.indexOf('\r')>=0||value.indexOf('\0')>=0)throw new IllegalArgumentException("Multiline live ILP strings forbidden");return value.replace("\\","\\\\").replace("\"","\\\"");}
    private static String tag(String value) {if(value.isEmpty()||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new IllegalArgumentException("Empty or multiline live symbol tag");return value.replace("\\","\\\\").replace(" ","\\ ").replace(",","\\,").replace("=","\\=");}
}
