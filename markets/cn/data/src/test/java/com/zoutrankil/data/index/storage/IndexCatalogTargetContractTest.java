package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import com.zoutrankil.data.index.domain.IndexCatalogState;
import com.zoutrankil.data.index.domain.IndexMembershipState;
import com.zoutrankil.data.index.domain.policy.IndexCatalogMerge;
import com.zoutrankil.data.index.mapper.IndexCatalogMapper;
import com.zoutrankil.data.index.mapper.IndexMembershipMapper;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import java.sql.ResultSet;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Catalog/membership storage contracts without opening a database connection. */
class IndexCatalogTargetContractTest {
    enum Family { CATALOG, MEMBERSHIP }
    @ParameterizedTest @EnumSource(Family.class)
    void physicalFactoriesAreFreshBoundedAndDoNotOpenConnections(Family family) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var ds=mock(DataSource.class);when(jdbc.getDataSource()).thenReturn(ds);
        Object a,b,stage1,stage2,publication;
        if(family==Family.CATALOG){
            var target=new QuestDbIndexCatalogTarget(jdbc,"index");
            a=target.open("owned_stage");b=target.open("owned_stage");stage1=target.newStaging();stage2=target.newStaging();
            publication=target.publicationTables();assertEquals("index",target.tableName());
        }else{
            var target=new QuestDbIndexMembershipTarget(jdbc,"index_member");
            a=target.open("owned_stage");b=target.open("owned_stage");stage1=target.newStaging();stage2=target.newStaging();
            publication=target.publicationTables();assertEquals("index_member",target.tableName());
        }
        assertNotSame(a,b);assertNotSame(jdbcOf(a),jdbcOf(b));assertSame(ds,jdbcOf(a).getDataSource());
        assertEquals(20,jdbcOf(a).getQueryTimeout());assertEquals(family==Family.CATALOG?5001:100001,jdbcOf(a).getMaxRows());
        assertNotSame(stage1,stage2);assertNotSame(jdbcOf(stage1),jdbcOf(stage2));assertEquals(20,jdbcOf(stage1).getQueryTimeout());
        assertNotSame(jdbc,jdbcOf(publication));assertEquals(20,jdbcOf(publication).getQueryTimeout());verifyNoInteractions(ds);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void tableOperationsRetainExactSqlAndIdentityFrame(Family family) {
        var jdbc=mock(JdbcTemplate.class);String table=family==Family.CATALOG?"index":"index_member";
        var target=family==Family.CATALOG?new QuestDbIndexCatalogTarget(jdbc,table):null;
        var member=family==Family.MEMBERSHIP?new QuestDbIndexMembershipTarget(jdbc,table):null;
        when(jdbc.queryForList("SELECT id FROM tables() WHERE table_name=?","owned_stage")).thenReturn(List.of(Map.of("id",7)));
        try(var identities=mockStatic(StaticTargetIdentity.class);var checks=mockStatic(QuestDbWriteChecks.class)){
            identities.when(()->StaticTargetIdentity.identify(jdbc,table,7L,"generation")).thenReturn("frozen-target");
            checks.when(()->QuestDbWriteChecks.walSettled(jdbc,"owned_stage")).thenReturn(true);
            if(target!=null){
                assertTrue(target.exists("owned_stage"));assertFalse(target.exists("missing"));assertTrue(target.walSettled("owned_stage"));
                assertEquals("frozen-target",target.identify(table,7,"generation"));target.rename("owned_stage",table);
            }else{
                assertTrue(member.exists("owned_stage"));assertFalse(member.exists("missing"));assertTrue(member.walSettled("owned_stage"));
                assertEquals("frozen-target",member.identify(table,7,"generation"));member.rename("owned_stage",table);
            }
            verify(jdbc).execute("RENAME TABLE \"owned_stage\" TO \""+table+"\"");
            identities.verify(()->StaticTargetIdentity.identify(jdbc,table,7L,"generation"));
        }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void preflightFailureStopsBeforeIdentityOrDataRead(Family family) {
        var external=mock(JdbcTemplate.class);when(external.getDataSource()).thenReturn(mock(DataSource.class));
        var failure=new IllegalStateException("schema drift");
        try(var copies=mockConstruction(JdbcTemplate.class);var checks=mockStatic(QuestDbWriteChecks.class)){
            Object table=family==Family.CATALOG?new QuestDbIndexCatalogTarget(external,"index").open("index"):
                    new QuestDbIndexMembershipTarget(external,"index_member").open("index_member");
            var jdbc=copies.constructed().getFirst();String name=family==Family.CATALOG?"index":"index_member";
            var definition=family==Family.CATALOG?IndexCatalogDataset.DEFINITION:IndexMembershipDataset.DEFINITION;
            checks.when(()->QuestDbWriteChecks.preflight(jdbc,name,definition)).thenThrow(failure);
            if(table instanceof IndexCatalogStorage catalog)assertSame(failure,assertThrows(IllegalStateException.class,catalog::snapshot));
            else assertSame(failure,assertThrows(IllegalStateException.class,((IndexMembershipStorage)table)::snapshot));
            verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
            verify(jdbc,never()).queryForList(anyString());
        }
    }

    @Test void catalogSnapshotRetainsProjectionNegativeMicrosecondAndRawNulls() throws Exception {
        var external=mock(JdbcTemplate.class);when(external.getDataSource()).thenReturn(mock(DataSource.class));
        try(var copies=mockConstruction(JdbcTemplate.class);var checks=mockStatic(QuestDbWriteChecks.class)){
            var storage=new IndexCatalogStorage(external,"index");var jdbc=copies.constructed().getFirst();
            when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?","index"))
                    .thenReturn(List.of(Map.of("id",7L,"directoryName","generation")));
            var values=new HashMap<String,Object>();values.put("index_code","000001");values.put("base_date","2000-01-01");
            values.put("publish_date","2001-01-01");values.put("import_micros",-1L);values.put("return_1m",-0.0);
            String sql="SELECT index_code,index_short_name,index_full_name,base_date,base_point,index_series,sample_count,latest_close,return_1m,asset_class,index_hotspot,currency,is_cooperation,has_tracking_product,compliance_status,index_category,publish_date,cast(import_time as long) AS import_micros FROM \"index\" ORDER BY index_code LIMIT 5001";
            when(jdbc.queryForList(sql)).thenReturn(List.of(values));
            var snapshot=storage.snapshot();assertEquals(Instant.ofEpochSecond(-1,999999000),snapshot.rows().getFirst().importTime());
            assertNull(snapshot.rows().getFirst().indexShortName());assertEquals(-0.0,snapshot.rows().getFirst().return1m());
            byte[] expected=JobDefinitionJson.mapper().writeValueAsBytes(snapshot.rows());
            assertEquals(expected.length,snapshot.bytes());assertEquals(sha(expected),snapshot.fingerprint());
            verify(jdbc,times(2)).queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?","index");
            when(jdbc.queryForList(sql)).thenReturn(List.of(values,values));
            assertEquals("Duplicate physical catalog code",assertThrows(IllegalStateException.class,storage::snapshot).getMessage());
        }
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"})
    void membershipSnapshotRetainsWalGuardQueryAndLegacyPhysicalStrings() throws Exception {
        var external=mock(JdbcTemplate.class);when(external.getDataSource()).thenReturn(mock(DataSource.class));
        try(var copies=mockConstruction(JdbcTemplate.class);var checks=mockStatic(QuestDbWriteChecks.class)){
            var storage=new IndexMembershipStorage(external,"index_member");var jdbc=copies.constructed().getFirst();
            when(jdbc.queryForList("SELECT id,directoryName FROM tables() WHERE table_name=?","index_member"))
                    .thenReturn(List.of(Map.of("id",7L,"directoryName","generation")));
            when(jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?","index_member"))
                    .thenReturn(List.of(Map.of("writerTxn",12L)));
            String sql="SELECT index_code,ts_code,cast(update_time AS long) AS observed_us,index_name,con_code,con_name,in_date,out_date,is_new,weight,level,l1_name,l2_name,l3_name FROM \"index_member\" ORDER BY index_code,ts_code,in_date LIMIT 100001";
            var rs=mock(ResultSet.class);when(rs.getObject("observed_us",Long.class)).thenReturn(-1L);
            for(var entry:Map.of("index_code","801011.SI","ts_code","T00018.SH","in_date","20200101","out_date","None","is_new","Y","level","L2").entrySet())
                when(rs.getString(entry.getKey())).thenReturn(entry.getValue());
            when(jdbc.query(eq(sql),any(RowMapper.class))).thenAnswer(i->List.of(((RowMapper)i.getArgument(1)).mapRow(rs,0)));
            var snapshot=storage.snapshot();assertEquals("None",snapshot.rows().getFirst().outDate());
            assertNull(snapshot.businessRows().getFirst().membershipEndDate());assertNull(snapshot.rows().getFirst().weight());
            assertEquals(Instant.ofEpochSecond(-1,999999000),snapshot.rows().getFirst().updateTime());
            byte[] bytes=JobDefinitionJson.mapper().writeValueAsBytes(snapshot.rows());assertEquals(sha(bytes),snapshot.fingerprint());
            when(jdbc.queryForList("SELECT writerTxn FROM wal_tables() WHERE name=?","index_member"))
                    .thenReturn(List.of(Map.of("writerTxn",12L)),List.of(Map.of("writerTxn",13L)));
            assertEquals("Membership changed while reading snapshot",assertThrows(IllegalStateException.class,storage::snapshot).getMessage());
        }
    }

    @Test void domainProjectionKeepsAllFieldsNullsAndSignedZeroWhileIgnoringOnlyImportTime() throws Exception {
        var mapper=new IndexCatalogMapper();var original=catalog(-0.0,Instant.EPOCH);
        var expected=new LinkedHashMap<String,Object>();expected.put("index_code","000001");expected.put("short_name",null);
        expected.put("full_name","full");expected.put("base_date",LocalDate.of(2000,1,1));expected.put("base_point",100.0);
        expected.put("series",null);expected.put("sample_count",0.0);expected.put("latest_close",null);expected.put("return_1m",-0.0);
        expected.put("asset_class",null);expected.put("hotspot",null);expected.put("currency","CNY");expected.put("cooperation",null);
        expected.put("tracking_product",null);expected.put("compliance_status",null);expected.put("category",null);
        expected.put("publish_date",LocalDate.of(2001,1,1));expected.put("import_time",Instant.EPOCH);
        assertEquals(expected,mapper.values(original).asMap());assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(mapper.values(original).asMap().keySet()));
        assertTrue(IndexCatalogMerge.sameBusinessValues(original,catalog(-0.0,Instant.ofEpochSecond(1))));
        assertFalse(IndexCatalogMerge.sameBusinessValues(original,catalog(+0.0,Instant.EPOCH)));
        var physical=mapper.toStorage(original);var state=new IndexCatalogState.Snapshot(new IndexCatalogState.Identity(7,"generation"),List.of(physical),"hash",123);
        assertEquals(List.of(original),state.businessRows());assertEquals(state,JobDefinitionJson.mapper().readValue(JobDefinitionJson.mapper().writeValueAsBytes(state),IndexCatalogState.Snapshot.class));
        assertEquals(List.of("identity","rows","fingerprint","bytes"),components(IndexCatalogState.Snapshot.class));
        assertEquals(List.of("before","rows","merge"),components(IndexCatalogState.Prepared.class));
        assertEquals(List.of("table","snapshot","receipt","batches"),components(IndexCatalogState.Verified.class));
        assertEquals(List.of("before","source","l2Code","rows","merge"),components(IndexMembershipState.Prepared.class));
        assertEquals(List.of("table","snapshot","batches","receipt"),components(IndexMembershipState.Verified.class));
    }

    private static IndexCatalogEntry catalog(double value,Instant at){return new IndexCatalogEntry("000001",null,"full",
            LocalDate.of(2000,1,1),100.0,null,0.0,null,value,null,null,"CNY",null,null,null,null,LocalDate.of(2001,1,1),at);}
    private static List<String> components(Class<?> type){return Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();}
    private static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    private static JdbcTemplate jdbcOf(Object value)throws Exception{var field=value.getClass().getDeclaredField("jdbc");field.setAccessible(true);return (JdbcTemplate)field.get(value);}
}
