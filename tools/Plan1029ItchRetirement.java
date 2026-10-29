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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅退出已完整归档的ITCH样本；既有Native、原件和其他市场表保持原位。 */
public final class Plan1029ItchRetirement {
    public static void main(String[] args) throws Exception {
        Plan1029WalMaintenance.require(args.length == 1, "须指定1029原证据目录");
        Path base = Path.of(args[0]).toAbsolutePath().normalize(),
                folder = base.resolve("itch-retirement");
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
        var archive = j.readTree(Files.readAllBytes(folder.resolve("archive-verified.json")));
        var proof = j.readTree(Files.readAllBytes(folder.resolve("pgwire-full-proof.json")));
        var plan = j.readTree(Files.readAllBytes(base.resolve("repair-plan.json")));
        Plan1029WalMaintenance.require(
                !Files.exists(folder.resolve("retire-intent.json")), "已有退役意图，只能核对原运行");
        Plan1029WalMaintenance.require(
                archive.path("complete").asBoolean()
                        && proof.path("allSixteenColumnsExact").asBoolean()
                        && proof.path("integerNanosecondsExact").asBoolean()
                        && proof.path("rows").asLong() == 450836,
                "完整归档或PGWire证明不足");
        for (String field : List.of("physicalZip", "logicalExport"))
            Plan1029WalMaintenance.require(
                    Plan1029WalMaintenance.sha(Path.of(archive.path(field).asText()))
                            .equals(archive.path(field + "Sha256").asText()),
                    "原归档摘要变化");
        var channels = new ArrayList<FileChannel>();
        var locks = new ArrayList<FileLock>();
        try {
            var paths = new ArrayList<Path>();
            for (var item : plan.path("locks")) paths.add(Path.of(item.asText()));
            Path root = Path.of(env.getRequiredProperty("app.market-data.root"));
            paths.add(root.resolve("us-itch-source.lock"));
            for (Path path : paths) {
                var channel =
                        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                channels.add(channel);
                var lock = channel.tryLock();
                Plan1029WalMaintenance.require(lock != null, "原正式写入或ITCH来源仍持锁");
                locks.add(lock);
            }
            String table = "us_nasdaq_itch_messages";
            var meta = Plan1029WalMaintenance.meta(table);
            var wal = Plan1029WalMaintenance.wal(table);
            Plan1029WalMaintenance.require(
                    meta.path("id").asLong() == 3208
                            && meta.path("table_row_count").asLong() == 450836
                            && wal.path("writerTxn").asLong() == 131
                            && wal.path("sequencerTxn").asLong() == 131
                            && !wal.path("suspended").asBoolean()
                            && wal.path("bufferedTxnSize").asLong() == 0,
                    "原ITCH身份或WAL变化");
            Plan1029WalMaintenance.require(
                    Plan1029WalMaintenance.rows("SELECT * FROM table_columns('" + table + "')")
                            .equals(archive.path("columns")),
                    "原完整字段变化");
            // 同时检查普通视图和原生MV，不按目录名或前缀批量删除。
            for (String sql :
                    List.of("SELECT * FROM views()", "SELECT * FROM materialized_views()"))
                for (var row : Plan1029WalMaintenance.rows(sql))
                    Plan1029WalMaintenance.require(
                            !row.toString().toLowerCase(Locale.ROOT).contains(table),
                            "ITCH仍有数据库消费者");
            Path physical =
                    Path.of(
                                    j.readTree(
                                                    Files.readAllBytes(
                                                            base.resolve("backup-verified.json")))
                                            .path("databaseRoot")
                                            .asText())
                            .resolve(meta.path("directoryName").asText());
            for (var file : archive.path("files"))
                Plan1029WalMaintenance.require(
                        Plan1029WalMaintenance.sha(physical.resolve(file.path("relative").asText()))
                                .equals(file.path("sha256").asText()),
                        "ITCH原文件变化");
            Plan1029WalMaintenance.save(
                    "retire-intent.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "pid",
                            ProcessHandle.current().pid(),
                            "metadata",
                            meta,
                            "wal",
                            wal,
                            "archiveSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("archive-verified.json")),
                            "pgWireProofSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("pgwire-full-proof.json")),
                            "sourceRequests",
                            0,
                            "oldNativePreserved",
                            true));
            Path stop = root.resolve("us-itch-stop.request");
            if (!Files.exists(stop))
                Files.writeString(
                        stop,
                        "2026-10-30：按用户要求退出ITCH；原归档和Native保留。",
                        StandardOpenOption.CREATE_NEW);
            Plan1029WalMaintenance.request("ALTER TABLE " + table + " SUSPEND WAL");
            Plan1029WalMaintenance.require(
                    Plan1029WalMaintenance.wal(table).path("suspended").asBoolean(), "ITCH暂停未确认");
            Plan1029WalMaintenance.request("DROP TABLE " + table);
            Plan1029WalMaintenance.require(
                    Plan1029WalMaintenance.rows(
                                    "SELECT id FROM tables() WHERE table_name='" + table + "'")
                            .isEmpty(),
                    "ITCH正式表仍存在");
            Plan1029WalMaintenance.save(
                    "retired-verified.json",
                    Map.of(
                            "at",
                            Instant.now().toString(),
                            "table",
                            table,
                            "oldTableId",
                            3208,
                            "archivedRows",
                            450836,
                            "formalTableAbsent",
                            true,
                            "oldNativePreserved",
                            true,
                            "archiveManifestSha256",
                            Plan1029WalMaintenance.sha(folder.resolve("archive-verified.json"))));
            System.out.println("ITCH完整归档后已移出正式库，原件、纳秒和Native保留。");
        } finally {
            for (int i = locks.size() - 1; i >= 0; i--) locks.get(i).close();
            for (int i = channels.size() - 1; i >= 0; i--) channels.get(i).close();
        }
    }
}
