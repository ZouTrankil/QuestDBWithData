package com.zoutrankil.data.maintenance.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacySyncMetadataRetirementTest {
    @TempDir Path root;
    private static final class Store {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final Set<String> remaining=new HashSet<>(List.of("income_period_meta","cashflow_period_meta"));
        final List<String> dropped=new ArrayList<>();
        final Map<String,Long> revisions=new HashMap<>();
        boolean unexpectedColumn;
        @SuppressWarnings("unchecked") Store() {
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call -> {
                String name=((Object[])call.getRawArguments()[1])[0].toString();
                return remaining.contains(name)?List.of(Map.of("id",1L,"directoryName",name+"~1","table_txn",revisions.getOrDefault(name,0L))):List.of();
            });
            when(jdbc.queryForList(anyString())).thenAnswer(call -> {
                String sql=call.getArgument(0);
                if(sql.contains("views()"))return List.of();
                if(sql.contains("current_data_id()"))return List.of(Map.of("current_data_id()","fixture-db"));
                if(sql.contains("table_columns"))return List.of(Map.of("column",unexpectedColumn?"business_value":"period","type","SYMBOL"),
                        Map.of("column","last_fetched_at","type","STRING"),Map.of("column","row_count","type","INT"));
                if(sql.startsWith("SHOW CREATE TABLE"))return List.of(Map.of("ddl",sql));
                throw new AssertionError(sql);
            });
            when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(1L);
            when(jdbc.query(anyString(),any(RowMapper.class))).thenAnswer(call -> List.of(
                    Map.of("period","20251231","last_fetched_at","2026-09-23 18:32:41","row_count",5752)));
            doAnswer(call -> {
                String sql=call.getArgument(0);String name=sql.substring(sql.indexOf('"')+1,sql.lastIndexOf('"'));
                assertTrue(remaining.remove(name));dropped.add(name);return null;
            }).when(jdbc).execute(anyString());
        }
    }

    @Test void archivesEveryFieldAndVerifiesLocalCopiesBeforeDroppingOnlyTheAllowlist() throws Exception {
        var store=new Store();Path ledger=root.resolve("sync.sqlite"),archive=root.resolve("archive");
        var service=new LegacySyncMetadataRetirement(store.jdbc,ledger,archive);
        var result=service.retire();assertEquals(2L,result.get("archivedRows"));assertEquals(2,store.dropped.size());
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+ledger);var q=db.createStatement();
            var rows=q.executeQuery("SELECT table_name,row_count,snapshot_json,archive_file FROM retired_questdb_sync_metadata")) {
            int count=0;
            while(rows.next()) {
                count++;byte[] snapshot=rows.getBytes(3);assertArrayEquals(snapshot,Files.readAllBytes(Path.of(rows.getString(4))));
                var json=new ObjectMapper().readTree(snapshot);assertEquals(1L,rows.getLong(2));
                assertEquals(5752,json.path("rows").get(0).path("row_count").asInt());
                assertEquals("2026-09-23 18:32:41",json.path("rows").get(0).path("last_fetched_at").asText());
                assertEquals("fixture-db",json.path("databaseIdentity").get(0).path("current_data_id()").asText());
            }
            assertEquals(2,count);
        }
        assertEquals(List.of(),service.retire().get("tables"));
    }

    @Test void archiveFailureDoesNotDropAnyRemoteTable() throws Exception {
        var store=new Store();Path blocked=Files.writeString(root.resolve("not-a-directory"),"occupied");
        assertThrows(Exception.class,() -> new LegacySyncMetadataRetirement(store.jdbc,root.resolve("sync.sqlite"),blocked).retire());
        assertTrue(store.dropped.isEmpty());verify(store.jdbc,never()).execute(anyString());
    }

    @Test void unknownSchemaIsNeverArchivedOrDroppedAsLegacyBookkeeping() {
        var store=new Store();store.unexpectedColumn=true;
        assertThrows(IllegalStateException.class,() -> new LegacySyncMetadataRetirement(store.jdbc,
                root.resolve("sync.sqlite"),root.resolve("archive")).retire());
        assertTrue(store.dropped.isEmpty());assertFalse(Files.exists(root.resolve("archive")));
    }
}
