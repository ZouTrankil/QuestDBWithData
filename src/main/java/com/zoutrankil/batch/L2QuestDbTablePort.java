package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** QuestDB adapter for the three fixed L2 archive products. Targets are content addressed and isolated. */
public final class L2QuestDbTablePort implements DurableWriter.Port {
    public enum Product { DEALS, ORDERS, QUOTES }
    public record Column(String name,String type,boolean key) {}
    private static final Map<Product,List<Column>> COLUMNS=Map.of(
        Product.DEALS,List.of(new Column("event_ts","TIMESTAMP_NS",true),new Column("ts_code","SYMBOL",true),new Column("source_row_number","LONG",true),new Column("trade_date","STRING",false),new Column("raw_time","INT",false),new Column("deal_id","STRING",true),new Column("bs_flag","STRING",false),new Column("buy_order_id","STRING",false),new Column("sell_order_id","STRING",false),new Column("price_cny","DOUBLE",false),new Column("volume","LONG",false),new Column("side","SYMBOL",false)),
        Product.ORDERS,List.of(new Column("event_ts","TIMESTAMP_NS",true),new Column("ts_code","SYMBOL",true),new Column("source_row_number","LONG",true),new Column("trade_date","STRING",false),new Column("raw_time","INT",false),new Column("order_id","STRING",true),new Column("vendor_type","STRING",false),new Column("vendor_code","STRING",false),new Column("price_cny","DOUBLE",false),new Column("volume","LONG",false),new Column("kuake_order_type","INT",false),new Column("kind","SYMBOL",false),new Column("submission","BOOLEAN",false)),
        Product.QUOTES,quoteColumns());
    private final Product product; private final String table; private final List<Map<String,Object>> rows;
    private final URI endpoint; private final String authorization; private final HttpClient http; private boolean sendReturned;

