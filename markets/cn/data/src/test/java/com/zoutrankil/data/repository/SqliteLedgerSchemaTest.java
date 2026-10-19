package com.zoutrankil.data.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqliteLedgerSchemaTest {
    @TempDir Path root;

    @Test void readOnlyProbeDoesNotCreateMissingFilesAndPropagatesCorruptDatabaseFailures() throws Exception {
        var missing = root.resolve("missing.sqlite3");
        assertThrows(SQLException.class, () -> SqliteLedgerSchema.tableNames(missing));
        assertFalse(Files.exists(missing));
        var corrupt = root.resolve("corrupt.sqlite3");
        Files.writeString(corrupt, "This is not a SQLite database.");
        var before = Files.readAllBytes(corrupt);
        assertThrows(SQLException.class, () -> SqliteLedgerSchema.tableNames(corrupt));
        assertArrayEquals(before, Files.readAllBytes(corrupt));
    }

    @Test void probesOnlyTablesAndKeepsTheSingleTableRefreshGate() throws Exception {
        var path = root.resolve("tables.sqlite3");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schedule_jobs(id TEXT)");
            statement.execute("CREATE VIEW sync_runs AS SELECT id FROM schedule_jobs");
        }
        var before = Files.readAllBytes(path);
        assertEquals(Set.of("schedule_jobs"), SqliteLedgerSchema.tableNames(path));
        assertFalse(SqliteLedgerSchema.hasRunHistoryTable(path));
        assertArrayEquals(before, Files.readAllBytes(path));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            assertFalse(SqliteLedgerSchema.tableExists(connection, "sync_runs"));
            assertTrue(SqliteLedgerSchema.tableExists(connection, "schedule_jobs"));
            assertFalse(SqliteLedgerSchema.tableExists(connection, "schedule_jobs' OR 1=1 --"));
            statement.execute("DROP VIEW sync_runs");
            statement.execute("CREATE TABLE sync_runs(id TEXT)");
        }
        assertTrue(SqliteLedgerSchema.hasRunHistoryTable(path));
        assertEquals(Set.of("schedule_jobs", "sync_runs"), SqliteLedgerSchema.tableNames(path));
    }

    @Test void suppliedConnectionRemainsOpenAndKeepsItsTransaction() throws Exception {
        var path = root.resolve("transaction.sqlite3");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE pending_table(id TEXT)");
            }
            assertEquals(Set.of("pending_table"), SqliteLedgerSchema.tableNames(connection));
            assertTrue(SqliteLedgerSchema.tableExists(connection, "pending_table"));
            assertFalse(connection.isClosed());
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            assertFalse(SqliteLedgerSchema.tableExists(connection, "pending_table"));
        }
        assertTrue(SqliteLedgerSchema.tableNames(path).isEmpty());
    }
}
