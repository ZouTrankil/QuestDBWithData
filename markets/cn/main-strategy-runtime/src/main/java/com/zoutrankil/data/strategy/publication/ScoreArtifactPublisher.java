package com.zoutrankil.data.strategy.publication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 发布与 Python 兼容的不可变研究产物；两个别名在同一 SQLite 事务中推进。 */
public final class ScoreArtifactPublisher {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ScoreArtifactPublisher.class);
    private static final java.util.regex.Pattern COLUMN_NAME=java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String PRODUCER = "java.research.etf_only_top2.prepare_inputs";
    public record Ref(String uri, String version, String contentHash, String schemaDigest,
                      long rowCount, Path path, LocalDate start, LocalDate end) {}
    public record Publication(String producerRunId, LocalDate resolvedEndDate, Ref panelRef,
                              Ref coverageRef, String provenanceSha256, String recipeSha256) {}
    private enum Kind { DATE, STRING, DOUBLE, LONG, BOOLEAN }
    @FunctionalInterface public interface PublicationGate {void check()throws Exception;}
    private final Path root, database;
    public ScoreArtifactPublisher(Path artifactRoot) {
        root = artifactRoot.toAbsolutePath().normalize(); database = root.resolve("_catalog/resource_catalog.sqlite3");
    }

    public Publication publish(String runId, List<Map<String,Object>> panel, List<Map<String,Object>> coverage,
            List<LocalDate> signalDates, String recipeSha256, Map<String,Object> provenance) throws Exception {
        return publish(runId,panel,coverage,signalDates,recipeSha256,provenance,()->{});
    }
    /**
     * 先写不可变文件，再在目录事务中同时推进评分和覆盖率别名。
     * 中途失败可以留下未引用文件，但不能让读取方看见只更新了一半的产物组合。
     */
    public Publication publish(String runId, List<Map<String,Object>> panel, List<Map<String,Object>> coverage,
            List<LocalDate> signalDates, String recipeSha256, Map<String,Object> provenance,PublicationGate gate) throws Exception {
        if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Bounded score run ID required");
        if (recipeSha256 == null || !recipeSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Frozen recipe SHA256 required");
        var expected = new TreeSet<>(signalDates);
        if (expected.isEmpty() || expected.size() != signalDates.size()) throw new IllegalArgumentException("Unique complete signal dates required");
        var sourceProof=JSON.valueToTree(provenance);
        if(!sourceProof.path("sourceComplete").asBoolean()||!recipeSha256.equals(sourceProof.path("recipeHashes").path("recipe").asText())||!expected.last().toString().equals(sourceProof.path("end").asText()))throw new IllegalArgumentException("Complete source, recipe and date evidence required before score publication");
        if (!dates(coverage, true).equals(expected) || !dates(panel, false).equals(expected))
            throw new IllegalStateException("Score panel/coverage does not cover exactly the frozen signal grid");
        var keys = new HashSet<String>();
        for (var row:panel) {
            String code=Objects.toString(row.get("ts_code"), "");
            if (!code.matches("[0-9]{6}\\.(SH|SZ|BJ)") || !keys.add(day(row.get("trade_date"))+":"+code))
                throw new IllegalStateException("Invalid or duplicate score panel key");
        }
        Files.createDirectories(root);
        String relative="research/etf_only_top2/inputs/"+runId+"/";
        byte[] proof=JSON.writeValueAsBytes(provenance);
        String proofHash=sha(proof);
        immutable(root.resolve(relative+"provenance-"+proofHash.substring(0,16)+".json"), proof);
        Ref panelRef=frame(relative,"score_panel",panel), coverageRef=frame(relative,"score_coverage",coverage);
        Files.createDirectories(database.getParent());
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+database)) {
            configure(c); initialize(c); c.setAutoCommit(false);
            try {
                String oldRun=null;
                for (String role:List.of("score_panel","score_coverage")) {
                    try (var q=c.prepareStatement("SELECT m.run_id,m.data_end_date FROM resource_aliases a JOIN resource_metadata m ON m.uri=a.target_uri AND m.version=a.target_version WHERE a.alias=?")) {
                        q.setString(1,"research.etf_only_top2."+role+"@latest");
                        try(var rs=q.executeQuery()) { if(rs.next()) {
                            if (oldRun!=null && !oldRun.equals(rs.getString(1))) throw new IllegalStateException("Current score aliases have different producer runs");
                            oldRun=rs.getString(1);
                            if (LocalDate.parse(rs.getString(2),DateTimeFormatter.BASIC_ISO_DATE).isAfter(expected.last()))
                                throw new IllegalStateException("Score publication cannot regress the complete frontier");
                        }}
                    }
                }
                metadata(c,"score_panel",runId,panelRef); metadata(c,"score_coverage",runId,coverageRef);
                alias(c,"score_panel",panelRef); alias(c,"score_coverage",coverageRef);
                try(var s=c.prepareStatement("INSERT INTO java_score_publications(run_id,recipe_sha256,provenance_sha256,panel_uri,coverage_uri,end_date) VALUES(?,?,?,?,?,?) ON CONFLICT(run_id) DO NOTHING")) {
                    s.setString(1,runId);s.setString(2,recipeSha256);s.setString(3,proofHash);s.setString(4,panelRef.uri());s.setString(5,coverageRef.uri());s.setString(6,expected.last().toString());s.executeUpdate();
                }
                try(var q=c.prepareStatement("SELECT recipe_sha256,provenance_sha256,panel_uri,coverage_uri,end_date FROM java_score_publications WHERE run_id=?")) {
                    q.setString(1,runId);try(var rs=q.executeQuery()) {
                        if(!rs.next() || !recipeSha256.equals(rs.getString(1)) || !proofHash.equals(rs.getString(2)) || !panelRef.uri().equals(rs.getString(3)) || !coverageRef.uri().equals(rs.getString(4)) || !expected.last().toString().equals(rs.getString(5)))
                            throw new IllegalStateException("Immutable Java score run collision");
                    }
                }
                gate.check();c.commit();
            } catch(Exception failure) { c.rollback();throw failure; }
        }
        var observed=latest(expected.last());
        if(!observed.producerRunId().equals(runId)||!observed.panelRef().contentHash().equals(panelRef.contentHash())||!observed.coverageRef().contentHash().equals(coverageRef.contentHash()))throw new IllegalStateException("Score aliases changed immediately after publication; explicit reconciliation required");
        return observed;
    }

    /** 一次读取事务同时绑定两个角色、回执和不可变字节内容。 */
    public Publication latest(LocalDate required) throws Exception {
        return load(null,required);
    }
    /** 每日别名推进后，周计划仍保留最初绑定的不可变评分运行。 */
    public Publication byRun(String runId)throws Exception {
        if(runId==null||!runId.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Bounded historical score run required");
        return load(runId,null);
    }
    /** 为周信号解析已冻结的日频运行，不移动每日别名。 */
    public Publication forDate(LocalDate signal)throws Exception {
        if(signal==null||!Files.isRegularFile(database))throw new IllegalStateException("Exact historical Java score date is unavailable");
        String run;
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database.toUri()+"?mode=ro")){
            // A migrated Python catalog may precede the first Java publication.
            // Keep this lookup read-only and report the normal unavailable state.
            try(var q=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name='java_score_publications'");var r=q.executeQuery()) {
                if(!r.next())throw new IllegalStateException("No verified Java score publication has exact weekly signal frontier: "+signal);
            }
            try(var q=c.prepareStatement("SELECT p.run_id FROM java_score_publications p JOIN resource_metadata m ON m.uri=p.panel_uri AND m.run_id=p.run_id WHERE p.end_date=? AND m.role='score_panel' AND m.producer_capability=? ORDER BY m.created_at DESC,p.run_id DESC LIMIT 1")) {
                q.setString(1,signal.toString());q.setString(2,PRODUCER);try(var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("No verified Java score publication has exact weekly signal frontier: "+signal);run=r.getString(1);}
            }
        }
        var p=byRun(run);if(!signal.equals(p.resolvedEndDate()))throw new IllegalStateException("Historical Java score frontier changed");return p;
    }
    private Publication load(String selectedRun,LocalDate required)throws Exception {
        if(!Files.isRegularFile(database)) throw new IllegalStateException("Java score catalog is absent: "+database);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database.toUri()+"?mode=ro")) {
            try(var s=c.createStatement()) {s.execute("PRAGMA query_only=ON");s.execute("PRAGMA busy_timeout=2000");}
            c.setAutoCommit(false);
            var refs=new LinkedHashMap<String,Ref>();String run=null;
            for(String role:List.of("score_panel","score_coverage")) {
                String query=selectedRun==null?"SELECT m.uri,m.version,m.content_hash,m.schema_digest,m.row_count,m.data_start_date,m.data_end_date,m.run_id FROM resource_aliases a JOIN resource_metadata m ON m.uri=a.target_uri AND m.version=a.target_version AND m.content_hash=a.target_content_hash WHERE a.alias=? AND m.role=? AND m.producer_capability=?":"SELECT m.uri,m.version,m.content_hash,m.schema_digest,m.row_count,m.data_start_date,m.data_end_date,m.run_id FROM resource_metadata m WHERE m.run_id=? AND m.role=? AND m.producer_capability=?";
                try(var q=c.prepareStatement(query)) {
                    q.setString(1,selectedRun==null?"research.etf_only_top2."+role+"@latest":selectedRun);q.setString(2,role);q.setString(3,PRODUCER);
                    try(var rs=q.executeQuery()) {
                        if(!rs.next()) throw new IllegalStateException("Java score artifact unavailable: "+role);
                        String uri=rs.getString(1);Path path=artifact(uri);
                        Ref ref=new Ref(uri,rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),path,LocalDate.parse(rs.getString(6),DateTimeFormatter.BASIC_ISO_DATE),LocalDate.parse(rs.getString(7),DateTimeFormatter.BASIC_ISO_DATE));
                        validateFrame(ref);
                        if(run!=null && !run.equals(rs.getString(8))) throw new IllegalStateException("Scores have different producer runs");
                        run=rs.getString(8);refs.put(role,ref);
                        if(rs.next())throw new IllegalStateException("Ambiguous score role in immutable producer run");
                    }
                }
            }
            Ref panel=refs.get("score_panel"),cov=refs.get("score_coverage");
            if(!panel.start().equals(cov.start()) || !panel.end().equals(cov.end()) || required!=null && cov.end().isBefore(required)) throw new IllegalStateException("Score pair complete frontier does not cover requested date");
            try(var q=c.prepareStatement("SELECT recipe_sha256,provenance_sha256,panel_uri,coverage_uri,end_date FROM java_score_publications WHERE run_id=?")) {
                q.setString(1,run);try(var rs=q.executeQuery()) {
                    if(!rs.next() || !panel.uri().equals(rs.getString(3)) || !cov.uri().equals(rs.getString(4)) || !cov.end().toString().equals(rs.getString(5))) throw new IllegalStateException("Java score publication receipt absent or changed");
                    Path proof=panel.path().getParent().resolve("provenance-"+rs.getString(2).substring(0,16)+".json");
                    if(!sha(proof).equals(rs.getString(2))) throw new IllegalStateException("Score source proof changed");
                    var sourceProof=JSON.readTree(Files.readAllBytes(proof));
                    if(!sourceProof.path("sourceComplete").asBoolean()||!rs.getString(1).equals(sourceProof.path("recipeHashes").path("recipe").asText())||!cov.end().toString().equals(sourceProof.path("end").asText()))throw new IllegalStateException("Score complete date/recipe proof is inconsistent with publication receipt");
                    return new Publication(run,cov.end(),panel,cov,rs.getString(2),rs.getString(1));
                }
            }
        }
    }
    public Path provenancePath(Publication p) { return p.panelRef().path().getParent().resolve("provenance-"+p.provenanceSha256().substring(0,16)+".json"); }

    public Ref frame(String prefix,String role,List<Map<String,Object>> rows)throws Exception {return frame(prefix,role,rows,"trade_date");}
    public Ref frame(String prefix,String role,List<Map<String,Object>> rows,String dateColumn)throws Exception {
        if(prefix==null||!prefix.endsWith("/")||role==null||!role.matches("[A-Za-z0-9_]{1,64}"))throw new IllegalArgumentException("Canonical frame prefix and role required");
        Path directory=artifact("quant://artifact/"+prefix);
        if(rows==null||rows.isEmpty())throw new IllegalArgumentException("Nonempty frozen frame required");
        LOG.info("Inferring complete published frame: role={}, rows={}",role,rows.size());
        var fields=new LinkedHashMap<String,Kind>();
        rows.forEach(row->row.forEach((name,value)-> {
            if(name.equals("component_set")) return;
            if(!fields.containsKey(name)&&!COLUMN_NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid frame column: "+name);
            if(value==null) {fields.putIfAbsent(name,null);return;}
            Kind k=value instanceof LocalDate || name.equals(dateColumn)?Kind.DATE:value instanceof Boolean?Kind.BOOLEAN:value instanceof Float || value instanceof Double?Kind.DOUBLE:value instanceof Number?Kind.LONG:Kind.STRING;
            Kind old=fields.get(name);if(old!=null && old!=k) {
                if((old==Kind.DOUBLE||old==Kind.LONG)&&(k==Kind.DOUBLE||k==Kind.LONG)) k=Kind.DOUBLE;
                else throw new IllegalArgumentException("Frame has mixed column types: "+name);
            } fields.put(name,k);
        }));
        fields.replaceAll((k,v)->v==null?Kind.DOUBLE:v);
        var types=new ArrayList<Type>();var schemaCards=new ArrayList<String>();
        for(var field:fields.entrySet()) {
            var primitive=switch(field.getValue()) {case DATE->PrimitiveType.PrimitiveTypeName.INT32;case STRING->PrimitiveType.PrimitiveTypeName.BINARY;case DOUBLE->PrimitiveType.PrimitiveTypeName.DOUBLE;case LONG->PrimitiveType.PrimitiveTypeName.INT64;case BOOLEAN->PrimitiveType.PrimitiveTypeName.BOOLEAN;};
            var builder=org.apache.parquet.schema.Types.optional(primitive);if(field.getValue()==Kind.DATE)builder.as(LogicalTypeAnnotation.dateType());if(field.getValue()==Kind.STRING)builder.as(LogicalTypeAnnotation.stringType());types.add(builder.named(field.getKey()));
            String dtype=switch(field.getValue()){case DATE,STRING->"object";case DOUBLE->"float64";case LONG->"int64";case BOOLEAN->"bool";};
            schemaCards.add("{\"dtype\": "+JSON.writeValueAsString(dtype)+", \"name\": "+JSON.writeValueAsString(field.getKey())+"}");
        }
        String schemaDigest=sha(("["+String.join(", ",schemaCards)+"]").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var range=dates(rows,false,dateColumn);
        var schema=new MessageType("research_frame",types);var factory=new SimpleGroupFactory(schema);
        LOG.info("Writing complete published frame: role={}, rows={}, columns={}",role,rows.size(),fields.size());
        Files.createDirectories(directory);Path temp=directory.resolve("."+role+"-"+UUID.randomUUID()+".parquet");
        try(var writer=ExampleParquetWriter.builder(new LocalOutputFile(temp)).withConf(new Configuration()).withType(schema).withExtraMetaData(Map.of("java.research.schema_digest",schemaDigest,"java.research.start",range.first().toString(),"java.research.end",range.last().toString(),"java.research.row_count",Long.toString(rows.size()))).withCompressionCodec(CompressionCodecName.SNAPPY).build()) {
            int written=0;
            for(var row:rows) {Group group=factory.newGroup();for(var field:fields.entrySet()) {
                Object value=row.get(field.getKey());if(value==null)continue;
                switch(field.getValue()) {
                    case DATE->group.add(field.getKey(),Math.toIntExact(day(value).toEpochDay()));
                    case STRING->group.add(field.getKey(),value.toString());
                    case BOOLEAN->group.add(field.getKey(),(Boolean)value);
                    case LONG->group.add(field.getKey(),((Number)value).longValue());
                    case DOUBLE->{double d=((Number)value).doubleValue();if(Double.isInfinite(d))throw new IllegalArgumentException("Infinite published value");if(!Double.isNaN(d))group.add(field.getKey(),d);}
                }
            }writer.write(group);if(++written%25000==0)LOG.info("Published frame write progress: role={}, rows={}/{}",role,written,rows.size());}
        }
        try {String digest=sha(temp), relative=prefix+role+"-"+digest.substring(0,16)+".parquet";Path target=root.resolve(relative);
            if(Files.exists(target)) {if(!sha(target).equals(digest))throw new IllegalStateException("Immutable artifact collision");} else Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Completed immutable published frame: role={}, rows={}, sha256={}",role,rows.size(),digest);
            return new Ref("quant://artifact/"+relative,digest.substring(0,16),digest,schemaDigest,rows.size(),target,range.first(),range.last());
        } finally {Files.deleteIfExists(temp);}
    }
    private Path artifact(String uri) {
        if(!uri.startsWith("quant://artifact/"))throw new IllegalArgumentException("Unsupported artifact URI");
        Path result=root.resolve(uri.substring("quant://artifact/".length())).normalize();if(!result.startsWith(root)||result.equals(root))throw new IllegalArgumentException("Artifact escapes root");return result;
    }
    public static void validateFrame(Ref ref)throws Exception {
        if(!sha(ref.path()).equals(ref.contentHash())||!ref.version().equals(ref.contentHash().substring(0,16))||ref.rowCount()<1)throw new IllegalStateException("Artifact immutable bytes/card invalid");
        try(var reader=ParquetFileReader.open(new LocalInputFile(ref.path()))) {
            var footer=reader.getFooter();var card=footer.getFileMetaData().getKeyValueMetaData();long rows=footer.getBlocks().stream().mapToLong(b->b.getRowCount()).sum();
            if(rows!=ref.rowCount()||!ref.schemaDigest().equals(card.get("java.research.schema_digest"))||!ref.start().toString().equals(card.get("java.research.start"))||!ref.end().toString().equals(card.get("java.research.end"))||!Long.toString(rows).equals(card.get("java.research.row_count")))throw new IllegalStateException("Artifact footer/schema/complete-date metadata mismatch");
        }
    }
    private static TreeSet<LocalDate> dates(List<Map<String,Object>> rows,boolean unique) {return dates(rows,unique,"trade_date");}
    private static TreeSet<LocalDate> dates(List<Map<String,Object>> rows,boolean unique,String dateColumn) {var result=new TreeSet<LocalDate>();for(var row:rows)if(!result.add(day(row.get(dateColumn))) && unique)throw new IllegalArgumentException("Duplicate coverage date");return result;}
    private static LocalDate day(Object value) {if(value instanceof LocalDate d)return d;if(value==null)throw new IllegalArgumentException("Missing trade_date");String text=value.toString();return text.matches("[0-9]{8}")?LocalDate.parse(text,DateTimeFormatter.BASIC_ISO_DATE):LocalDate.parse(text.substring(0,10));}
    public static String sha(Path path)throws Exception {try(var in=Files.newInputStream(path)){var md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);return HexFormat.of().formatHex(md.digest());}}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void immutable(Path path,byte[] bytes)throws Exception {Files.createDirectories(path.getParent());if(Files.exists(path)){if(!sha(path).equals(sha(bytes)))throw new IllegalStateException("Immutable proof collision");return;}try(var c=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}}
    private static void configure(Connection c)throws SQLException {try(var s=c.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=30000");s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");}}
    private static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement();var rs=s.executeQuery("PRAGMA user_version")){if(rs.next()&&rs.getInt(1)>1)throw new IllegalStateException("Unsupported research catalog schema");}
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS resource_catalog_schema_versions(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS resource_metadata(resource_id INTEGER PRIMARY KEY,uri TEXT NOT NULL,version TEXT NOT NULL DEFAULT '',content_hash TEXT,media_type TEXT,role TEXT NOT NULL,schema_id TEXT NOT NULL,schema_version TEXT NOT NULL,schema_digest TEXT NOT NULL,size_bytes INTEGER NOT NULL CHECK(size_bytes>=0),producer_capability TEXT NOT NULL,producer_version TEXT NOT NULL,run_id TEXT NOT NULL,created_at TEXT NOT NULL,data_start_date TEXT NOT NULL,data_end_date TEXT NOT NULL,row_count INTEGER NOT NULL CHECK(row_count>=0),retention_policy TEXT NOT NULL CHECK(retention_policy IN ('cache','run','audit','permanent')),execution_node TEXT NOT NULL,UNIQUE(uri,version))");
            s.execute("CREATE TABLE IF NOT EXISTS resource_column_codecs(resource_id INTEGER NOT NULL REFERENCES resource_metadata(resource_id) ON DELETE CASCADE,column_name TEXT NOT NULL,codec TEXT NOT NULL,PRIMARY KEY(resource_id,column_name))");
            s.execute("CREATE TABLE IF NOT EXISTS resource_parents(resource_id INTEGER NOT NULL REFERENCES resource_metadata(resource_id) ON DELETE CASCADE,ordinal INTEGER NOT NULL CHECK(ordinal>=0),parent_uri TEXT NOT NULL,parent_version TEXT NOT NULL DEFAULT '',parent_content_hash TEXT,parent_media_type TEXT,PRIMARY KEY(resource_id,ordinal))");
            s.execute("CREATE TABLE IF NOT EXISTS resource_aliases(alias TEXT PRIMARY KEY,target_uri TEXT NOT NULL,target_version TEXT NOT NULL DEFAULT '',target_content_hash TEXT,target_media_type TEXT,updated_at TEXT NOT NULL,updated_by TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS java_score_publications(run_id TEXT PRIMARY KEY,recipe_sha256 TEXT NOT NULL,provenance_sha256 TEXT NOT NULL,panel_uri TEXT NOT NULL,coverage_uri TEXT NOT NULL,end_date TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO resource_catalog_schema_versions VALUES(1,CURRENT_TIMESTAMP)");s.execute("PRAGMA user_version=1");
        }
    }
    public Connection openCatalogWriter()throws Exception {Files.createDirectories(database.getParent());var c=DriverManager.getConnection("jdbc:sqlite:"+database);try{configure(c);initialize(c);c.setAutoCommit(false);return c;}catch(Exception e){c.close();throw e;}}
    public Ref jsonArtifact(String prefix,String role,byte[] bytes,LocalDate start,LocalDate end,long rows,String schemaDigest)throws Exception {
        String digest=sha(bytes),relative=prefix+role+"-"+digest.substring(0,16)+".json";Path path=artifact("quant://artifact/"+relative);immutable(path,bytes);return new Ref("quant://artifact/"+relative,digest.substring(0,16),digest,schemaDigest,rows,path,start,end);
    }
    private static void metadata(Connection c,String role,String run,Ref ref)throws Exception {registerMetadata(c,role,run,ref,"quant_platform.research.etf_only_top2."+role,PRODUCER,List.of());}
    public static void registerMetadata(Connection c,String role,String run,Ref ref,String schemaId,String producer,List<Ref> parents)throws Exception {
        try(var q=c.prepareStatement("SELECT content_hash,schema_digest,run_id FROM resource_metadata WHERE uri=? AND version=?")){q.setString(1,ref.uri());q.setString(2,ref.version());try(var rs=q.executeQuery()){if(rs.next()){if(!ref.contentHash().equals(rs.getString(1))||!ref.schemaDigest().equals(rs.getString(2))||!run.equals(rs.getString(3)))throw new IllegalStateException("Immutable metadata collision");return;}}}
        try(var q=c.prepareStatement("INSERT INTO resource_metadata(uri,version,content_hash,media_type,role,schema_id,schema_version,schema_digest,size_bytes,producer_capability,producer_version,run_id,created_at,data_start_date,data_end_date,row_count,retention_policy,execution_node) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            Object[] values={ref.uri(),ref.version(),ref.contentHash(),mediaType(ref),role,schemaId,"v1",ref.schemaDigest(),Files.size(ref.path()),producer,"v1",run,Instant.now().toString(),ref.start().format(DateTimeFormatter.BASIC_ISO_DATE),ref.end().format(DateTimeFormatter.BASIC_ISO_DATE),ref.rowCount(),"audit","local"};for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();
        }
        try(var q=c.prepareStatement("INSERT INTO resource_parents VALUES((SELECT resource_id FROM resource_metadata WHERE uri=? AND version=?),?,?,?,?,?)")){int ordinal=0;for(var p:parents){q.setString(1,ref.uri());q.setString(2,ref.version());q.setInt(3,ordinal++);q.setString(4,p.uri());q.setString(5,p.version());q.setString(6,p.contentHash());q.setString(7,mediaType(p));q.addBatch();}q.executeBatch();}
    }
    private static void alias(Connection c,String role,Ref ref)throws SQLException {registerAlias(c,"research.etf_only_top2."+role+"@latest",ref,PRODUCER);}
    public static void registerAlias(Connection c,String alias,Ref ref,String producer)throws SQLException {
        try(var q=c.prepareStatement("INSERT INTO resource_aliases VALUES(?,?,?,?,?,?,?) ON CONFLICT(alias) DO UPDATE SET target_uri=excluded.target_uri,target_version=excluded.target_version,target_content_hash=excluded.target_content_hash,target_media_type=excluded.target_media_type,updated_at=excluded.updated_at,updated_by=excluded.updated_by")){Object[] values={alias,ref.uri(),ref.version(),ref.contentHash(),mediaType(ref),Instant.now().toString(),producer};for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}
    }
    private static String mediaType(Ref ref){return ref.path().getFileName().toString().endsWith(".json")?"application/json":"application/vnd.apache.parquet";}
}
