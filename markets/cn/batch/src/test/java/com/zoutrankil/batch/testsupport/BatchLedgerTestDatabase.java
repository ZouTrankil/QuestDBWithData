package com.zoutrankil.batch.testsupport;

import com.zoutrankil.batch.domain.RunRequest;
import com.zoutrankil.batch.storage.SqliteLedger;
import com.zoutrankil.batch.writer.DurableWriter;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;

/** A fresh connection per operation makes persisted state visible independently of a writer transaction. */
public final class BatchLedgerTestDatabase {
    public final Path file;
    public final SQLiteDataSource dataSource;
    public final JdbcTemplate jdbc;
    public final SqliteLedger ledger;

    private BatchLedgerTestDatabase(Path file) {
        this.file = file;
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        ledger = new SqliteLedger(dataSource);
    }

    public static BatchLedgerTestDatabase create(Path directory) throws IOException {
        Files.createDirectories(directory);
        var database = new BatchLedgerTestDatabase(directory.resolve("metadata.sqlite"));
        Flyway.configure()
                .dataSource(database.dataSource)
                .locations("classpath:db/migration/batch")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        return database;
    }

    public BatchLedgerTestDatabase reopen() {
        return new BatchLedgerTestDatabase(file);
    }

    public RunRequest register(String id) {
        var date = LocalDate.of(2026, 9, 29);
        var time = Instant.parse("2026-09-29T10:30:00Z");
        var request =
                new RunRequest(
                        id,
                        "post_close",
                        date,
                        date,
                        date,
                        "v1",
                        "0",
                        null,
                        null,
                        "source-v1",
                        "calendar-v1",
                        "Asia/Shanghai",
                        time,
                        time);
        return ledger.register(request);
    }

    public DurableWriter.Intent intent(String batchId, String instanceId, String target)
            throws Exception {
        Path artifact = file.resolveSibling(batchId + ".json");
        byte[] bytes = "immutable-source\n".getBytes(StandardCharsets.UTF_8);
        Files.write(artifact, bytes);
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new DurableWriter.Intent(
                batchId, instanceId, target, "test-owner", digest, artifact.toString(), 1);
    }

    public static SqliteLedger.WriteIntentRecord stored(DurableWriter.Intent intent) {
        return new SqliteLedger.WriteIntentRecord(
                intent.batchId(),
                intent.instanceId(),
                intent.target(),
                intent.owner(),
                intent.sourceFingerprint(),
                intent.artifact(),
                intent.expectedRows());
    }

    public long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
