package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQLite schedule authority. A claimed slot is never replayed automatically after a crash. */
public final class SyncScheduleStore {
    public enum State { CLAIMED, VERIFIED, VERIFIED_EMPTY, PARTIAL, FAILED, IN_DOUBT,
        CANCELLED, MISSED, SKIPPED_REENTRY }
    public record History(String scheduleId, Instant dueAt, State state, String runId, String detail) {}
    private final Path path;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public SyncScheduleStore(Path path) throws Exception {
        this.path = path.toAbsolutePath().normalize();
        Files.createDirectories(this.path.getParent());
        try (var c = open(); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS sync_schedule_defs(id TEXT PRIMARY KEY, definition TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sync_schedule_history(schedule_id TEXT NOT NULL, due_at TEXT NOT NULL, "
                    + "state TEXT NOT NULL, run_id TEXT, detail TEXT NOT NULL, "
                    + "PRIMARY KEY(schedule_id,due_at))");
            s.execute("CREATE INDEX IF NOT EXISTS sync_schedule_state ON sync_schedule_history(schedule_id,state)");
        }
    }
    private Connection open() throws SQLException {
        var c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (var s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA synchronous=FULL");
        } catch (SQLException failure) { c.close(); throw failure; }
        return c;
    }
    public void put(SyncScheduleDefinition definition) throws Exception {
        String json = JSON.writeValueAsString(definition);
        try (var c = open()) {
            try (var begin=c.createStatement()) { begin.execute("BEGIN IMMEDIATE"); }
            try {
                SyncScheduleDefinition previous=null;
                try (var s=c.prepareStatement("SELECT definition FROM sync_schedule_defs WHERE id=?")) {
                    s.setString(1,definition.scheduleId());
                    try (var r=s.executeQuery()) {
                        if (r.next()) previous=JSON.readValue(r.getString(1),SyncScheduleDefinition.class);
                    }
                }
                if (previous!=null && !previous.withEnabled(definition.enabled()).equals(definition)) {
                    try (var s=c.prepareStatement("SELECT count(*) FROM sync_schedule_history WHERE schedule_id=?")) {
                        s.setString(1,definition.scheduleId());
                        try (var r=s.executeQuery()) {
                            if (r.next() && r.getInt(1)>0)
                                throw new IllegalArgumentException("Schedule with history requires a new ID for definition changes");
                        }
                    }
                }
                try (var s = c.prepareStatement("INSERT INTO sync_schedule_defs VALUES(?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET definition=excluded.definition")) {
                    s.setString(1, definition.scheduleId()); s.setString(2, json); s.executeUpdate();
                }
                try (var commit=c.createStatement()) { commit.execute("COMMIT"); }
            } catch (Exception failure) {
                try (var rollback=c.createStatement()) { rollback.execute("ROLLBACK"); }
                throw failure;
            }
        }
    }
    public SyncScheduleDefinition get(String id) throws Exception {
        try (var c = open(); var s = c.prepareStatement("SELECT definition FROM sync_schedule_defs WHERE id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalArgumentException("Unknown schedule: " + id);
                return JSON.readValue(r.getString(1), SyncScheduleDefinition.class);
            }
        }
    }
    public List<SyncScheduleDefinition> list() throws Exception {
        var result = new ArrayList<SyncScheduleDefinition>();
        try (var c = open(); var s = c.createStatement();
             var r = s.executeQuery("SELECT definition FROM sync_schedule_defs ORDER BY id")) {
            while (r.next()) result.add(JSON.readValue(r.getString(1), SyncScheduleDefinition.class));
        }
        return List.copyOf(result);
    }
    public List<History> history(String id, int limit) throws SQLException {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Bounded history limit required");
        var result = new ArrayList<History>();
        try (var c = open(); var s = c.prepareStatement("SELECT due_at,state,run_id,detail FROM sync_schedule_history "
                + "WHERE schedule_id=? ORDER BY due_at DESC LIMIT ?")) {
            s.setString(1,id); s.setInt(2,limit);
            try (var r = s.executeQuery()) { while (r.next()) result.add(new History(id,Instant.parse(r.getString(1)),
                    State.valueOf(r.getString(2)),r.getString(3),r.getString(4))); }
        }
        return List.copyOf(result);
    }
    /** Transactional slot claim and reentry check. False also means this slot was already recorded. */
    public boolean claim(String id, Instant dueAt, State initial, String detail) throws SQLException {
        if (initial != State.CLAIMED && initial != State.MISSED)
            throw new IllegalArgumentException("Only due or missed slots can be claimed");
        try (var c = open()) {
            try (var begin = c.createStatement()) { begin.execute("BEGIN IMMEDIATE"); }
            try {
                int active;
                try (var s = c.prepareStatement("SELECT count(*) FROM sync_schedule_history WHERE schedule_id=? AND state IN ('CLAIMED','IN_DOUBT')")) {
                    s.setString(1,id);
                    try (var r = s.executeQuery()) { active = r.next() ? r.getInt(1) : 0; }
                }
                State state = initial == State.CLAIMED && active > 0 ? State.SKIPPED_REENTRY : initial;
                int inserted;
                try (var s = c.prepareStatement("INSERT OR IGNORE INTO sync_schedule_history VALUES(?,?,?,NULL,?)")) {
                    s.setString(1,id); s.setString(2,dueAt.toString()); s.setString(3,state.name());
                    s.setString(4,detail); inserted=s.executeUpdate();
                }
                try (var commit = c.createStatement()) { commit.execute("COMMIT"); }
                return inserted == 1 && state == State.CLAIMED;
            } catch (SQLException failure) {
                try (var rollback = c.createStatement()) { rollback.execute("ROLLBACK"); }
                throw failure;
            }
        }
    }
    public void finish(String id, Instant dueAt, State state, String runId, String detail) throws SQLException {
        if (state == State.CLAIMED || state == State.MISSED || state == State.SKIPPED_REENTRY)
            throw new IllegalArgumentException("Actual run outcome required");
        try (var c = open(); var s = c.prepareStatement("UPDATE sync_schedule_history SET state=?,run_id=?,detail=? "
                + "WHERE schedule_id=? AND due_at=? AND state='CLAIMED'")) {
            s.setString(1,state.name()); s.setString(2,runId); s.setString(3,detail);
            s.setString(4,id); s.setString(5,dueAt.toString());
            if (s.executeUpdate()!=1) throw new IllegalStateException("Schedule slot is not uniquely claimed");
        }
    }
}
