import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.runtime.ProjectConfiguration;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 沿1029原备份显式续接维护；原失败、原意图和Native不改写，不重新发送行情。 */
public final class Plan1029WalResume {
    static JsonNode rows(String sql) throws Exception {
        return Plan1029WalMaintenance.rows(sql);
    }

    static JsonNode meta(String name) throws Exception {
        return Plan1029WalMaintenance.meta(name);
    }

    static JsonNode wal(String name) throws Exception {
        return Plan1029WalMaintenance.wal(name);
    }

    static void sql(String text) throws Exception {
        Plan1029WalMaintenance.request(text);
    }

    static void require(boolean value, String reason) {
        Plan1029WalMaintenance.require(value, reason);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "须指定原维护目录");
        Path folder = Path.of(args[0]).toAbsolutePath().normalize();
        Plan1029WalMaintenance.folder = folder;
        var env = ProjectConfiguration.load("us-data");
        Plan1029WalMaintenance.endpoint =
                env.getRequiredProperty("app.questdb.http-url").replaceAll("/$", "");
        Plan1029WalMaintenance.authorization =
                env.getProperty("app.questdb.http-authorization", "");
        Plan1029WalMaintenance.http =
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
        var j = Plan1029WalMaintenance.J;
        var intent = j.readTree(Files.readAllBytes(folder.resolve("repair-intent.json")));
        var plan = j.readTree(Files.readAllBytes(folder.resolve("repair-plan.json")));
        var backup = j.readTree(Files.readAllBytes(folder.resolve("backup-verified.json")));
        require(
                j.readTree(Files.readAllBytes(folder.resolve("repair-exit.json")))
                                .path("exitCode")
                                .asInt()
                        == 1,
                "原失败终态缺失");
        var old = ProcessHandle.of(intent.path("pid").asLong());
        require(
                old.isEmpty()
                        || !old.get().isAlive()
                        || !old.get()
                                .info()
                                .startInstant()
                                .orElse(Instant.EPOCH)
                                .toString()
                                .equals(intent.path("startedAt").asText()),
                "原维护仍运行，不能续接");
        require(!Files.exists(folder.resolve("repair-resume-intent.json")), "续接已启动，只能跟进原进程");
        require(
                backup.path("complete").asBoolean()
                        && backup.path("requestSha256")
                                .asText()
                                .equals(
                                        Plan1029WalMaintenance.sha(
                                                folder.resolve("backup-request.json"))),
                "备份与原暂停现场不符");
        var channels = new ArrayList<FileChannel>();
        var locks = new ArrayList<FileLock>();
        try {
            for (var item : plan.required("locks")) {
                var channel =
                        FileChannel.open(
                                Path.of(item.asText()),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                channels.add(channel);
                var lock = channel.tryLock();
                require(lock != null, "原采集或账本仍持锁");
                locks.add(lock);
            }
            Plan1029WalMaintenance.configuration();
            for (String table : Plan1029WalMaintenance.TARGETS) {
                require(
                        meta(table)
                                .path("id")
                                .equals(
                                        intent.path("originals")
                                                .path(table)
                                                .path("metadata")
                                                .path("id")),
                        "原物理目标已变化，不能重建或重发");
                require(
                        !Files.exists(folder.resolve(table + "-rebase-intent.json")),
                        "原表已经存在重建意图，须按原运行核对");
            }
            // 暂停前重新核对完整副本；不覆盖原备份或原失败现场。
            var differences =
                    j.readTree(
                            Files.readAllBytes(
                                    folder.resolve("resume-preflight-file-differences.json")));
            for (var file : backup.required("files")) {
                Path copy = Path.of(file.path("backup").asText()),
                        source = Path.of(file.path("source").asText());
                require(
                        Plan1029WalMaintenance.sha(copy).equals(file.path("sha256").asText()),
                        "原完整副本变化");
                String currentSha = Plan1029WalMaintenance.sha(source);
                if (currentSha.equals(file.path("sha256").asText())) continue;
                JsonNode found = null;
                for (var difference : differences)
                    if (difference.path("source").equals(file.path("source"))) found = difference;
                // RESUME关闭旧writer后会缩掉预分配尾部。只接受现场已记录的严格前缀，任何有效字节变化均拒绝。
                require(
                        found != null
                                && found.path("after").asText().equals(currentSha)
                                && Files.size(source) < Files.size(copy),
                        "原业务文件变化");
                byte[] currentBytes = Files.readAllBytes(source),
                        backupBytes = Files.readAllBytes(copy);
                require(
                        Arrays.equals(
                                currentBytes,
                                0,
                                currentBytes.length,
                                backupBytes,
                                0,
                                currentBytes.length),
                        "缩短后的原文件不是完整备份的相同前缀");
            }
            Plan1029WalMaintenance.save(
                    "repair-resume-intent.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "startedAt",
                            ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                            "parentIntentSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("repair-intent.json")),
                            "backupSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("backup-verified.json")),
                            "sourceRequests",
                            0,
                            "行情重发",
                            false));
            // 不再交错RESUME与SUSPEND；连续观察暂停稳定后才进入重建。
            for (String table : Plan1029WalMaintenance.TARGETS)
                sql("ALTER TABLE " + table + " SUSPEND WAL");
            for (int sample = 0; sample < 3; sample++) {
                Thread.sleep(1500);
                for (String table : Plan1029WalMaintenance.TARGETS) {
                    var state = wal(table);
                    require(state.path("suspended").asBoolean(), "暂停状态仍变化，保留原现场");
                    require(
                            state.path("writerTxn")
                                            .equals(
                                                    intent.path("originals")
                                                            .path(table)
                                                            .path("wal")
                                                            .path("writerTxn"))
                                    && state.path("sequencerTxn")
                                            .equals(
                                                    intent.path("originals")
                                                            .path(table)
                                                            .path("wal")
                                                            .path("sequencerTxn")),
                            "暂停期间原水位变化");
                }
            }
            Plan1029WalMaintenance.save(
                    "stable-suspension-proof.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "samples",
                            3,
                            "us",
                            wal("us_equity_daily"),
                            "crypto",
                            wal("crypto_ohlcv")));
            var all = j.createObjectNode();
            for (String table : Plan1029WalMaintenance.TARGETS) {
                var original = intent.path("originals").path(table);
                var metadata = original.path("metadata");
                require(
                        meta(table).path("id").equals(metadata.path("id"))
                                && wal(table).path("suspended").asBoolean(),
                        "原暂停目标变化");
                Plan1029WalMaintenance.save(
                        table + "-rebase-intent.json",
                        Map.of(
                                "at",
                                Instant.now().toString(),
                                "pid",
                                ProcessHandle.current().pid(),
                                "oldTableId",
                                metadata.path("id").asLong(),
                                "backupSha256",
                                Plan1029WalMaintenance.sha(folder.resolve("backup-verified.json")),
                                "resumeIntentSha256",
                                Plan1029WalMaintenance.sha(
                                        folder.resolve("repair-resume-intent.json"))));
                sql("ALTER TABLE " + table + " REBASE WAL");
                Plan1029WalMaintenance.settled(table);
                var current = meta(table);
                require(current.path("id").asLong() != metadata.path("id").asLong(), "原重建未形成新物理身份");
                if (!current.path("maxUncommittedRows").equals(metadata.path("maxUncommittedRows")))
                    sql(
                            "ALTER TABLE "
                                    + table
                                    + " SET PARAM maxUncommittedRows="
                                    + metadata.path("maxUncommittedRows").asLong());
                if (!current.path("o3MaxLag").equals(metadata.path("o3MaxLag")))
                    sql(
                            "ALTER TABLE "
                                    + table
                                    + " SET PARAM o3MaxLag="
                                    + metadata.path("o3MaxLag").asLong()
                                    + "us");
                Plan1029WalMaintenance.settled(table);
                current = meta(table);
                require(
                        rows("SELECT * FROM table_columns('" + table + "')")
                                .equals(original.path("columns")),
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
                                "o3MaxLag",
                                "ttlValue",
                                "ttlUnit"))
                    require(
                            current.path(field).equals(metadata.path(field)),
                            "原业务边界或表参数变化：" + field);
                var item = j.createObjectNode();
                item.set("oldMetadata", metadata);
                item.set("newMetadata", current);
                item.set("wal", wal(table));
                all.set(table, item);
                Plan1029WalMaintenance.save(table + "-rebase-result.json", item);
                System.out.println(table + "已在原备份下重建，原字段与业务边界保持，WAL结清。");
                System.out.flush();
            }
            Plan1029WalMaintenance.save(
                    "repair-java-verified.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "tables",
                            all,
                            "backupSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("backup-verified.json")),
                            "oldNativeNotModified",
                            true,
                            "fullColumnFileAndValueVerificationStillRequired",
                            true));
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).close();
            for (int i = channels.size() - 1; i >= 0; i--) channels.get(i).close();
        }
    }
}
