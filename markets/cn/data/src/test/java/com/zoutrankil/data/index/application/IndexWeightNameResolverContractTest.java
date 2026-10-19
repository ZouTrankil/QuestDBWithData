package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.table.StockDetailInfoRow;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.stock.domain.StockDetailState.Identity;
import com.zoutrankil.data.stock.port.StockDetailNameReadPort;
import com.zoutrankil.data.stock.storage.QuestDbStockDetailNameReadPort;
import com.zoutrankil.data.stock.storage.StockDetailInfoStorage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexWeightNameResolverContractTest {
    private static final String ID="static-v2-"+"a".repeat(64),OTHER="static-v2-"+"b".repeat(64);
    private static final Identity GENERATION=new Identity(7,"g7");
    private final StockDetailNameReadPort reference=mock(StockDetailNameReadPort.class);
    private final StockDetailNameReadPort.Session session=mock(StockDetailNameReadPort.Session.class);
    private final IndexWeightNameResolver resolver=new IndexWeightNameResolver(reference);
    private void prepare() {
        when(reference.openSession()).thenReturn(session);when(session.preflight()).thenReturn(GENERATION);
        when(reference.identify(GENERATION)).thenReturn(ID);when(reference.targetId()).thenReturn(ID);
        when(session.readKeys(anyList())).thenReturn(List.of());
    }
    @Test void invalidFrozenIdAndResponseBudgetFailBeforeOpeningAReadSession() {
        assertEquals("Frozen D002 stock_detail_info target identity required",assertThrows(IllegalArgumentException.class,()->resolver.resolve("bad",null)).getMessage());
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(ID,null));
        assertThrows(IllegalArgumentException.class,()->resolver.resolve(ID,new HashSet<>(codes(4001))));
        verifyNoInteractions(reference,session);
    }
    @Test void allCodesAreSortedAndReadIn250CodeBatchesFromOneSession() throws Exception {
        prepare();var input=new LinkedHashSet<>(codes(501).reversed());
        resolver.resolve(ID,input);
        var order=inOrder(reference,session);
        order.verify(reference).openSession();order.verify(session).preflight();order.verify(reference).identify(GENERATION);
        for(int offset=0;offset<501;offset+=250) {
            order.verify(reference).targetId();order.verify(session).readKeys(codes(501).subList(offset,Math.min(501,offset+250)));
        }
        order.verify(session).preflight();order.verify(reference).identify(GENERATION);order.verifyNoMoreInteractions();
    }
    @Test void mismatchedInitialGenerationFailsBeforeAnyBoundedRead() {
        prepare();when(reference.identify(GENERATION)).thenReturn(OTHER);
        assertEquals("D021 name enrichment D002 target differs from frozen identity",assertThrows(IllegalStateException.class,()->resolver.resolve(ID,Set.of("000001.SZ"))).getMessage());
        verify(reference,never()).targetId();verify(session,never()).readKeys(anyList());
    }
    @Test void generationChangeBetweenBatchesStopsBeforeTheNextRead() {
        prepare();when(reference.targetId()).thenReturn(ID,OTHER);
        assertEquals("D002 target changed between bounded name-reference reads",assertThrows(IllegalStateException.class,()->resolver.resolve(ID,new HashSet<>(codes(251)))).getMessage());
        verify(session).readKeys(codes(251).subList(0,250));verify(session,times(1)).preflight();
        verify(session,times(1)).readKeys(anyList());
    }
    @Test void finalGenerationMismatchShortCircuitsItsSecondIdentityLookup() {
        prepare();when(session.preflight()).thenReturn(GENERATION,new Identity(8,"g8"));
        assertEquals("D002 stock_detail_info changed during D021 name enrichment",assertThrows(IllegalStateException.class,()->resolver.resolve(ID,Set.of("000001.SZ"))).getMessage());
        verify(reference,times(1)).identify(any());
    }
    @Test void unchangedMetadataStillRequiresTheFinalPhysicalTargetIdentity() {
        prepare();when(reference.identify(GENERATION)).thenReturn(ID,OTHER);
        assertThrows(IllegalStateException.class,()->resolver.resolve(ID,Set.of("000001.SZ")));
        verify(reference,times(2)).identify(GENERATION);
    }
    @Test void nullNamesAndStatusesRemainInTheCanonicalFingerprintAndImmutableResult() throws Exception {
        prepare();when(session.readKeys(List.of("000001.SZ","000002.SZ"))).thenReturn(List.of(row("000002.SZ","Beta","D"),row("000001.SZ",null,null)));
        var result=resolver.resolve(ID,Set.of("000002.SZ","000001.SZ"));
        String canonical="[{\"list_status\":null,\"name\":null,\"ts_code\":\"000001.SZ\"},{\"list_status\":\"D\",\"name\":\"Beta\",\"ts_code\":\"000002.SZ\"}]";
        assertEquals(FileEvidenceStore.sha256(canonical.getBytes(StandardCharsets.UTF_8)),result.fingerprint());
        assertEquals(List.of("000001.SZ","000002.SZ"),new ArrayList<>(result.names().keySet()));
        assertTrue(result.names().containsKey("000001.SZ"));assertNull(result.names().get("000001.SZ"));
        assertThrows(UnsupportedOperationException.class,()->result.names().put("x","y"));
        assertThrows(UnsupportedOperationException.class,()->result.referenceRows().getFirst().put("name","other"));
        assertThrows(UnsupportedOperationException.class,()->result.referenceRows().clear());
    }
    @Test void duplicateNullNameAndUnexpectedKeysRemainRejected() {
        prepare();when(session.readKeys(anyList())).thenReturn(List.of(row("000001.SZ",null,null),row("000001.SZ",null,null)));
        assertEquals("D021 name reference returned an unexpected or duplicate stock key",assertThrows(IllegalStateException.class,()->resolver.resolve(ID,Set.of("000001.SZ"))).getMessage());
        when(session.readKeys(anyList())).thenReturn(List.of(row("000002.SZ","bad","L")));
        assertThrows(IllegalStateException.class,()->resolver.resolve(ID,Set.of("000001.SZ")));
    }
    @Test void emptyInputStillVerifiesBeforeAndAfterAndHashesTheEmptyArray() throws Exception {
        prepare();var result=resolver.resolve(ID,Set.of());
        assertEquals(FileEvidenceStore.sha256("[]".getBytes(StandardCharsets.UTF_8)),result.fingerprint());
        assertTrue(result.names().isEmpty());verify(session,times(2)).preflight();verify(reference,times(2)).identify(GENERATION);
        verify(reference,never()).targetId();verify(session,never()).readKeys(anyList());
    }
    @Test void referenceAdapterCreatesFreshD002SessionsAndKeepsEachBoundedReadOnItsSession() {
        var jdbc=mock(JdbcTemplate.class);var calls=new ArrayList<String>();
        var adapter=new QuestDbStockDetailNameReadPort(jdbc,"stock_detail_info");verifyNoInteractions(jdbc);
        try(var storages=mockConstruction(StockDetailInfoStorage.class,(storage,context)->{
            assertEquals(List.of(jdbc,"stock_detail_info"),context.arguments());
            when(storage.preflight()).thenReturn(GENERATION);
            when(storage.readKeys(List.of("000001.SZ"))).thenReturn(List.of(row("000001.SZ","A","L")));
        });var identity=mockStatic(com.zoutrankil.data.repository.StaticTargetIdentity.class)) {
            identity.when(()->com.zoutrankil.data.repository.StaticTargetIdentity.identify(jdbc,"stock_detail_info",7L,"g7")).thenReturn(ID);
            var session=adapter.openSession();assertEquals(GENERATION,session.preflight());
            assertEquals(ID,adapter.targetId());assertEquals(2,storages.constructed().size());
            assertEquals("A",session.readKeys(List.of("000001.SZ")).getFirst().name());
            assertEquals(ID,adapter.identify(GENERATION));
            verify(storages.constructed().getFirst()).readKeys(List.of("000001.SZ"));
            verify(storages.constructed().get(1)).preflight();verify(storages.constructed().get(1),never()).readKeys(anyList());
        }
    }
    private static List<String> codes(int count){return IntStream.range(0,count).mapToObj(i->String.format(Locale.ROOT,"%06d.SZ",i)).toList();}
    private static StockDetailInfoRow row(String code,String name,String status){return new StockDetailInfoRow(code,null,null,name,null,null,status,null,null,null,null,null,null,null,null,null,null,null);}
}
