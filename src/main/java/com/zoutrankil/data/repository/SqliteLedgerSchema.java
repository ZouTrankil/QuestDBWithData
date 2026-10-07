package com.zoutrankil.data.repository;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Schema probes that leave history admission and its failure policy to the caller. */
public final class SqliteLedgerSchema {
    private SqliteLedgerSchema() {}

    public static Set<String> tableNames(Path path) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro")) {
            return tableNames(connection);
        }
    }

    public static Set<String> tableNames(Connection connection) throws SQLException {
        var names = new HashSet<String>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    public static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            query.setString(1, table);
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    /** Preserves the legacy refresh gate's connection mode and single-table check. */
    public static boolean hasRunHistoryTable(Path path) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var query = connection.prepareStatement("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='sync_runs'")) {
            try (var rows = query.executeQuery()) { return rows.next() && rows.getInt(1) != 0; }
        }
    }
}
