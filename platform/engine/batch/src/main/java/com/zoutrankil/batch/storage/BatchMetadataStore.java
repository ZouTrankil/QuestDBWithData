package com.zoutrankil.batch.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConfig.JournalMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import javax.sql.DataSource;

/** Shared local metadata infrastructure for standalone and composition runtimes. */
public final class BatchMetadataStore {
    private BatchMetadataStore() {}

    public static DataSource metadataDataSource(String url) {
        validateMetadataUrl(url);
        var sqlite = new SQLiteConfig();
        sqlite.setBusyTimeout(10_000);
        sqlite.setJournalMode(JournalMode.WAL);
        sqlite.enforceForeignKeys(true);
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDataSourceProperties(sqlite.toProperties());
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(10_000);
        return new HikariDataSource(config);
    }

    public static SqliteOwnershipLock.Handle metadataRuntimeLease(DataSource dataSource)
            throws SQLException {
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        return SqliteOwnershipLock.tryAcquire(url, "runtime")
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Another Java runtime already owns this SQLite metadata file"));
    }

    public static void validateMetadataUrl(String url) {
        if (url == null
                || !url.startsWith("jdbc:sqlite:")
                || url.equals("jdbc:sqlite::memory:")
                || url.contains("mode=memory")
                || url.contains("?"))
            throw new IllegalArgumentException("Metadata must use a persistent local SQLite file");
        Path file = Path.of(url.substring("jdbc:sqlite:".length())).toAbsolutePath().normalize();
        if (Files.isDirectory(file))
            throw new IllegalArgumentException("SQLite metadata path must be a file");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot create SQLite metadata directory", error);
        }
    }

    public static Flyway metadataFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/batch")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load();
    }
}
