import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.table.MarketSentimentDailyRow;
import com.zoutrankil.data.repository.MarketSentimentDailyWritePort;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Audit artifact: local SELECT-only, no Spring application, provider, publisher or tests. */
public class PersistentTailReadAudit {
  static final ObjectMapper JSON=JobDefinitionJson.mapper();
  static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static final List<Map<String,Object>> QUERIES=new ArrayList<>();
  static JsonNode query(String sql)throws Exception {
    var uri=URI.create("http://127.0.0.1:9000/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8));
    var response=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build(),HttpResponse.BodyHandlers.ofString());
    var node=JSON.readTree(response.body());if(response.statusCode()!=200||node.has("error"))throw new IllegalStateException("Read-only SQL failed: "+response.body());
    QUERIES.add(Map.of("sql",sql,"result",node));return node;
  }
  static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
  static List<MarketSentimentDailyRow> marketRows(String table)throws Exception {
    if(!table.matches("market_sentiment_daily|java_d121_market_sentiment_daily_backup_[a-f0-9]{32}"))throw new IllegalArgumentException("Unexpected table");
    var columns=MarketSentimentDailyWritePort.COLUMNS;
    String projection=String.join(",",columns.stream().map(c->c.equals("trade_date")||c.equals("updated_at")?"cast("+c+" AS LONG) AS "+c:c).toList());
    var data=query("SELECT "+projection+" FROM "+table+" ORDER BY trade_date LIMIT 10001");
    if(data.path("columns").size()!=53||data.path("dataset").size()>10000)throw new IllegalStateException("Expected bounded 53-column market data");
    var out=new ArrayList<MarketSentimentDailyRow>();
    for(var values:data.path("dataset")){var map=new LinkedHashMap<String,Object>();for(int i=0;i<53;i++){
      String key=columns.get(i);if(!key.equals(data.path("columns").get(i).path("name").asText()))throw new IllegalStateException("Column mismatch");
      JsonNode value=values.get(i);Object typed=value.isNull()?null:key.equals("trade_date")||key.equals("updated_at")?MarketSentimentDailyWritePort.fromMicros(value.longValue()):value.isBoolean()?value.booleanValue():value.isNumber()?value.doubleValue():value.asText();map.put(key,typed);
    }out.add(MarketSentimentDailyWritePort.row(map));}return List.copyOf(out);
  }
  static List<String> differences(List<MarketSentimentDailyRow> expected,List<MarketSentimentDailyRow> actual){
    var out=new ArrayList<String>();var byDate=new HashMap<Instant,MarketSentimentDailyRow>();for(var row:actual)if(byDate.put(row.tradeDate(),row)!=null)out.add("duplicate:"+row.tradeDate());
    for(var row:expected){var other=byDate.remove(row.tradeDate());if(other==null){out.add("missing:"+row.tradeDate());continue;}var a=MarketSentimentDailyWritePort.values(row);var b=MarketSentimentDailyWritePort.values(other);for(String c:MarketSentimentDailyWritePort.COLUMNS){Object x=a.get(c),y=b.get(c);boolean same=x instanceof Double d&&y instanceof Double e?Double.doubleToRawLongBits(d)==Double.doubleToRawLongBits(e):Objects.equals(x,y);if(!same)out.add(row.tradeDate()+":"+c+":"+x+"/"+y);}}
    byDate.keySet().forEach(k->out.add("extra:"+k));return out;
  }
  public static void main(String[] args)throws Exception {
    String run=args[0];Path ledger=Path.of(args[1]).toAbsolutePath();Path output=Path.of(args[2]);
    var report=new LinkedHashMap<String,Object>();report.put("observedAtUtc",Instant.now());report.put("readOnly",true);
    var evidence=ledger.getParent().resolve("sync-evidence").resolve(run);var source=JSON.readTree(evidence.resolve("source.json").toFile());
    JsonNode intent;String publicationState;
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger.toUri().toASCIIString()+"?mode=ro");var pragma=db.createStatement()){
      pragma.execute("PRAGMA query_only=ON");pragma.execute("PRAGMA busy_timeout=5000");try(var stmt=db.prepareStatement("SELECT state,intent_json FROM reference_publications WHERE run_id=?")){stmt.setString(1,run);try(var rows=stmt.executeQuery()){if(!rows.next())throw new IllegalStateException("Publication absent");publicationState=rows.getString(1);intent=JSON.readTree(rows.getString(2));if(rows.next())throw new IllegalStateException("Ambiguous publication");}}
    }
    String backup=intent.path("backup").asText();if(backup.isBlank())backup=intent.path("backupTable").asText();
    var before=query("SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name IN ('market_sentiment_daily','"+backup+"','l2_daily_features') ORDER BY name");
    var formal=marketRows("market_sentiment_daily");var retained=marketRows(backup);var expected=new ArrayList<MarketSentimentDailyRow>();for(var row:source.path("rows"))expected.add(JSON.treeToValue(row,MarketSentimentDailyRow.class));
    LocalDate from=LocalDate.parse(source.path("from").asText()),to=LocalDate.parse(source.path("to").asText());
    var window=formal.stream().filter(r->!MarketSentimentDailyWritePort.outside(r,from,to)).toList();var outside=formal.stream().filter(r->MarketSentimentDailyWritePort.outside(r,from,to)).toList();var oldOutside=retained.stream().filter(r->MarketSentimentDailyWritePort.outside(r,from,to)).toList();
    var combined=new ArrayList<>(oldOutside);combined.addAll(expected);combined.sort(Comparator.comparing(MarketSentimentDailyRow::tradeDate));
    var market=new LinkedHashMap<String,Object>();market.put("runId",run);market.put("publicationStateAtObservation",publicationState);market.put("backup",backup);market.put("sourceRows",expected.size());market.put("formalRows",formal.size());market.put("backupRows",retained.size());market.put("outsideRows",outside.size());market.put("fieldsChecked",window.size()*53);market.put("sourceDiff",differences(expected,window));market.put("outsideDiff",differences(oldOutside,outside));market.put("fullTypedSha256",MarketSentimentDailyWritePort.digest(formal));market.put("expectedFullTypedSha256",MarketSentimentDailyWritePort.digest(combined));market.put("oldFullTypedSha256",MarketSentimentDailyWritePort.digest(retained));market.put("intentOriginalShaMatches",MarketSentimentDailyWritePort.digest(retained).equals(intent.path("beforeFingerprint").asText()));market.put("intentReplacementShaMatches",MarketSentimentDailyWritePort.digest(formal).equals(intent.path("afterFingerprint").asText()));market.put("sourceEvidenceSha256",sha(evidence.resolve("source.json")));market.put("formal",formal);market.put("retainedBackup",retained);report.put("marketContentOnly",market);
    Path acceptPath=Path.of("artifacts/l2-full-cleaning-acceptance/20261007/output-validation-all.json");Path contractPath=Path.of("artifacts/l2-full-cleaning-acceptance/20261007/null-reference/schema.json");var acceptedAll=JSON.readTree(acceptPath.toFile());var contract=JSON.readTree(contractPath.toFile());JsonNode accepted=null;for(var day:acceptedAll.path("dates"))if(day.path("date").asText().equals("20260930"))accepted=day;if(accepted==null)throw new IllegalStateException("L2 accepted date absent");
    var schema=query("SELECT \"column\",\"type\" FROM table_columns('l2_daily_features')");var schemaErrors=new ArrayList<String>();if(schema.path("dataset").size()!=110)schemaErrors.add("count");for(var row:schema.path("dataset")){var field=L2DailyFeatureField.named(row.get(0).asText());if(field==null||!field.storageType().name().equals(row.get(1).asText()))schemaErrors.add("type:"+row);}
    for(var field:L2DailyFeatureField.values()){var c=contract.path("model_fields").path(field.fieldName());if(!c.isObject()||c.path("required").asBoolean()==field.nullable())schemaErrors.add("contract:"+field.fieldName());}
    String projection=String.join(",",Arrays.stream(L2DailyFeatureField.values()).map(f->f.temporal()?"cast(ts AS LONG) AS ts":"\""+f.fieldName()+"\"").toList());var l2=query("SELECT "+projection+" FROM l2_daily_features WHERE ts='2026-09-30' ORDER BY symbol LIMIT 10001");var names=new HashSet<String>();var nulls=new TreeMap<String,Long>();var nullSymbols=new TreeMap<String,Set<String>>();var errors=new ArrayList<String>();long checked=0;
    for(var row:l2.path("dataset")){String symbol=row.get(1).asText();if(!symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)")||!names.add(symbol))errors.add("key:"+symbol);for(int i=0;i<110;i++){var field=L2DailyFeatureField.values()[i];var value=row.get(i);if(!field.fieldName().equals(l2.path("columns").get(i).path("name").asText()))errors.add("projection:"+field);if(field.temporal()){if(value.asLong()!=1790726400000000L)errors.add("date:"+symbol);}else{Object v=field.decode(value);if(v==null){nulls.merge(field.fieldName(),1L,Long::sum);nullSymbols.computeIfAbsent(field.fieldName(),k->new TreeSet<>()).add(symbol);}if(field==L2DailyFeatureField.FEATURE_VERSION&&!Objects.equals(v,contract.path("feature_version").asText()))errors.add("feature:"+symbol);if(field==L2DailyFeatureField.PARSER_VERSION&&!Objects.equals(v,contract.path("parser_version").asText()))errors.add("parser:"+symbol);}checked++;}}
    var expectedNulls=new TreeMap<String,Long>();accepted.path("nullCountsByField").fields().forEachRemaining(e->expectedNulls.put(e.getKey(),e.getValue().asLong()));var expectedSymbols=new TreeMap<String,Set<String>>();accepted.path("nullSymbolsByField").fields().forEachRemaining(e->{var symbols=new TreeSet<String>();e.getValue().forEach(v->symbols.add(v.asText()));expectedSymbols.put(e.getKey(),symbols);});
    var l2Report=new LinkedHashMap<String,Object>();l2Report.put("featureRootExists",Files.isDirectory(Path.of("D:/l2-native-features")));l2Report.put("rows",names.size());l2Report.put("fieldsChecked",checked);l2Report.put("schemaErrors",schemaErrors);l2Report.put("errors",errors);l2Report.put("nullCounts",nulls);l2Report.put("nullSymbols",nullSymbols);l2Report.put("nullContractMatches",nulls.equals(expectedNulls)&&nullSymbols.equals(expectedSymbols));l2Report.put("historicalSourceSha256",accepted.path("aggregateSha256").asText());l2Report.put("originalSourceComparisonRepeated",false);report.put("retainedL2Conditions",l2Report);
    var after=query("SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name IN ('market_sentiment_daily','"+backup+"','l2_daily_features') ORDER BY name");report.put("stableWalBeforeAfter",before.path("dataset").equals(after.path("dataset")));report.put("queries",QUERIES);report.put("scope","Content verification only. An IN_DOUBT publication/run must still be formally reconciled; this audit cannot mark the pipeline complete.");Files.createDirectories(output.getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);System.out.println("Saved content-only audit "+output+" market="+formal.size()+" l2="+names.size()+" checked="+checked);
  }
}
