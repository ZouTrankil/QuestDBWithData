package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Bounded real QuestDB adapter for a registered typed source and one immutable batch. */
public final class QuestDbTablePort implements DurableWriter.Port {
    private final SourceContract contract;
    private final String table;
    private final List<Map<String,Object>> expected;
    private final URI endpoint;
    private final String authorization;
    private final HttpClient http;
    private boolean sendReturned;
    public QuestDbTablePort(URI endpoint,String authorization,SourceContract contract,String table,List<Map<String,Object>> expected) {
        SourceContract.requireTarget(table);
        if(!Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null
                || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !Set.of("","/").contains(endpoint.getPath()))
            throw new IllegalArgumentException("QuestDB HTTP origin required");
        if(expected.isEmpty()&&!contract.emptyAllowed() || expected.size()>contract.writeBatchSize())
            throw new IllegalArgumentException("One bounded batch; emptiness must be allowed by the product contract");
        this.contract=contract; this.table=table; this.expected=List.copyOf(expected); this.endpoint=endpoint; this.authorization=authorization;
        http=HttpClient.newBuilder().proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }
    @Override public void preflight(DurableWriter.Intent intent) throws Exception {
        requireIntent(intent);
        query(contract.createTableSql(table));
        var schema=query("SELECT * FROM table_columns('"+table+"')");
        var fieldIndexes=fieldIndexes(schema);
        var rows=schema.path("dataset");
        if(rows.size()!=contract.columns().size()) throw new IllegalStateException("Target schema drift");
        for(int index=0;index<rows.size();index++) {
            var row=rows.get(index); var column=contract.columns().get(index);
            if(!column.target().equals(row.get(fieldIndexes.get("column")).asText())
                    || !column.type().equals(row.get(fieldIndexes.get("type")).asText())
                    || column.key()!=row.get(fieldIndexes.get("upsertKey")).asBoolean()
                    || column.target().equals(contract.timestampColumn())!=row.get(fieldIndexes.get("designated")).asBoolean())
                throw new IllegalStateException("Target columns, keys or date carrier differ from contract");
        }
        if(suspended()) throw new IllegalStateException("Target WAL suspended");
    }
    @Override public void send(DurableWriter.Intent intent) throws Exception {
        requireIntent(intent);
        byte[] encoded=encode(contract,table,expected);
        // The retained file and actual network bytes must be identical; no TOCTOU re-read during the send.
        if(!Arrays.equals(encoded,Files.readAllBytes(Path.of(intent.artifact())))) throw new IllegalArgumentException("Retained ILP batch differs from prepared rows");
        // Empty snapshots have a typed isolated table and a ledger intent, but no data POST.
        if(expected.isEmpty()) { sendReturned=true;return; }
        var request=request(endpoint.resolve("/write?precision=n")).POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            body.readNBytes(4096);
            if(response.statusCode()!=204) throw new java.io.IOException("QuestDB write outcome unverified: HTTP "+response.statusCode());
            sendReturned=true;
        }
    }
    @Override public DurableWriter.Proof inspect(DurableWriter.Intent intent) throws Exception {
        requireIntent(intent);
        boolean blocked=suspended();
        if(blocked) return new DurableWriter.Proof(false,false,false,true,"questdb://"+table+"/wal-suspended");
        if(expected.isEmpty()) {
            boolean empty=completeSnapshot(0);
            return new DurableWriter.Proof(empty,empty,true,false,"questdb://"+table+"/verified-empty");
        }
        String columns=String.join(",",contract.columns().stream().map(c -> '"'+c.target()+'"').toList());
        String scope;
        if(contract.dataset().equals("fut_basic")) {
            scope="timestamp='1970-01-01T00:00:00.000000Z' AND ts_code IN ("+
                    String.join(",",expected.stream().map(row -> literal(row.get("ts_code").toString())).toList())+")";
        } else if(contract.dataset().equals("etf_basic")) {
            scope="timestamp='1970-01-01T00:00:00.000000Z' AND market='E' AND ts_code IN ("+
                    String.join(",",expected.stream().map(row -> literal(row.get("ts_code").toString())).toList())+")";
        } else if(contract.dataset().equals("ths_index")) {
            scope="update_time="+literal(Instant.parse(expected.getFirst().get("update_time").toString()).toString())+" AND ts_code IN ("+
                    String.join(",",expected.stream().map(row -> literal(row.get("ts_code").toString())).toList())+")";
        } else if(contract.columns().stream().filter(SourceContract.Column::key).count()>2) {
            scope=String.join(" OR ",expected.stream().map(row -> "("+String.join(" AND ",contract.columns().stream()
                    .filter(SourceContract.Column::key).map(c -> '"'+c.target()+"\"="+literal(c.type().startsWith("TIMESTAMP")?
                            instant(row.get(c.target())).toString():row.get(c.target()).toString())).toList())+")").toList());
        } else {
            if((SourceContract.MONTHLY_AGGREGATES.contains(contract.dataset())||SourceContract.QUARTERLY_AGGREGATES.contains(contract.dataset())) && expected.size()>1) {
                scope=contract.logicalDateColumn()+" IN ("+String.join(",",expected.stream()
                        .map(row -> literal(row.get(contract.logicalDateColumn()).toString()+"T00:00:00.000000Z")).toList())+")";
            } else {
                String date=expected.getFirst().get(contract.logicalDateColumn()).toString();
                scope=contract.logicalDateColumn()+"="+literal(date+"T00:00:00.000000Z");
            }
            if(!contract.isMarketAggregate()) {
                String entity=contract.columns().stream().map(SourceContract.Column::target)
                        .filter(c -> c.equals("ts_code") || c.equals("exchange")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Source contract has no bounded entity key"));
                scope+=" AND "+entity+" IN ("+String.join(",",expected.stream().map(r -> literal(r.get(entity).toString())).toList())+")";
            }
        }
        var result=query("SELECT "+columns+" FROM "+table+" WHERE "+scope+" LIMIT "+(expected.size()+1));
        boolean exact=matches(contract,expected,result.path("dataset"));
        // Direct exact visibility of every immutable batch value proves this batch's boundary. Global WAL equality is irrelevant.
        return new DurableWriter.Proof(exact,exact,sendReturned,false,"questdb://"+table+"/"+intent.batchId());
    }
    static boolean matches(SourceContract contract,List<Map<String,Object>> expected,JsonNode actual) {
        if(!actual.isArray() || actual.size()!=expected.size()) return false;
        var byKey=new HashMap<String,Map<String,Object>>();
        for(var row:expected) if(byKey.put(contract.key(row),row)!=null) return false;
        var seen=new HashSet<String>();
        for(var row:actual) {
            if(!row.isArray() || row.size()!=contract.columns().size()) return false;
            var actualKey=new LinkedHashMap<String,Object>();
            for(int index=0;index<contract.columns().size();index++) {
                var column=contract.columns().get(index);if(!column.key()) continue;
                var value=row.get(index);if(value.isNull()) return false;
                Object key=column.type().startsWith("TIMESTAMP")?
                        (contract.dataset().equals("ths_index")?Instant.parse(value.asText()).toString():Instant.parse(value.asText()).atOffset(ZoneOffset.UTC).toLocalDate().toString()):value.asText();
                actualKey.put(column.target(),key);
            }
            String key=contract.key(actualKey);var want=byKey.get(key);
            if(want==null || !seen.add(key)) return false;
            for(int index=0;index<contract.columns().size();index++) {
                var column=contract.columns().get(index);
                if(!equal(column,want.get(column.target()),row.get(index))) return false;
            }
        }
        return true;
    }
    private static String literal(String value) { return "'"+value.replace("'","''")+"'"; }
    public boolean completeSnapshot(long expectedRows) throws Exception {
        if(suspended()) return false;
        var result=query("SELECT count() FROM "+table);
        return result.path("dataset").size()==1 && result.path("dataset").get(0).get(0).asLong()==expectedRows;
    }
    private static boolean equal(SourceContract.Column column,Object expected,JsonNode actual) {
        if(expected==null) return actual.isNull();
        if(actual.isNull()) return false;
        return switch(column.type()) {
            case "TIMESTAMP", "TIMESTAMP_NS" -> Instant.parse(actual.asText()).equals(instant(expected));
            case "DOUBLE" -> actual.isNumber() && Double.compare(((Number)expected).doubleValue(),actual.doubleValue())==0;
            case "LONG","INT" -> actual.isIntegralNumber() && ((Number)expected).longValue()==actual.longValue();
            default -> actual.isTextual() && expected.equals(actual.asText());
        };
    }
    private static Instant instant(Object value) {
        String text=value.toString();return text.length()==10?LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant():Instant.parse(text);
    }
    private void requireIntent(DurableWriter.Intent intent) {
        if(!intent.target().equals(table) || intent.expectedRows()!=expected.size()) throw new IllegalArgumentException("Intent does not match bound target/batch");
    }
    private boolean suspended() throws Exception {
        var response=query("SELECT suspended FROM wal_tables() WHERE name='"+table+"'");
        if(response.path("dataset").size()!=1) throw new IllegalStateException("Expected one WAL target");
        return response.path("dataset").get(0).get(0).asBoolean();
    }
    private static Map<String,Integer> fieldIndexes(JsonNode result) {
        var index=new HashMap<String,Integer>();
        for(int i=0;i<result.path("columns").size();i++) index.put(result.path("columns").get(i).path("name").asText(),i);
        return index;
    }
    private HttpRequest.Builder request(URI uri) {
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
        if(authorization!=null && !authorization.isBlank()) builder.header("Authorization",authorization);
        return builder;
    }
    private JsonNode query(String sql) throws Exception {
        var response=http.send(request(endpoint.resolve("/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()) {
            byte[] bytes=body.readNBytes(32*1024*1024+1);
            if(response.statusCode()!=200 || bytes.length>32*1024*1024) throw new java.io.IOException("QuestDB query failed/budget exceeded: HTTP "+response.statusCode());
            var json=Json.MAPPER.readTree(bytes);
            if(json==null || json.has("error")) throw new java.io.IOException("QuestDB rejected bounded query");
            return json;
        }
    }
    public static byte[] encode(SourceContract contract,String table,List<Map<String,Object>> rows) {
        SourceContract.requireTarget(table); var result=new StringBuilder();
        for(var row:rows) {
            result.append(table); var fields=new ArrayList<String>();
            for(var column:contract.columns()) {
                Object value=row.get(column.target()); if(value==null || column.target().equals(contract.timestampColumn())) continue;
                if(column.type().equals("SYMBOL")) {
                    result.append(',').append(column.target()).append('=').append(symbol(value.toString())); continue;
                }
                String encoded=switch(column.type()) {
                    case "DOUBLE" -> Double.toString(((Number)value).doubleValue());
                    case "LONG","INT" -> ((Number)value).longValue()+"i";
                    case "TIMESTAMP" -> {
                        Instant timestamp=instant(value);
                        yield Math.addExact(Math.multiplyExact(timestamp.getEpochSecond(),1_000_000L),timestamp.getNano()/1000)+"t";
                    }
                    case "STRING" -> "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"")+"\"";
                    default -> throw new IllegalArgumentException("Unsupported ILP column");
                };
                fields.add(column.target()+"="+encoded);
            }
            if(fields.isEmpty()) throw new IllegalArgumentException("ILP requires at least one value field");
            String timestampValue=row.get(contract.timestampColumn()).toString();
            long nanos=timestampValue.contains("T")
                    ? Instant.parse(timestampValue).getEpochSecond()*1_000_000_000L+Instant.parse(timestampValue).getNano()
                    : Math.multiplyExact(LocalDate.parse(timestampValue).atStartOfDay(ZoneOffset.UTC).toEpochSecond(),1_000_000_000L);
            result.append(' ').append(String.join(",",fields)).append(' ').append(nanos).append('\n');
        }
        byte[] bytes=result.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>4*1024*1024) throw new IllegalArgumentException("ILP batch exceeds byte budget");
        return bytes;
    }
    private static String symbol(String value) {
        if(value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Multiline symbol rejected");
        return value.replace("\\","\\\\").replace(" ","\\ ").replace(",","\\,").replace("=","\\=");
    }
}
