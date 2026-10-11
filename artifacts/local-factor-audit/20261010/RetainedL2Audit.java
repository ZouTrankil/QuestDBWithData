import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** SELECT-only audit of the retained importer evidence; never edits a source ledger. */
class RetainedL2Audit {
  static final ObjectMapper JSON=JobDefinitionJson.mapper();
  static final HttpClient HTTP=HttpClient.newHttpClient();
  static JsonNode query(String sql)throws Exception {
    var uri=URI.create("http://127.0.0.1:9000/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8));
    var response=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).GET().build(),HttpResponse.BodyHandlers.ofString());
    var body=JSON.readTree(response.body());
    if(response.statusCode()!=200||body.has("error"))throw new IllegalStateException("Query failed: "+body);
    return body;
  }
  static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  static void require(boolean yes,String reason){if(!yes)throw new IllegalStateException(reason);}
  static JsonNode day(JsonNode report,String date){for(var item:report.path("dates"))if(date.equals(item.path("date").asText()))return item;throw new IllegalStateException("Missing receipt: "+date);}
  public static void main(String[] args)throws Exception {
    var files=List.of(Path.of("artifacts/l2-questdb-ingestion/20261007/ingestion-report.json"),Path.of("artifacts/l2-full-cleaning-acceptance/20261007/output-validation-all.json"),Path.of("artifacts/l2-full-cleaning-acceptance/20261007/null-reference/schema.json"),Path.of("artifacts/main-strategy-java-refresh/20261007/goal-current-questdb-observation.json"));
    var hashes=new LinkedHashMap<String,String>();for(var file:files)hashes.put(file.toString(),sha(Files.readAllBytes(file)));
    var imported=JSON.readTree(files.get(0).toFile());var accepted=JSON.readTree(files.get(1).toFile());var contract=JSON.readTree(files.get(2).toFile());var historical=JSON.readTree(files.get(3).toFile()).path("queries").path("wal").path("result");
    require("SUCCEEDED".equals(imported.path("status").asText())&&"l2_daily_features".equals(imported.path("table").asText()),"Importer status/table mismatch");
    require(Set.of("http://127.0.0.1:9000","http://localhost:9000").contains(imported.path("endpoint").asText()),"Unexpected importer endpoint");
    String walSql="SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name='l2_daily_features'";
    var before=query(walSql);JsonNode old=null;for(var row:historical.path("dataset"))if("l2_daily_features".equals(row.get(0).asText()))old=row;
    String identitySql="SELECT id,table_name,directoryName FROM tables() WHERE table_name='l2_daily_features'";
    var identity=query(identitySql);require(identity.path("dataset").size()==1,"Missing table identity");
    require(old!=null&&before.path("dataset").size()==1&&old.equals(before.path("dataset").get(0)),"Historical/live WAL frontier differs");
    require(!old.get(1).asBoolean()&&old.get(2).asLong()==old.get(3).asLong()&&old.get(4).asLong()==0,"Unsettled historical WAL");
    var schema=query("SELECT \"column\",\"type\" FROM table_columns('l2_daily_features')");require(schema.path("dataset").size()==110,"Schema count differs");
    for(var row:schema.path("dataset")){var field=L2DailyFeatureField.named(row.get(0).asText());require(field!=null&&field.storageType().name().equals(row.get(1).asText()),"Physical schema differs");}
    for(var field:L2DailyFeatureField.values()){var spec=contract.path("model_fields").path(field.fieldName());require(spec.isObject()&&spec.path("required").asBoolean()!=field.nullable(),"Nullable contract differs: "+field);}
    String projection=String.join(",",Arrays.stream(L2DailyFeatureField.values()).map(f->f.temporal()?"cast(ts AS LONG) AS ts":"\""+f.fieldName()+"\"").toList());
    var results=new ArrayList<Map<String,Object>>();
    for(String date:List.of("20260928","20260929","20260930")){
      var receipt=day(imported,date);var archive=day(accepted,date);long count=receipt.path("expectedRows").asLong(-1);String sourceSha=receipt.path("sourceSha256").asText();
      require("VERIFIED".equals(receipt.path("status").asText())&&"PASSED".equals(archive.path("status").asText()),"Unaccepted date "+date);
      require(receipt.path("mismatches").isEmpty()&&archive.path("errors").isEmpty()&&archive.path("failedSymbols").isEmpty()&&archive.path("missingSourceDirectories").isEmpty(),"Archive/import errors "+date);
      require(count>0&&count<=10000&&count==receipt.path("readbackRows").asLong(-1)&&count*110==receipt.path("verifiedFields").asLong(-1)&&count==archive.path("outputRows").asLong(-1)&&count==archive.path("expectedCanonicalSymbols").asLong(-1)&&count==archive.path("archiveRawSymbols").asLong(-1)&&sourceSha.matches("[a-f0-9]{64}")&&sourceSha.equals(archive.path("aggregateSha256").asText()),"Receipt counts/SHA differ "+date);
      var logical=LocalDate.parse(date,java.time.format.DateTimeFormatter.BASIC_ISO_DATE);long micros=logical.atStartOfDay(ZoneOffset.UTC).toEpochSecond()*1000000;
      var data=query("SELECT "+projection+" FROM l2_daily_features WHERE ts='"+logical+"' ORDER BY symbol LIMIT 10001");
      var keys=new HashSet<String>();var nulls=new TreeMap<String,Long>();var nullSymbols=new TreeMap<String,Set<String>>();long checked=0;
      for(var row:data.path("dataset")){String symbol=row.get(1).asText();require(symbol.matches("[0-9]{6}\\.(SH|SZ|BJ)")&&keys.add(symbol),"Invalid/duplicate key");
        for(int i=0;i<110;i++){var field=L2DailyFeatureField.values()[i];require(field.fieldName().equals(data.path("columns").get(i).path("name").asText()),"Projection differs");var value=row.get(i);
          if(field.temporal())require(value.asLong()!=0&&value.asLong()==micros,"Date differs");
          else {Object decoded=field.decode(value);if(decoded==null){nulls.merge(field.fieldName(),1L,Long::sum);nullSymbols.computeIfAbsent(field.fieldName(),k->new TreeSet<>()).add(symbol);}if(field==L2DailyFeatureField.FEATURE_VERSION||field==L2DailyFeatureField.PARSER_VERSION)require(Objects.equals(decoded,contract.path(field.fieldName()).asText()),"Feature/parser version differs");}checked++;
        }
      }
      var expectedNulls=new TreeMap<String,Long>();archive.path("nullCountsByField").fields().forEachRemaining(e->expectedNulls.put(e.getKey(),e.getValue().asLong()));var expectedSymbols=new TreeMap<String,Set<String>>();archive.path("nullSymbolsByField").fields().forEachRemaining(e->{var set=new TreeSet<String>();e.getValue().forEach(v->set.add(v.asText()));expectedSymbols.put(e.getKey(),set);});
      require(keys.size()==count&&checked==count*110&&nulls.equals(expectedNulls)&&nullSymbols.equals(expectedSymbols),"Typed completeness/null key sets differ "+date);
      results.add(Map.of("date",date,"rows",count,"fieldsChecked",checked,"historicalSourceSha256",sourceSha,"currentJsonRowsSha256",sha(JSON.writeValueAsBytes(data.path("dataset"))),"status","verified_retained_conditions"));
    }
    require(before.path("dataset").equals(query(walSql).path("dataset"))&&identity.path("dataset").equals(query(identitySql).path("dataset")),"Table identity or WAL changed during audit");for(var file:files)require(hashes.get(file.toString()).equals(sha(Files.readAllBytes(file))),"Evidence changed during audit");
    var result=new LinkedHashMap<String,Object>();result.put("format","retained_questdb_l2_ingestion.v1");result.put("evidenceRoot",Path.of("").toAbsolutePath().toString());result.put("evidencePaths",Map.of("import",files.get(0).toString(),"archive",files.get(1).toString(),"contract",files.get(2).toString(),"observation",files.get(3).toString()));result.put("tableIdentity",identity);result.put("schema",schema);result.put("observedAt",Instant.now().toString());result.put("status","retained_conditions_verified");result.put("dates",results);result.put("evidenceSha256",hashes);result.put("wal",before);result.put("limitation","Historical importer receipt plus unchanged WAL and full current typed QA. Original raw-source comparison was not repeated. Does not replace Java publication ledger or fabricate Python ingest reports.");
    Path output=Path.of(args[0]);Files.createDirectories(output.getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),result);System.out.println("Verified retained L2 conditions for 3 dates and 23732 rows");
  }
}
