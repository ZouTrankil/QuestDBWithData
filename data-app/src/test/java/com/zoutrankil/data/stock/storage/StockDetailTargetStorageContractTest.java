package com.zoutrankil.data.stock.storage;

import com.zoutrankil.data.domain.StockDetailInfoDataset;
import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockDetailTargetStorageContractTest {
    private static final String TABLE = "stock_detail_info";
    private static final String PROJECTION = "ts_code,cast(update_time as long) AS update_micros,symbol,name,market,exchange,"
            + "list_status,list_date,fullname,enname,cnspell,area,industry,curr_type,delist_date,is_hs,act_name,act_ent_type";
    private static final String META = "SELECT id,directoryName,designatedTimestamp,partitionBy,walEnabled,dedup FROM tables() WHERE table_name=?";

    @Test void fullSnapshotKeepsRawLegacyDatesNegativeMicrosecondsAndExactFingerprintBytes() throws Exception {
        try (var h = new Harness()) {
            var snapshot = h.storage.snapshot(); var raw = snapshot.rows().getFirst();
            assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), raw.updateTime());
            assertEquals("None", raw.listDate()); assertEquals("", raw.delistDate());
            assertNull(snapshot.businessRows().getFirst().listingDate()); assertNull(snapshot.businessRows().getFirst().delistingDate());
            String golden = "[{\"tsCode\":\"T600018.SH\",\"updateTime\":\"1969-12-31T23:59:59.999999Z\","
                    + "\"symbol\":\"600018\",\"name\":\"legacy\",\"market\":null,\"exchange\":null,\"listStatus\":\"L\","
                    + "\"listDate\":\"None\",\"fullname\":null,\"enname\":null,\"cnspell\":null,\"area\":null,\"industry\":null,"
                    + "\"currType\":null,\"delistDate\":\"\",\"isHs\":null,\"actName\":null,\"actEntType\":null}]";
            byte[] expected = golden.getBytes(StandardCharsets.UTF_8);
            assertEquals(expected.length, snapshot.bytes());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), snapshot.fingerprint());
            verify(h.jdbc).query(eq("SELECT " + PROJECTION + " FROM stock_detail_info ORDER BY ts_code LIMIT 10001"), any(RowMapper.class));
            verify(h.jdbc, times(2)).queryForList(META, TABLE);
        }
    }

    @Test @SuppressWarnings("unchecked")
    void snapshotRejectsDuplicateKeysAndIdentityChangesAfterReadback() throws Exception {
        try (var h = new Harness()) {
            when(h.jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(i -> {
                var mapper = (RowMapper<StockDetailInfoRow>) i.getArgument(1); var row = mapper.mapRow(h.row, 0);
                return List.of(row, row);
            });
            assertEquals("Duplicate static-table stock identity", assertThrows(IllegalStateException.class, h.storage::snapshot).getMessage());
        }
        try (var h = new Harness()) {
            when(h.jdbc.queryForList(META, TABLE)).thenReturn(List.of(metadata(7)), List.of(metadata(8)));
            assertEquals("Static table changed identity during read", assertThrows(IllegalStateException.class, h.storage::snapshot).getMessage());
        }
    }

    @Test void explicitKeyReadbackKeepsOrderParametersSentinelAndNoIoForInvalidKeys() throws Exception {
        try (var h = new Harness()) {
            clearInvocations(h.jdbc);
            assertEquals(List.of(), h.storage.readKeys(List.of()));
            assertThrows(IllegalArgumentException.class, () -> h.storage.readKeys(List.of("000001.SZ", "000001.SZ")));
            assertThrows(IllegalArgumentException.class, () -> h.storage.readKeys(List.of("bad")));
            verifyNoInteractions(h.jdbc);
            assertEquals(1, h.storage.readKeys(List.of("T600018.SH", "000001.SZ")).size());
            var call = mockingDetails(h.jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().equals("query")).findFirst().orElseThrow();
            assertEquals("SELECT " + PROJECTION + " FROM stock_detail_info WHERE ts_code IN (?,?) ORDER BY ts_code LIMIT 3", call.getArgument(0));
            assertArrayEquals(new Object[]{"T600018.SH", "000001.SZ"}, (Object[]) call.getRawArguments()[2]);
            when(h.row.getObject("update_micros")).thenReturn(null);
            assertThrows(SQLException.class, () -> h.storage.readKeys(List.of("T600018.SH")));
        }
    }

    @Test void walOrDedupSchemaRejectionPrecedesAllRowReads() throws Exception {
        try (var h = new Harness()) {
            var wal = new HashMap<>(metadata(7)); wal.put("walEnabled", true);
            when(h.jdbc.queryForList(META, TABLE)).thenReturn(List.of(wal));
            assertEquals("Expected unpartitioned non-WAL static table", assertThrows(IllegalStateException.class, h.storage::snapshot).getMessage());
            assertTrue(mockingDetails(h.jdbc).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("query")));
        }
    }

    private static Map<String,Object> metadata(long id) {
        return Map.of("id", id, "directoryName", "generation-" + id, "partitionBy", "NONE", "walEnabled", false, "dedup", false);
    }
    private static final class Harness implements AutoCloseable {
        final MockedConstruction<JdbcTemplate> copies;
        final JdbcTemplate jdbc; final StockDetailInfoStorage storage; final ResultSet row = mock(ResultSet.class);
        @SuppressWarnings("unchecked") Harness() throws Exception {
            var original = mock(JdbcTemplate.class); when(original.getDataSource()).thenReturn(mock(DataSource.class));
            copies = mockConstruction(JdbcTemplate.class); storage = new StockDetailInfoStorage(original, TABLE);
            jdbc = copies.constructed().getFirst();
            List<Map<String,Object>> columns = StockDetailInfoDataset.DEFINITION.columns().stream()
                    .map(c -> Map.<String,Object>of("column", c.storageName(), "type", c.storageType().name(), "upsertKey", false)).toList();
            when(jdbc.queryForList("SELECT \"column\",\"type\",\"upsertKey\" FROM table_columns('stock_detail_info')")).thenReturn(columns);
            when(jdbc.queryForList(META, TABLE)).thenReturn(List.of(metadata(7)));
            when(row.getObject("update_micros")).thenReturn(-1L); when(row.getString("ts_code")).thenReturn("T600018.SH");
            when(row.getString("symbol")).thenReturn("600018"); when(row.getString("name")).thenReturn("legacy");
            when(row.getString("list_status")).thenReturn("L"); when(row.getString("list_date")).thenReturn("None");
            when(row.getString("delist_date")).thenReturn("");
            when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(row, 0)));
            when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(row, 0)));
        }
        @Override public void close() { copies.close(); }
    }
}
