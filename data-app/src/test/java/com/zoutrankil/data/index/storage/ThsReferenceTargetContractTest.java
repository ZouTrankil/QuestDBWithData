package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.index.domain.ThsIndexRows;
import com.zoutrankil.data.index.domain.ThsIndexState;
import com.zoutrankil.data.index.domain.ThsMemberState;
import com.zoutrankil.data.index.mapper.ThsIndexMapper;
import com.zoutrankil.data.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ThsReferenceTargetContractTest {
    private static final Instant TIME=Instant.parse("2020-01-03T01:02:03.123456Z");
    @Test void targetConstructionHasNoIoAndEveryStagingPublicationSessionIsFresh()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var ds=mock(DataSource.class);
        var index=new QuestDbThsIndexTarget("ths_index",jdbc);var member=new QuestDbThsMemberTarget("ths_member",jdbc);
        assertEquals("ths_index",index.tableName());assertEquals("ths_member",member.tableName());verifyNoInteractions(jdbc,ds);
        when(jdbc.getDataSource()).thenReturn(ds);
        var a=index.newStaging();var b=index.newStaging();var c=member.newStaging();var d=member.newStaging();
        assertNotSame(a,b);assertNotSame(c,d);assertEquals(20,jdbc(a).getQueryTimeout());assertEquals(120,jdbc(c).getQueryTimeout());
        assertNotSame(jdbc(a),jdbc(b));assertNotSame(jdbc(c),jdbc(d));
        var ip=(QuestDbThsIndexTables)index.publicationTables();var mp=(QuestDbThsMemberTables)member.publicationTables();
        assertNotSame(ip,index.publicationTables());assertNotSame(mp,member.publicationTables());assertEquals(20,ip.jdbc.getQueryTimeout());assertEquals(120,mp.jdbc.getQueryTimeout());
        verifyNoInteractions(ds);
    }
    @Test void metadataIdentityAndRenameStatementsAreBoundToTheOriginalRawJdbc() {
        var jdbc=mock(JdbcTemplate.class);var index=new QuestDbThsIndexTarget("ths_index",jdbc);var member=new QuestDbThsMemberTarget("ths_member",jdbc);
        try(var identity=mockStatic(StaticTargetIdentity.class)) {
            identity.when(()->StaticTargetIdentity.identify(jdbc,"ths_index",7L,"g7")).thenReturn("index-id");
            identity.when(()->StaticTargetIdentity.identify(jdbc,"ths_member",8L,"g8")).thenReturn("member-id");
            assertEquals("index-id",index.identify("ths_index",7,"g7"));assertEquals("member-id",member.identify("ths_member",8,"g8"));
        }
        index.rename("old_index","new_index");member.rename("old_member","new_member");
        verify(jdbc).execute("RENAME TABLE \"old_index\" TO \"new_index\"");verify(jdbc).execute("RENAME TABLE \"old_member\" TO \"new_member\"");
    }
    @Test void optionalSnapshotsCheckExistenceThenWalThenOpenTheCorrectScope()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var calls=new ArrayList<String>();
        var index=new QuestDbThsIndexTarget("ths_index",jdbc);var member=new QuestDbThsMemberTarget("ths_member",jdbc);
        try(var wal=mockStatic(QuestDbWriteChecks.class);var indexes=mockConstruction(ThsIndexStorage.class,(storage,ctx)->when(storage.snapshot()).thenAnswer(i->{calls.add("index-read");return null;}));var members=mockConstruction(ThsMemberBoardStorage.class,(storage,ctx)->when(storage.snapshot("885001.TI")).thenAnswer(i->{calls.add("member-read");return null;}))) {
            assertNull(index.snapshotIfPresent("absent","unsettled"));assertNull(member.snapshotIfPresent("absent","885001.TI"));wal.verifyNoInteractions();assertTrue(indexes.constructed().isEmpty());assertTrue(members.constructed().isEmpty());
            when(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?","index_stage")).thenAnswer(i->{calls.add("index-exists");return List.of(Map.of("id",1));});
            when(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?","member_stage")).thenAnswer(i->{calls.add("member-exists");return List.of(Map.of("id",2));});
            wal.when(()->QuestDbWriteChecks.walSettled(jdbc,"index_stage")).thenAnswer(i->{calls.add("index-wal");return true;});wal.when(()->QuestDbWriteChecks.walSettled(jdbc,"member_stage")).thenAnswer(i->{calls.add("member-wal");return true;});
            index.snapshotIfPresent("index_stage","unsettled");member.snapshotIfPresent("member_stage","885001.TI");
            assertEquals(List.of("index-exists","index-wal","index-read","member-exists","member-wal","member-read"),calls);
        }
    }
    @Test void completeIndexObservationRetainsAbsentKeysAndOldObservationClocks()throws Exception {
        var old=IntStream.range(0,50).mapToObj(i->new ThsIndex(String.format(Locale.ROOT,"%06d.TI",i),"name",1,"A",null,"N",TIME)).toList();
        var rows=old.stream().map(new ThsIndexMapper()::toStorage).toList();var before=new ThsIndexState.Snapshot(new ThsIndexState.Identity(1,"g1"),rows,"before",1);
        var source=old.subList(0,49).stream().map(r->new ThsIndex(r.tsCode(),r.name(),r.memberCount(),r.exchange(),r.listingDate(),r.indexType(),TIME.plusSeconds(1))).toList();
        var jdbc=mock(JdbcTemplate.class);var target=new QuestDbThsIndexTarget("ths_index",jdbc);
        var prepared=target.prepare(before,source,ThsIndexState.Scope.all());assertEquals(50,prepared.rows().size());assertEquals(0,prepared.merge().removed());assertEquals(1,prepared.merge().retainedAbsent());assertFalse(prepared.merge().requiresWrite());assertEquals(rows,prepared.rows());
        assertThrows(IllegalStateException.class,()->target.prepare(before,source.subList(0,48),ThsIndexState.Scope.all()));
        var bounded=target.preparePrepared(before,List.of(old.getFirst()));assertEquals(50,bounded.rows().size());assertEquals(49,bounded.merge().retainedAbsent());assertNull(bounded.scope());verifyNoInteractions(jdbc);
    }
    @Test void pureIndexRowMappingKeepsDatesNullsMicrosecondsAndDeclaredFieldOrder()throws Exception {
        var mapper=new ThsIndexMapper();var row=new ThsIndexRow("885001.TI",null,null,"A","20100101","N",TIME);
        var expected=new ThsIndex("885001.TI",null,null,"A",LocalDate.of(2010,1,1),"N",TIME);
        assertEquals(expected,ThsIndexRows.fromStorage(row));assertEquals(expected,mapper.fromStorage(row));assertEquals(row,mapper.toStorage(expected));
        assertNull(ThsIndexRows.fromStorage(new ThsIndexRow("885001.TI",null,null,null,"",null,TIME)).listingDate());
        assertThrows(java.time.format.DateTimeParseException.class,()->ThsIndexRows.fromStorage(new ThsIndexRow("885001.TI",null,null,null,"2020-01-03",null,TIME)));
        assertThrows(IllegalArgumentException.class,()->ThsIndexRows.fromStorage(new ThsIndexRow("885001.TI",null,null,null,null,null,TIME.plusNanos(1))));
        var snapshot=new ThsIndexState.Snapshot(new ThsIndexState.Identity(7,"g7"),List.of(row),"hash",42);
        assertEquals(List.of("identity","rows","fingerprint","bytes"),fields(JobDefinitionJson.mapper().valueToTree(snapshot)));
        assertEquals(List.of("code","exchange","type"),fields(JobDefinitionJson.mapper().valueToTree(ThsIndexState.Scope.all())));
        assertThrows(IllegalArgumentException.class,()->new ThsIndexState.Scope("885001.TI","A",null));
    }
    @Test void memberFingerprintKeepsRawOtherHashFollowedByTheUnframedBoardJson()throws Exception {
        var rows=new ArrayList<ThsMemberRow>();rows.add(new ThsMemberRow("885001.TI","000001.SZ","name",null,null,null,null,TIME));
        var snapshot=new ThsMemberState.Snapshot(new ThsMemberState.Identity(1,"g1",7),"885001.TI",rows,3,"other-hash");rows.clear();assertEquals(4,snapshot.totalRows());assertEquals(1,snapshot.boardRows().size());
        String json="[{\"tsCode\":\"885001.TI\",\"conCode\":\"000001.SZ\",\"conName\":\"name\",\"weight\":null,\"inDate\":null,\"outDate\":null,\"isNew\":null,\"updateTime\":\"2020-01-03T01:02:03.123456Z\"}]";
        String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("other-hash"+json).getBytes(StandardCharsets.UTF_8)));assertEquals(expected,snapshot.contentFingerprint());
        assertEquals(List.of("identity","board","boardRows","otherRows","otherFingerprint"),fields(JobDefinitionJson.mapper().valueToTree(snapshot)));
        assertThrows(UnsupportedOperationException.class,()->snapshot.boardRows().clear());
    }
    @Test void indexPhysicalSnapshotRetainsExactSqlAndNegativeMicrosecondDecoding()throws Exception {
        var raw=mock(JdbcTemplate.class);var ds=mock(DataSource.class);when(raw.getDataSource()).thenReturn(ds);
        try(var clones=mockConstruction(JdbcTemplate.class);var checks=mockStatic(QuestDbWriteChecks.class)) {
            var storage=new ThsIndexStorage(raw,"ths_index");var jdbc=clones.constructed().getFirst();
            when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?","ths_index")).thenReturn(List.of(Map.of("id",7,"directoryName","g7")));
            String sql="SELECT ts_code,name,\"count\",exchange,list_date,\"type\",cast(update_time as long) AS update_micros FROM \"ths_index\" ORDER BY ts_code LIMIT 5001";
            var values=new LinkedHashMap<String,Object>();values.put("ts_code","885001.TI");values.put("name",null);values.put("count",null);values.put("exchange",null);values.put("list_date",null);values.put("type",null);values.put("update_micros",-1L);
            when(jdbc.queryForList(sql)).thenReturn(List.of(values));var snapshot=storage.snapshot();assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"),snapshot.rows().getFirst().updateTime());
            assertEquals(FileEvidenceStore.sha256(JobDefinitionJson.mapper().writeValueAsBytes(snapshot.rows())),snapshot.fingerprint());
            verify(jdbc).queryForList(sql);verify(jdbc,times(2)).queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?","ths_index");verify(jdbc).setQueryTimeout(20);verify(jdbc).setMaxRows(5001);
        }
        verifyNoInteractions(ds);
    }
    private static List<String> fields(com.fasterxml.jackson.databind.JsonNode node){var result=new ArrayList<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    private static JdbcTemplate jdbc(Object value)throws Exception{var f=value.getClass().getDeclaredField("jdbc");f.setAccessible(true);return (JdbcTemplate)f.get(value);}
}
