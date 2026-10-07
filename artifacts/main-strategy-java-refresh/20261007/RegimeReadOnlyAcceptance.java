import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.table.RegimeFeaturesMonitorDailyRow;
import com.zoutrankil.data.repository.RegimeFeaturesMonitorDailyWritePort;
import com.zoutrankil.data.repository.SyncRunLedger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One-shot local SELECT-only acceptance. No Spring application, provider, writer or tests. */
public class RegimeReadOnlyAcceptance {
    static final ObjectMapper JSON=JobDefinitionJson.mapper();
    static final String RUN="regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756";
    static final List<String> COLS=RegimeFeaturesMonitorDailyWritePort.COLUMNS;
    static final LocalDate FROM=LocalDate.parse("2026-09-21"),TO=LocalDate.parse("2026-09-30");
    static final List<Map<String,Object>> QUERIES=new ArrayList<>();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    static JsonNode query(String sql)throws Exception {
        var url=URI.create("http://127.0.0.1:9000/exec?query="+URLEncoder.encode(sql,StandardCharsets.UTF_8));
        var response=HTTP.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(45)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("Local read-only query failed: "+response.statusCode());
        var result=JSON.readTree(response.body());if(result.has("error"))throw new IllegalStateException(result.path("error").asText());
        QUERIES.add(Map.of("sql",sql,"response",result));return result;
    }
    static List<RegimeFeaturesMonitorDailyRow> rows(JsonNode reply)throws Exception {
        if(reply.path("columns").size()!=21)throw new IllegalStateException("Expected 21 columns");
        var result=new ArrayList<RegimeFeaturesMonitorDailyRow>();
        for(int i=0;i<21;i++)if(!COLS.get(i).equals(reply.path("columns").get(i).path("name").asText()))throw new IllegalStateException("Column order differs");
        for(var values:reply.path("dataset")) {
            var mapped=new LinkedHashMap<String,Object>();
            for(int i=0;i<21;i++){JsonNode value=values.get(i);Object typed=value.isNull()?null:i==0||i==20?Instant.parse(value.asText()):i==1||i==19?value.asText():value.doubleValue();mapped.put(COLS.get(i),typed);}
            result.add(RegimeFeaturesMonitorDailyWritePort.row(mapped));
        }
        return List.copyOf(result);
    }
    static List<String> differences(List<RegimeFeaturesMonitorDailyRow> expected,List<RegimeFeaturesMonitorDailyRow> actual) {
        var errors=new ArrayList<String>();var byDate=new HashMap<Instant,RegimeFeaturesMonitorDailyRow>();
        for(var row:actual)if(byDate.put(row.tradeDate(),row)!=null)errors.add("duplicate:"+row.tradeDate());
        if(expected.size()!=actual.size())errors.add("row-count:"+expected.size()+"/"+actual.size());
        for(var row:expected){var other=byDate.remove(row.tradeDate());if(other==null){errors.add("missing:"+row.tradeDate());continue;}
            var a=RegimeFeaturesMonitorDailyWritePort.values(row);var b=RegimeFeaturesMonitorDailyWritePort.values(other);
            for(var key:COLS){Object x=a.get(key),y=b.get(key);boolean equal=x instanceof Double d&&y instanceof Double e?Double.doubleToRawLongBits(d)==Double.doubleToRawLongBits(e):Objects.equals(x,y);
                if(!equal)errors.add(row.tradeDate()+":"+key+":"+x+"/"+y);}
        }
        byDate.keySet().forEach(date->errors.add("extra:"+date));return errors;
    }
    static String fileHash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static boolean settled(JsonNode reply){for(var row:reply.path("dataset"))if(row.get(1).asBoolean()||row.get(2).asLong()!=row.get(3).asLong()||row.get(4).asLong()!=0)return false;return reply.path("dataset").size()==2;}
    public static void main(String[] args)throws Exception {
        var root=Path.of("").toAbsolutePath();var folder=root.resolve("artifacts/main-strategy-java-refresh/20261007");
        var evidence=root.resolve("var/main-strategy-java-refresh/20261007/sync-evidence").resolve(RUN);
        var sourcePath=evidence.resolve("source.json");var source=JSON.readTree(sourcePath.toFile());var publication=JSON.readTree(evidence.resolve("publication.json").toFile());
        String backup=publication.path("backup").asText();if(!backup.matches("java_regime_features_monitor_daily_backup_[a-f0-9]{32}"))throw new IllegalStateException("Unexpected backup identifier");
        var expected=new ArrayList<RegimeFeaturesMonitorDailyRow>();for(var row:source.path("rows"))expected.add(JSON.treeToValue(row,RegimeFeaturesMonitorDailyRow.class));
        String select="SELECT "+String.join(",",COLS)+" FROM ";
        var walBefore=query("SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name IN ('regime_features_monitor_daily','"+backup+"') ORDER BY name");
        var formalReply=query(select+"regime_features_monitor_daily ORDER BY trade_date LIMIT 10001");var formal=rows(formalReply);
        var backupReply=query(select+backup+" ORDER BY trade_date LIMIT 10001");var backupRows=rows(backupReply);
        var before=rows(JSON.readTree(folder.resolve("regime-outside-before.json").toFile()));
        var outside=formal.stream().filter(row->RegimeFeaturesMonitorDailyWritePort.outside(row,FROM,TO)).toList();
        var window=formal.stream().filter(row->!RegimeFeaturesMonitorDailyWritePort.outside(row,FROM,TO)).toList();
        var sourceDiff=differences(expected,window);var outsideDiff=differences(before,outside);var backupDiff=differences(before,backupRows);
        var fullExpected=new ArrayList<>(before);fullExpected.addAll(expected);fullExpected.sort(Comparator.comparing(RegimeFeaturesMonitorDailyRow::tradeDate));
        String canonical=RegimeFeaturesMonitorDailyWritePort.digest(formal),expectedCanonical=RegimeFeaturesMonitorDailyWritePort.digest(fullExpected),backupCanonical=RegimeFeaturesMonitorDailyWritePort.digest(backupRows);
        var ledgerPath=root.resolve("var/main-strategy-java-refresh/20261007/sync-ledger.sqlite3");var ledger=SyncRunLedger.openReadOnly(ledgerPath);var run=ledger.getRun(RUN);var state=ledger.get(RUN);
        var frozen=JSON.readTree(run.frozenJson());
        var journal=new LinkedHashMap<String,Object>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledgerPath.toUri().toASCIIString()+"?mode=ro");var pragma=db.createStatement()) {
            pragma.execute("PRAGMA query_only=ON");pragma.execute("PRAGMA busy_timeout=5000");
            try(var stmt=db.prepareStatement("SELECT state,revision,intent_json FROM reference_publications WHERE dataset='regime_features_monitor_daily' AND run_id=?")){
                stmt.setString(1,RUN);try(var result=stmt.executeQuery()){if(!result.next())throw new IllegalStateException("Publication journal absent");journal.put("state",result.getString(1));journal.put("revision",result.getLong(2));journal.put("intent",JSON.readTree(result.getString(3)));if(result.next())throw new IllegalStateException("Duplicate publication journal");}
            }
        }
        var intent=(JsonNode)journal.get("intent");var scope=JSON.readTree(intent.path("scope").asText());String sha=fileHash(sourcePath);
        var cal=query("SELECT cal_date,is_open FROM exchange_calendar WHERE exchange='SSE' AND cal_date>='2026-09-21' AND cal_date<'2026-10-01' ORDER BY cal_date");var expectedDays=new TreeSet<String>();var allCalDays=new HashSet<String>();
        for(var row:cal.path("dataset")){String date=row.get(0).asText().substring(0,10);if(!allCalDays.add(date))throw new IllegalStateException("Duplicate calendar date");if(row.get(1).asInt()==1)expectedDays.add(date);}
        boolean calendarGrid=allCalDays.size()==10;for(var date=FROM;!date.isAfter(TO);date=date.plusDays(1))calendarGrid&=allCalDays.contains(date.toString());
        var actualDays=new TreeSet<String>();var requiredMissing=new ArrayList<String>();var allDates=new HashSet<Instant>();var nonfinite=new ArrayList<String>();
        for(var row:formal){if(!allDates.add(row.tradeDate()))throw new IllegalStateException("Duplicate formal date");for(var entry:RegimeFeaturesMonitorDailyWritePort.values(row).entrySet())if(entry.getValue() instanceof Double value&&!Double.isFinite(value))nonfinite.add(row.tradeDate()+":"+entry.getKey());}
        for(var row:window){actualDays.add(row.tradeDate().toString().substring(0,10));var values=RegimeFeaturesMonitorDailyWritePort.values(row);
            for(var field:source.path("requiredContextFields")){Object value=values.get(field.asText());if(!(value instanceof Double number)||!Double.isFinite(number))requiredMissing.add(row.tradeDate()+":"+field.asText());}
            if(!row.month().equals("202609")||!row.dataQualityFlag().equals("proxy_style"))requiredMissing.add(row.tradeDate()+":month/flag");
        }
        var last=window.stream().filter(row->row.tradeDate().equals(Instant.parse("2026-09-30T00:00:00Z"))).findFirst().orElseThrow();
        var margin=source.path("marginAvailability");JsonNode marginLast=null;for(var item:margin)if(item.path("tradeDate").asText().equals("2026-09-30"))marginLast=item;
        boolean marginNull=last.marginBalanceChangeMtd()==null&&marginLast!=null&&!marginLast.path("admitted").asBoolean()&&marginLast.path("counts").path("SZ").asInt()==0&&marginLast.path("counts").path("BJ").asInt()==0;
        var metadata=query("SELECT table_name,id,directoryName,walEnabled,partitionBy,dedup,designatedTimestamp FROM tables() WHERE table_name IN ('regime_features_monitor_daily','"+backup+"') ORDER BY table_name");
        boolean identities=metadata.path("dataset").size()==2;
        for(var item:metadata.path("dataset")){
            boolean isFormal=item.get(0).asText().equals("regime_features_monitor_daily");
            identities&=item.get(1).asLong()==intent.path(isFormal?"replacementId":"originalId").asLong()
                    &&item.get(3).asBoolean()&&item.get(4).asText().equals("MONTH")&&!item.get(5).asBoolean()&&item.get(6).asText().equals("trade_date");
            if(!isFormal)identities&=item.get(2).asText().equals(intent.path("originalDirectory").asText());
        }
        var schema=query("SELECT \"column\",type,designated,upsertKey FROM table_columns('regime_features_monitor_daily')");
        boolean schemaExact=schema.path("dataset").size()==21;for(int i=0;i<21;i++){var column=schema.path("dataset").get(i);schemaExact&=COLS.get(i).equals(column.get(0).asText())&&(i==0||i==20?"TIMESTAMP":i==1||i==19?"SYMBOL":"DOUBLE").equals(column.get(1).asText())&&column.get(2).asBoolean()==(i==0)&&!column.get(3).asBoolean();}
        var walAfter=query("SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name IN ('regime_features_monitor_daily','"+backup+"') ORDER BY name");
        boolean scopeValid=sha.equals(scope.path("sourceEvidenceSha256").asText())&&source.path("sourceFingerprint").asText().equals(scope.path("sourceFingerprint").asText())&&FROM.toString().equals(scope.path("from").asText())&&TO.toString().equals(scope.path("to").asText())&&FROM.toString().equals(frozen.path("from").asText())&&TO.toString().equals(frozen.path("to").asText())&&run.targetId().equals(intent.path("initialTarget").asText())&&"java.regime_features_monitor_daily".equals(source.path("producer").asText());
        boolean passed=sourceDiff.isEmpty()&&outsideDiff.isEmpty()&&backupDiff.isEmpty()&&nonfinite.isEmpty()&&requiredMissing.isEmpty()&&expectedDays.equals(actualDays)&&calendarGrid&&marginNull&&schemaExact&&identities&&scopeValid&&canonical.equals(expectedCanonical)&&canonical.equals(publication.path("fullTargetFingerprint").asText())&&canonical.equals(intent.path("afterFingerprint").asText())&&backupCanonical.equals(intent.path("beforeFingerprint").asText())&&state.state().name().equals("VERIFIED")&&journal.get("state").equals("VERIFIED")&&settled(walBefore)&&settled(walAfter)&&walBefore.path("dataset").equals(walAfter.path("dataset"));
        var report=new LinkedHashMap<String,Object>();report.put("observedAt",Instant.now());report.put("passed",passed);report.put("runId",RUN);report.put("comparison","All 21 fields; exact Instant including UTC microseconds, exact strings/null and Double IEEE-754 raw bits");
        report.put("windowRows",window.size());report.put("fullRows",formal.size());report.put("outsideRows",outside.size());report.put("backupRows",backupRows.size());report.put("sourceFieldComparisons",expected.size()*21);report.put("outsideFieldComparisons",before.size()*21);report.put("backupFieldComparisons",before.size()*21);
        report.put("sourceDifferences",sourceDiff);report.put("outsideDifferences",outsideDiff);report.put("backupDifferences",backupDiff);report.put("sourceFileSha256",sha);report.put("scopeValid",scopeValid);report.put("fullTargetCanonicalSha256",canonical);report.put("expectedMergedCanonicalSha256",expectedCanonical);report.put("backupCanonicalSha256",backupCanonical);report.put("schemaExact",schemaExact);
        report.put("expectedTradingDates",expectedDays);report.put("actualTradingDates",actualDays);report.put("calendarGridComplete",calendarGrid);report.put("requiredContextInvalid",requiredMissing);report.put("formalDuplicateDates",formal.size()-allDates.size());report.put("nonfiniteValues",nonfinite);report.put("margin0930NullAndPartialEvidenced",marginNull);report.put("margin0930Evidence",marginLast);report.put("latestRow",last);report.put("metadata",metadata);report.put("physicalIdentitiesAndLayoutVerified",identities);report.put("walBefore",walBefore);report.put("walAfter",walAfter);
        report.put("ledgerRun",run);report.put("ledgerState",state);report.put("ledgerEntries",ledger.entries(RUN,null,100));report.put("publicationJournal",journal);
        var sourceHeader=(com.fasterxml.jackson.databind.node.ObjectNode)source.deepCopy();sourceHeader.remove("rows");report.put("sourceHeader",sourceHeader);
        JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("regime-native-java-audit.json").toFile(),report);JSON.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("regime-actual-readonly-queries.json").toFile(),QUERIES);
        System.out.println(JSON.writeValueAsString(Map.of("passed",passed,"windowRows",window.size(),"fullRows",formal.size(),"outsideRows",outside.size(),"backupRows",backupRows.size(),"canonicalSha256",canonical,"sourceDifferences",sourceDiff.size(),"outsideDifferences",outsideDiff.size(),"backupDifferences",backupDiff.size(),"scopeValid",scopeValid)));
        if(!passed)System.exit(2);
    }
}