    public L2QuestDbTablePort(URI endpoint,String authorization,Product product,String table,List<Map<String,Object>> rows) {
        SourceContract.requireTarget(table);
        if(!table.matches("jdb_test_l2_[a-f0-9]{16}_(deals|orders|quotes)")) throw new IllegalArgumentException("L2 target must be an archive-scoped isolated table");
        if(endpoint==null||!Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null||!Set.of("","/").contains(endpoint.getPath())) throw new IllegalArgumentException("QuestDB HTTP origin required");
        if(rows.size()>500) throw new IllegalArgumentException("L2 writes must contain at most 500 rows");
        this.product=Objects.requireNonNull(product);this.table=table;this.rows=List.copyOf(rows);this.endpoint=endpoint;this.authorization=authorization;
        validateRows();
        http=HttpClient.newBuilder().proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }
    public static List<Column> columns(Product product){return COLUMNS.get(product);}
    @Override public void preflight(DurableWriter.Intent intent)throws Exception {
        requireIntent(intent);query(createSql());var schema=query("SELECT * FROM table_columns('"+table+"')");
        var ix=indexes(schema);var data=schema.path("dataset");var columns=COLUMNS.get(product);
        if(data.size()!=columns.size())throw new IllegalStateException("L2 target schema drift");
        for(int i=0;i<columns.size();i++) {var c=columns.get(i);var row=data.get(i);
            if(!c.name().equals(row.get(ix.get("column")).asText())||!c.type().equals(row.get(ix.get("type")).asText())||c.key()!=row.get(ix.get("upsertKey")).asBoolean()||c.name().equals("event_ts")!=row.get(ix.get("designated")).asBoolean())throw new IllegalStateException("L2 target columns, keys or designated timestamp differ from contract");
        }
        if(suspended())throw new IllegalStateException("L2 target WAL suspended");
    }
    @Override public void send(DurableWriter.Intent intent)throws Exception {
        requireIntent(intent);byte[] bytes=encode(product,table,rows);
        if(!java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(java.nio.file.Path.of(intent.artifact()))))throw new IllegalArgumentException("Retained L2 ILP differs from prepared rows");
        if(rows.isEmpty()){sendReturned=true;return;}
        var response=http.send(request(endpoint.resolve("/write?precision=n")).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var body=response.body()){byte[] message=body.readNBytes(4096);if(response.statusCode()!=204)throw new java.io.IOException("QuestDB L2 write outcome unverified: HTTP "+response.statusCode()+" "+new String(message,StandardCharsets.UTF_8).replaceAll("[\\r\\n\\t]"," "));sendReturned=true;}
    }
    @Override public DurableWriter.Proof inspect(DurableWriter.Intent intent)throws Exception {
        requireIntent(intent);if(suspended())return new DurableWriter.Proof(false,false,false,true,"questdb://"+table+"/wal-suspended");
        if(rows.isEmpty()){var count=query("SELECT count() FROM "+table).path("dataset");boolean empty=count.size()==1&&count.get(0).size()==1&&count.get(0).get(0).asLong()==0;return new DurableWriter.Proof(empty,empty,true,false,"questdb://"+table+"/verified-empty");}
        var keys=COLUMNS.get(product).stream().filter(Column::key).toList();String predicate=String.join(" OR ",rows.stream().map(row->"("+String.join(" AND ",keys.stream().map(c->c.name()+"="+literal(c.name().equals("event_ts")?row.get(c.name()).toString():row.get(c.name()).toString())).toList())+")").toList());
        String selected=String.join(",",COLUMNS.get(product).stream().map(c->'"'+c.name()+'"').toList());
        var result=query("SELECT "+selected+" FROM "+table+" WHERE "+predicate+" LIMIT "+(rows.size()+1));
        boolean exact=matches(result.path("dataset"));return new DurableWriter.Proof(exact,exact,sendReturned,false,"questdb://"+table+"/"+intent.batchId());
    }
    public static byte[] encode(Product product,String table,List<Map<String,Object>> rows) {
        SourceContract.requireTarget(table);if(!table.matches("jdb_test_l2_[a-f0-9]{16}_(deals|orders|quotes)"))throw new IllegalArgumentException("L2 target must be isolated");
        var columns=COLUMNS.get(product);var out=new StringBuilder();
        for(var row:rows){out.append(table);var tags=new ArrayList<String>();var fields=new ArrayList<String>();for(var c:columns){Object v=row.get(c.name());if(v==null){if(c.key())throw new IllegalArgumentException("Null L2 key");continue;}if(c.name().equals("event_ts"))continue;
                String value=switch(c.type()){case "SYMBOL"->c.name()+"="+symbol(v.toString());case "STRING"->c.name()+"=\""+escape(v.toString())+"\"";case "DOUBLE"->{double n=((Number)v).doubleValue();if(!Double.isFinite(n))throw new IllegalArgumentException("Finite L2 value required");yield c.name()+"="+Double.toString(n);}case "LONG","INT"->c.name()+"="+((Number)v).longValue()+"i";case "BOOLEAN"->c.name()+"="+v.toString().toLowerCase(Locale.ROOT);default->throw new IllegalArgumentException("Unsupported L2 type");};if(c.type().equals("SYMBOL"))tags.add(value);else fields.add(value);}
            Instant instant=Instant.parse(row.get("event_ts").toString());long nanos=Math.addExact(Math.multiplyExact(instant.getEpochSecond(),1_000_000_000L),instant.getNano());out.append(tags.isEmpty()?"":","+String.join(",",tags)).append(' ').append(String.join(",",fields)).append(' ').append(nanos).append('\n');}
        byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024)throw new IllegalArgumentException("L2 ILP batch exceeds byte budget");return bytes;
    }
    private boolean matches(JsonNode actual) {
        var columns=COLUMNS.get(product);if(!actual.isArray()||actual.size()!=rows.size())return false;var matched=new HashSet<Integer>();
        for(var physical:actual){if(!physical.isArray()||physical.size()!=columns.size())return false;var values=new LinkedHashMap<String,Object>();
            for(int i=0;i<columns.size();i++){var c=columns.get(i);JsonNode value=physical.get(i);if(value.isNull()){values.put(c.name(),null);continue;}if(c.name().equals("event_ts")){try{values.put(c.name(),Instant.parse(value.asText()));}catch(Exception e){return false;}}else if(c.type().equals("DOUBLE")){if(!value.isNumber())return false;values.put(c.name(),value.doubleValue());}else if(Set.of("INT","LONG").contains(c.type())){if(!value.isIntegralNumber())return false;values.put(c.name(),value.longValue());}else if(c.type().equals("BOOLEAN")){if(!value.isBoolean())return false;values.put(c.name(),value.booleanValue());}else {if(!value.isTextual())return false;values.put(c.name(),value.asText());}}
            int found=-1;for(int i=0;i<rows.size();i++){if(matched.contains(i))continue;var expected=rows.get(i);boolean keyMatch=true;for(var c:columns)if(c.key()&&!equal(c,expected.get(c.name()),values.get(c.name()))){keyMatch=false;break;}if(keyMatch){if(found!=-1)return false;found=i;}}
            if(found<0)return false;var expected=rows.get(found);for(var c:columns)if(!equal(c,expected.get(c.name()),values.get(c.name())))return false;matched.add(found);
        }
        return matched.size()==rows.size();
    }
    private static boolean equal(Column c,Object expected,Object actual){if(expected==null)return actual==null;if(actual==null)return false;if(c.name().equals("event_ts")){try{return Instant.parse(expected.toString()).equals(actual instanceof Instant i?i:Instant.parse(actual.toString()));}catch(Exception e){return false;}}if(expected instanceof Number en&&actual instanceof Number an)return new BigDecimal(en.toString()).compareTo(new BigDecimal(an.toString()))==0;return Objects.equals(expected,actual);}
    private void validateRows(){for(var row:rows){if(!row.keySet().equals(new LinkedHashSet<>(COLUMNS.get(product).stream().map(Column::name).toList())))throw new IllegalArgumentException("L2 row fields differ from fixed product schema");if(!row.get("ts_code").toString().matches("[0-9]{6}\\.(SH|SZ|BJ)"))throw new IllegalArgumentException("Invalid L2 symbol");if(!row.get("event_ts").toString().matches("\\d{4}-\\d{2}-\\d{2}T.*Z"))throw new IllegalArgumentException("UTC event timestamp required");}}
    private String createSql(){var cs=COLUMNS.get(product);return "CREATE TABLE IF NOT EXISTS "+table+" ("+String.join(",",cs.stream().map(c->c.name()+" "+c.type()).toList())+") TIMESTAMP(event_ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS("+String.join(",",cs.stream().filter(Column::key).map(Column::name).toList())+")";}
    private void requireIntent(DurableWriter.Intent i){if(!table.equals(i.target())||rows.size()!=i.expectedRows())throw new IllegalArgumentException("Intent does not match L2 target/batch");}
    private boolean suspended()throws Exception{var r=query("SELECT suspended FROM wal_tables() WHERE name='"+table+"'");if(r.path("dataset").size()!=1)throw new IllegalStateException("Expected one L2 WAL target");return r.path("dataset").get(0).get(0).asBoolean();}
    private JsonNode query(String sql)throws Exception{var response=http.send(request(endpoint.resolve("/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofInputStream());try(var body=response.body()){byte[] b=body.readNBytes(32*1024*1024+1);if(response.statusCode()!=200||b.length>32*1024*1024)throw new java.io.IOException("QuestDB L2 query failed/budget exceeded: HTTP "+response.statusCode());var json=Json.MAPPER.readTree(b);if(json==null||json.has("error"))throw new java.io.IOException("QuestDB rejected L2 query");return json;}}
    private HttpRequest.Builder request(URI uri){var b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));if(authorization!=null&&!authorization.isBlank())b.header("Authorization",authorization);return b;}
    private static Map<String,Integer> indexes(JsonNode r){var m=new HashMap<String,Integer>();for(int i=0;i<r.path("columns").size();i++)m.put(r.path("columns").get(i).path("name").asText(),i);return m;}
    private static String literal(String v){return "'"+v.replace("'","''")+"'";}private static String escape(String v){return v.replace("\\","\\\\").replace("\"","\\\"");}private static String symbol(String v){if(v.contains("\n")||v.contains("\r"))throw new IllegalArgumentException("Multiline symbol rejected");return v.replace("\\","\\\\").replace(" ","\\ ").replace(",","\\,").replace("=","\\=");}
    private static List<Column> quoteColumns(){var c=new ArrayList<Column>(List.of(new Column("event_ts","TIMESTAMP_NS",true),new Column("ts_code","SYMBOL",true),new Column("source_row_number","LONG",true),new Column("trade_date","STRING",false),new Column("raw_time","INT",false),new Column("tick_time_diff","INT",false),new Column("price_cny","DOUBLE",false),new Column("volume","LONG",false),new Column("source_turnover","DOUBLE",false),new Column("total_volume","LONG",false),new Column("source_total_turnover","DOUBLE",false),new Column("total_bid_volume","LONG",false),new Column("total_ask_volume","LONG",false),new Column("weighted_bid_price_cny","DOUBLE",false),new Column("weighted_ask_price_cny","DOUBLE",false)));for(int i=1;i<=10;i++){c.add(new Column("bid_price_"+i,"DOUBLE",false));c.add(new Column("bid_volume_"+i,"LONG",false));c.add(new Column("ask_price_"+i,"DOUBLE",false));c.add(new Column("ask_volume_"+i,"LONG",false));}return List.copyOf(c);}
}
