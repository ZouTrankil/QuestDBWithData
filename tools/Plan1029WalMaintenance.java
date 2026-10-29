import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.io.InputStream;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 1029任务的一次性维护入口：先验证隔离样本，实际表须停写、备份并逐文件核对。 */
public final class Plan1029WalMaintenance {
    static final ObjectMapper J = new ObjectMapper();
    static final List<String> TARGETS = List.of("us_equity_daily", "crypto_ohlcv");
    static final String FIXTURE = "java_plan1029_wal_fixture_20261030";
    static Path folder;
    static String endpoint, authorization;
    static HttpClient http;

    static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    static String sha(Path file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] bytes = new byte[1024 * 1024];
            for (int n; (n = stream.read(bytes)) >= 0; ) if (n > 0) md.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static void save(String name, Object value) throws Exception {
        Files.write(
                folder.resolve(name),
                J.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
                StandardOpenOption.CREATE_NEW);
    }

    static JsonNode request(String sql) throws Exception {
        var builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        endpoint
                                                + "/exec?query="
                                                + URLEncoder.encode(sql, StandardCharsets.UTF_8)
                                                + "&limit=0,100001"))
                        .timeout(Duration.ofMinutes(5))
                        .GET();
        if (!authorization.isBlank()) builder.header("Authorization", authorization);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        var body = J.readTree(response.body());
        require(
                response.statusCode() == 200 && !body.has("error"),
                "维护SQL失败：" + body.path("error").asText("HTTP " + response.statusCode()));
        return body;
    }

    static ArrayNode rows(String sql) throws Exception {
        var body = request(sql);
        var values = J.createArrayNode();
        for (var record : body.path("dataset")) {
            var object = J.createObjectNode();
            for (int i = 0; i < body.path("columns").size(); i++)
                object.set(body.path("columns").get(i).path("name").asText(), record.get(i));
            values.add(object);
        }
        return values;
    }

    static JsonNode one(String sql) throws Exception {
        var found = rows(sql);
        require(found.size() == 1, "维护目标必须唯一");
        return found.get(0);
    }

    static JsonNode wal(String table) throws Exception {
        return one("SELECT * FROM wal_tables() WHERE name='" + table + "'");
    }

    static JsonNode meta(String table) throws Exception {
        return one("SELECT * FROM tables() WHERE table_name='" + table + "'");
    }

    static void settled(String table) throws Exception {
        Instant deadline = Instant.now().plusSeconds(90);
        while (true) {
            var state = wal(table);
            if (!state.path("suspended").asBoolean()
                    && state.path("writerTxn").asLong() == state.path("sequencerTxn").asLong()
                    && state.path("bufferedTxnSize").asLong() == 0) return;
            require(Instant.now().isBefore(deadline), "维护目标水位未结清，保留原意图：" + table);
            Thread.sleep(500);
        }
    }

    static void configuration() throws Exception {
        var flag =
                one(
                        "(SHOW PARAMETERS) WHERE property_path='cairo.wal.apply.suspended.write.denied'");
        require(flag.path("value").asText().equals("true"), "须先显式启用暂停表拒写，本入口不修改服务配置");
    }

    static void fixture() throws Exception {
        configuration();
        require(
                rows("SELECT id FROM tables() WHERE table_name='" + FIXTURE + "'").isEmpty(),
                "隔离样本已存在，不能覆盖");
        save(
                "fixture-intent.json",
                Map.of(
                        "at",
                        Instant.now().toString(),
                        "pid",
                        ProcessHandle.current().pid(),
                        "table",
                        FIXTURE));
        request(
                "CREATE TABLE "
                        + FIXTURE
                        + " (ts TIMESTAMP,label SYMBOL,value DOUBLE) TIMESTAMP(ts) PARTITION BY DAY WAL DEDUP UPSERT KEYS(ts,label)");
        request(
                "INSERT INTO "
                        + FIXTURE
                        + " VALUES ('2020-01-01T00:00:00.123456Z','A',1.25),('2026-01-01T00:00:00.654321Z','B',2.5)");
        settled(FIXTURE);
        var before = meta(FIXTURE);
        var data = rows("SELECT * FROM " + FIXTURE + " ORDER BY ts,label");
        request("ALTER TABLE " + FIXTURE + " SUSPEND WAL");
        request("ALTER TABLE " + FIXTURE + " REBASE WAL");
        // 当前版本有种子事务与首笔写入合并的已知问题；等种子单独结清后才允许新写入。
        settled(FIXTURE);
        var rebased = meta(FIXTURE);
        require(
                before.path("id").asLong() != rebased.path("id").asLong()
                        && rows("SELECT * FROM " + FIXTURE + " ORDER BY ts,label").equals(data),
                "隔离重建改变原值");
        require(
                before.path("table_min_timestamp").equals(rebased.path("table_min_timestamp")),
                "隔离重建改变最早时刻");
        request("INSERT INTO " + FIXTURE + " VALUES ('2022-01-01T00:00:00.000000Z','C',3.75)");
        settled(FIXTURE);
        request("INSERT INTO " + FIXTURE + " VALUES ('2022-01-01T00:00:00.000000Z','C',4.5)");
        settled(FIXTURE);
        var after = rows("SELECT * FROM " + FIXTURE + " ORDER BY ts,label");
        require(
                after.size() == 3
                        && after.get(0).equals(data.get(0))
                        && after.get(2).equals(data.get(1))
                        && after.get(1).path("value").asDouble() == 4.5,
                "隔离重建后的乱序或业务键去重错误");
        require(
                meta(FIXTURE)
                        .path("table_min_timestamp")
                        .equals(before.path("table_min_timestamp")),
                "首笔写入改变最早时刻");
        request("DROP TABLE " + FIXTURE);
        save(
                "fixture-verified.json",
                Map.of(
                        "at",
                        Instant.now().toString(),
                        "before",
                        before,
                        "rebased",
                        rebased,
                        "rowsAfterWrites",
                        after,
                        "originalValuesExact",
                        true,
                        "outOfOrderAndDedupPassed",
                        true,
                        "seedDrainBeforeNewWrite",
                        true,
                        "fixtureDropped",
                        true));
        System.out.println("隔离REBASE样本通过：原字段、微秒、乱序、去重与最早时刻均保持。");
    }

    static void repair() throws Exception {
        require(Files.exists(folder.resolve("fixture-verified.json")), "须先通过当前服务的隔离样本");
        configuration();
        var plan = J.readTree(Files.readAllBytes(folder.resolve("repair-plan.json")));
        var channels = new ArrayList<FileChannel>();
        var locks = new ArrayList<FileLock>();
        try {
            for (var node : plan.required("locks")) {
                var channel =
                        FileChannel.open(
                                Path.of(node.asText()),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                channels.add(channel);
                var lock = channel.tryLock();
                require(lock != null, "原采集或账本仍持锁");
                locks.add(lock);
            }
            var originals = J.createObjectNode();
            for (String table : TARGETS) {
                var expected = plan.path("tables").path(table);
                var actual = meta(table);
                var state = wal(table);
                require(
                        actual.path("id").equals(expected.path("id"))
                                && actual.path("directoryName")
                                        .equals(expected.path("directoryName")),
                        "原物理目标变化");
                require(
                        state.path("writerTxn").asLong() == expected.path("writerTxn").asLong()
                                && state.path("sequencerTxn").asLong()
                                        == expected.path("sequencerTxn").asLong(),
                        "原WAL状态变化");
                require(
                        state.path("writerTxn").asLong() > state.path("sequencerTxn").asLong(),
                        "本计划只处理已证明的反向水位异常");
                require(
                        one("SELECT count() AS n FROM wal_transactions('"
                                                + table
                                                + "') WHERE sequencerTxn>"
                                                + state.path("writerTxn").asLong())
                                        .path("n")
                                        .asLong()
                                == 0,
                        "存在未应用事务，不能重建");
                var entry = J.createObjectNode();
                entry.set("metadata", actual);
                entry.set("wal", state);
                entry.set("columns", rows("SELECT * FROM table_columns('" + table + "')"));
                entry.set("ddl", rows("SHOW CREATE TABLE " + table));
                entry.set("partitions", rows("SELECT * FROM table_partitions('" + table + "')"));
                originals.set(table, entry);
            }
            save(
                    "repair-intent.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "startedAt",
                            ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                            "originals",
                            originals,
                            "planSha256",
                            sha(folder.resolve("repair-plan.json")),
                            "automaticRetryForbidden",
                            true));
            for (String table : TARGETS) {
                try {
                    request("ALTER TABLE " + table + " RESUME WAL");
                } catch (Exception e) {
                    save(
                            table + "-resume-observation.json",
                            Map.of("at", Instant.now().toString(), "message", e.getMessage()));
                }
                request("ALTER TABLE " + table + " SUSPEND WAL");
                require(wal(table).path("suspended").asBoolean(), "原表未进入拒写暂停状态");
            }
            save(
                    "backup-request.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "originals",
                            originals));
            System.out.println("两张表已拒写暂停，等待同一进程的完整物理备份核验。");
            System.out.flush();
            Instant deadline = Instant.now().plusSeconds(7200);
            while (!Files.exists(folder.resolve("backup-verified.json"))) {
                require(Instant.now().isBefore(deadline), "备份核验超时，原表保持暂停，禁止重发");
                Thread.sleep(1000);
            }
            var backup = J.readTree(Files.readAllBytes(folder.resolve("backup-verified.json")));
            require(
                    backup.path("complete").asBoolean()
                            && backup.path("requestSha256")
                                    .asText()
                                    .equals(sha(folder.resolve("backup-request.json"))),
                    "备份未绑定原暂停现场");
            for (var entry : backup.required("files")) {
                require(
                        sha(Path.of(entry.required("backup").asText()))
                                        .equals(entry.path("sha256").asText())
                                && sha(Path.of(entry.required("source").asText()))
                                        .equals(entry.path("sha256").asText()),
                        "完整备份与原表文件不一致");
            }
            var result = J.createObjectNode();
            for (String table : TARGETS) {
                require(
                        meta(table)
                                        .path("id")
                                        .equals(originals.path(table).path("metadata").path("id"))
                                && wal(table).path("suspended").asBoolean(),
                        "原暂停目标变化");
                save(
                        table + "-rebase-intent.json",
                        Map.of(
                                "at",
                                Instant.now().toString(),
                                "backupSha256",
                                sha(folder.resolve("backup-verified.json")),
                                "oldTableId",
                                meta(table).path("id").asLong()));
                request("ALTER TABLE " + table + " REBASE WAL");
                settled(table);
                var old = originals.path(table).path("metadata");
                var now = meta(table);
                require(now.path("id").asLong() != old.path("id").asLong(), "未形成新的物理身份");
                // REBASE可能复位表参数；沿旧值恢复，不改变业务字段或去重键。
                if (!now.path("maxUncommittedRows").equals(old.path("maxUncommittedRows")))
                    request(
                            "ALTER TABLE "
                                    + table
                                    + " SET PARAM maxUncommittedRows="
                                    + old.path("maxUncommittedRows").asLong());
                if (!now.path("o3MaxLag").equals(old.path("o3MaxLag")))
                    request(
                            "ALTER TABLE "
                                    + table
                                    + " SET PARAM o3MaxLag="
                                    + old.path("o3MaxLag").asLong()
                                    + "us");
                settled(table);
                now = meta(table);
                require(
                        now.path("ttlValue").equals(old.path("ttlValue"))
                                && now.path("ttlUnit").equals(old.path("ttlUnit")),
                        "TTL变化，须显式核对，不能继续新采集");
                require(
                        rows("SELECT * FROM table_columns('" + table + "')")
                                .equals(originals.path(table).path("columns")),
                        "完整字段定义变化");
                for (String field :
                        List.of(
                                "table_row_count",
                                "table_min_timestamp",
                                "table_max_timestamp",
                                "partitionBy",
                                "designatedTimestamp",
                                "dedup",
                                "maxUncommittedRows",
                                "o3MaxLag"))
                    require(now.path(field).equals(old.path(field)), "重建改变业务边界或表参数：" + field);
                var item = J.createObjectNode();
                item.set("oldMetadata", old);
                item.set("newMetadata", now);
                item.set("wal", wal(table));
                result.set(table, item);
                save(table + "-rebase-result.json", item);
                System.out.println(table + "原字段定义、业务边界和表参数保持，新WAL已结清。");
                System.out.flush();
            }
            save(
                    "repair-java-verified.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "tables",
                            result,
                            "backupSha256",
                            sha(folder.resolve("backup-verified.json")),
                            "oldNativeNotModified",
                            true,
                            "fullColumnFileAndValueVerificationStillRequired",
                            true));
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).close();
            for (int i = channels.size() - 1; i >= 0; i--) channels.get(i).close();
        }
    }

    public static void main(String[] args) throws Exception {
        require(
                args.length == 2 && Set.of("fixture", "repair").contains(args[0]),
                "须明确fixture或repair及证据目录");
        folder = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(folder);
        var env = ProjectConfiguration.load("us-data");
        endpoint = env.getRequiredProperty("app.questdb.http-url").replaceAll("/$", "");
        authorization = env.getProperty("app.questdb.http-authorization", "");
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .proxy(
                                new ProxySelector() {
                                    public List<Proxy> select(URI uri) {
                                        return List.of(Proxy.NO_PROXY);
                                    }

                                    public void connectFailed(
                                            URI uri,
                                            SocketAddress address,
                                            java.io.IOException error) {}
                                })
                        .build();
        if (args[0].equals("fixture")) fixture();
        else repair();
    }
}
