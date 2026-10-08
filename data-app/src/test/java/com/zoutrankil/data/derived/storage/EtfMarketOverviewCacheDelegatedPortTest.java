package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.derived.application.DefaultEtfMarketOverviewPublicationSession;
import com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationSession;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.derived.domain.EtfMarketOverviewOwnerResult;
import com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationCodec;
import com.zoutrankil.data.derived.application.EtfMarketOverviewCacheOwnerGateway;
import com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures;

import com.zoutrankil.data.repository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.service.*;
import org.springframework.jdbc.core.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.zoutrankil.data.derived.application.EtfMarketOverviewCacheTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfMarketOverviewCacheDelegatedPortTest {
    @TempDir Path temp;
    private EtfMarketOverviewCachePublicationEnvelope envelope(boolean known,boolean cache)throws Exception{return EtfMarketOverviewCacheTestFixtures.envelope(temp.resolve("preview.json"),known,cache);}
    private VerifiedBatchExecutor.Submission submission(){return new VerifiedBatchExecutor.Submission(temp.resolve("ledger.sqlite"),"run1","slice1",4,UNIT);}
    private static final class ValuePort implements EtfMarketOverviewPublicationSession {
        List<EtfMarketOverviewDailyCache> cacheRows;
        List<MarketBarometerCacheCoverage> receipts;
        EtfMarketOverviewCachePublicationEnvelope bound;
        boolean changeDuringRead,metadataBroken;
        int reads;
        private final DefaultEtfMarketOverviewPublicationSession delegate;
        ValuePort(EtfMarketOverviewCacheOwnerGateway gateway,EtfMarketOverviewCachePublicationEnvelope expected){
            bound=expected;cacheRows=expected.cache()==null?List.of():List.of(expected.cache());receipts=expected.receipt()==null?List.of():List.of(expected.receipt());
            var target=new QuestDbEtfMarketOverviewPublicationTarget(mock(JdbcTemplate.class)) {
                @Override void requireJdbcTarget(){}
                @Override void verifySchemas(EtfMarketOverviewCachePublicationEnvelope expected){}
                @Override Map<String,JsonNode> readSnapshots(EtfMarketOverviewCachePublicationEnvelope expected){if(metadataBroken)throw new IllegalStateException("Source physical version changed");var node=JobDefinitionJson.mapper().createObjectNode().put("txn",changeDuringRead?++reads:1);return Map.of("snapshot",node);}
                @Override List<EtfMarketOverviewDailyCache> readCache(EtfMarketOverviewDailyCacheKey key){return cacheRows;}
                @Override List<MarketBarometerCacheCoverage> readReceipt(EtfMarketOverviewDailyCacheKey key){return receipts;}
            };
            delegate=new DefaultEtfMarketOverviewPublicationSession(gateway,gateway,target,expected);
        }
        public void bind(EtfMarketOverviewCachePublicationEnvelope row){bound=row;delegate.bind(row);}
        public void cancellationProbe(BooleanSupplier probe){delegate.cancellationProbe(probe);}
        public void reconciliationContext(Path path,String run){delegate.reconciliationContext(path,run);}
        public boolean unresolved(){return delegate.unresolved();}
        public Duration visibilityTimeout(){return delegate.visibilityTimeout();}
        public EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date)throws Exception{return delegate.preview(date);}
        public EtfMarketOverviewCachePublicationEnvelope requireExactEnvelope(EtfMarketOverviewDailyCache row)throws Exception{return delegate.requireExactEnvelope(row);}
        public void preflight()throws Exception{delegate.preflight();}
        public void submissionRecorded(VerifiedBatchExecutor.Submission value)throws Exception{delegate.submissionRecorded(value);}
        public void send(List<EtfMarketOverviewCachePublicationEnvelope> rows)throws Exception{delegate.send(rows);}
        public List<EtfMarketOverviewCachePublicationEnvelope> readback(List<EtfMarketOverviewDailyCacheKey> keys)throws Exception{return delegate.readback(keys);}
        public boolean walSettled()throws Exception{return delegate.walSettled();}
        public boolean uncertainSenderStopped()throws Exception{return delegate.uncertainSenderStopped();}
    }
    @Test void constructorAndBindPerformNoIoAndUnknownAnchorPreflightIsReadonly()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var jdbc=mock(JdbcTemplate.class);var port=new DefaultEtfMarketOverviewPublicationSession(gateway,gateway,new QuestDbEtfMarketOverviewPublicationTarget(jdbc),envelope(false,false));port.bind(envelope(false,false));verifyNoInteractions(gateway,jdbc);var checked=new ValuePort(gateway,envelope(false,false));checked.preflight();assertThrows(IllegalStateException.class,()->checked.send(List.of(envelope(false,false))));verifyNoInteractions(gateway);}
    @Test void exactCodecExcludesLegalTargetTxnAndPreviewNonceButKeepsAllBusinessValues()throws Exception{var original=envelope(true,true);var changed=new EtfMarketOverviewCachePublicationEnvelope(original.tradeDate(),original.sourceVersion(),original.cache(),original.receipt(),original.sources(),original.targets(),original.sourcesFingerprint(),"2".repeat(64),original.targetId(),original.sourceFingerprint(),original.sourceRows(),true,temp.resolve("other-preview.json"),"3".repeat(64),"different-path-evidence",original.previewResponse());assertTrue(EtfMarketOverviewPublicationCodec.CODEC.equivalent(original,changed));assertEquals(UNIT,EtfMarketOverviewPublicationCodec.fingerprint(changed));}
    @Test void exactCodecDistinguishesNullableDoubleAndRawSignedZero()throws Exception{var expected=envelope(true,true);var positive=new EtfMarketOverviewDailyCache(DAY,2,0.0,null,SHA);var negative=new EtfMarketOverviewDailyCache(DAY,2,-0.0,null,SHA);assertFalse(EtfMarketOverviewPublicationCodec.CODEC.equivalent(expected.withActual(positive,expected.receipt()),expected.withActual(negative,expected.receipt())));}
    @Test void sendRequiresDurableHookAndOneKnownUnit()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);var port=new ValuePort(gateway,expected);assertThrows(IllegalStateException.class,()->port.send(List.of(expected)));port.submissionRecorded(submission());assertThrows(IllegalStateException.class,()->port.send(List.of(expected,expected)));verify(gateway,never()).publish(any(),any(),any());}
    @Test void knownSuccessfulDelegationCallsOriginalGatewayExactlyOnce()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);when(gateway.publish(any(),any(),any())).thenReturn(new EtfMarketOverviewOwnerResult(temp.resolve("request.json"),temp.resolve("response.json"),temp.resolve("intent.json"),SHA,true,1,0,"VERIFIED_READTHROUGH"));var port=new ValuePort(gateway,expected);port.submissionRecorded(submission());port.send(List.of(expected));assertFalse(port.unresolved());assertTrue(port.uncertainSenderStopped());assertEquals(1,port.readback(List.of(expected.key())).size());assertThrows(IllegalStateException.class,()->port.send(List.of(expected)));verify(gateway,times(1)).publish(any(),any(),any());}
    @Test void unknownAckStaysInDoubtUntilExplicitRunScopedStoppedProof()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);when(gateway.publish(any(),any(),any())).thenThrow(new IOException("unknown after owner"));var port=new ValuePort(gateway,expected);port.submissionRecorded(submission());assertThrows(IOException.class,()->port.send(List.of(expected)));assertTrue(port.unresolved());assertFalse(port.uncertainSenderStopped());assertThrows(IllegalStateException.class,()->port.send(List.of(expected)));when(gateway.writerStopped(temp.resolve("ledger.sqlite").toAbsolutePath(),"run1")).thenReturn(true);port.reconciliationContext(temp.resolve("ledger.sqlite"),"run1");port.bind(expected);assertTrue(port.uncertainSenderStopped());assertTrue(port.unresolved());verify(gateway,times(1)).publish(any(),any(),any());}
    @Test void failedTerminationProofDoesNotBecomeAcknowledged()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);when(gateway.publish(any(),any(),any())).thenReturn(new EtfMarketOverviewOwnerResult(null,null,null,SHA,false,0,1,"VERIFIED_READTHROUGH"));var port=new ValuePort(gateway,expected);port.submissionRecorded(submission());assertThrows(IOException.class,()->port.send(List.of(expected)));assertTrue(port.unresolved());assertFalse(port.uncertainSenderStopped());}
    @Test void cancellationBeforeBoundaryNeverInvokesOwner()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);var port=new ValuePort(gateway,expected);port.submissionRecorded(submission());port.cancellationProbe(()->true);assertThrows(IllegalStateException.class,()->port.send(List.of(expected)));verify(gateway,never()).publish(any(),any(),any());}
    @Test void readbackRequiresReceiptAndFullRawBitsCacheValues()throws Exception{var port=new ValuePort(mock(EtfMarketOverviewCacheOwnerGateway.class),envelope(true,true));assertEquals(1,port.readback(List.of(port.bound.key())).size());port.receipts=List.of();assertTrue(port.readback(List.of(port.bound.key())).isEmpty());port.receipts=List.of(port.bound.receipt());port.cacheRows=List.of(new EtfMarketOverviewDailyCache(DAY,2,Math.nextUp(123.5),0.0247,SHA));assertTrue(port.readback(List.of(port.bound.key())).isEmpty());}
    @Test void missingCacheAndAlteredCountOrReceiptDigestAreRejected()throws Exception{var expected=envelope(true,true);var port=new ValuePort(mock(EtfMarketOverviewCacheOwnerGateway.class),expected);port.cacheRows=List.of();assertTrue(port.readback(List.of(expected.key())).isEmpty());port.cacheRows=List.of(new EtfMarketOverviewDailyCache(DAY,3,123.5,0.0247,SHA));assertTrue(port.readback(List.of(expected.key())).isEmpty());port.cacheRows=List.of(expected.cache());port.receipts=List.of(new MarketBarometerCacheCoverage(DAY,"etf_market_overview_daily",SHA,1,"f".repeat(64)));assertTrue(port.readback(List.of(expected.key())).isEmpty());}
    @Test void knownEmptyRequiresExactZeroReceiptAndActualCacheAbsence()throws Exception{var expected=envelope(true,false);var port=new ValuePort(mock(EtfMarketOverviewCacheOwnerGateway.class),expected);assertEquals(1,port.readback(List.of(expected.key())).size());port.cacheRows=List.of(new EtfMarketOverviewDailyCache(DAY,0,null,null,SHA));assertTrue(port.readback(List.of(expected.key())).isEmpty());}
    @Test void duplicateCompleteKeysAndPhysicalRaceCannotReturnVerifiedRows()throws Exception{var expected=envelope(true,true);var port=new ValuePort(mock(EtfMarketOverviewCacheOwnerGateway.class),expected);port.cacheRows=List.of(expected.cache(),expected.cache());assertThrows(IllegalStateException.class,()->port.readback(List.of(expected.key())));port.cacheRows=List.of(expected.cache());port.changeDuringRead=true;assertThrows(IllegalStateException.class,()->port.readback(List.of(expected.key())));}
    @Test void mismatchedRequestedCompleteKeyRejectedBeforeIo()throws Exception{var expected=envelope(true,true);var port=new ValuePort(mock(EtfMarketOverviewCacheOwnerGateway.class),expected);assertThrows(IllegalArgumentException.class,()->port.readback(List.of(new EtfMarketOverviewDailyCacheKey(DAY,"b".repeat(64)))));}
    @Test void preparedCallerRequiresCurrentGenerationAndAllFiveValues()throws Exception{var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);var expected=envelope(true,true);when(gateway.preview(DAY)).thenReturn(expected);var port=new ValuePort(gateway,expected);assertSame(expected,port.requireExactEnvelope(expected.cache()));assertThrows(IllegalArgumentException.class,()->port.requireExactEnvelope(new EtfMarketOverviewDailyCache(DAY,2,Math.nextUp(123.5),0.0247,SHA)));assertThrows(IllegalArgumentException.class,()->port.requireExactEnvelope(new EtfMarketOverviewDailyCache(DAY,2,123.5,0.0247,"b".repeat(64))));verify(gateway,never()).publish(any(),any(),any());}
    @Test void metadataAcceptsPythonIntNodesAndPgLongsWithoutComparingTableTxnToWal()throws Exception{var expected=envelope(true,true);var physical=physical(expected);physical.get("etf_share").put("table_txn",9L);var json=expected.previewResponse();((ObjectNode)json.path("source_snapshots").path("etf_share").path("physical")).put("table_txn",9);var adjusted=new EtfMarketOverviewCacheTestFixtures.TestGateway(config(temp)).parsePreview(json,DAY,json.path("invocation_id").asText(),expected.previewPath(),SHA);var port=metadataPort(adjusted,physical);port.preflight();assertTrue(port.walSettled());}
    @Test void sourceTxnSeqOrIdentityChangeIsRejectedEvenWhenSourceGenerationMatches()throws Exception{for(String field:List.of("id","table_txn","sequencerTxn","writerTxn")){var expected=envelope(true,true);var physical=physical(expected);physical.get("etf_share").put(field,3L);if(field.equals("writerTxn")||field.equals("sequencerTxn")){physical.get("etf_share").put("writerTxn",3L);physical.get("etf_share").put("sequencerTxn",3L);}var port=metadataPort(expected,physical);assertThrows(IllegalStateException.class,port::preflight);}}
    @Test void targetCanAdvanceOwnSettledTxnButCannotRebuildOrRegress()throws Exception{var expected=envelope(true,true);var physical=physical(expected);var cache=physical.get(EtfMarketOverviewCachePublicationEnvelope.CACHE);cache.put("table_txn",4L);cache.put("sequencerTxn",3L);cache.put("writerTxn",3L);var port=metadataPort(expected,physical);port.preflight();cache.put("id",444L);assertThrows(IllegalStateException.class,port::preflight);cache.put("id",4L);cache.put("table_txn",1L);assertThrows(IllegalStateException.class,port::preflight);}
    @Test void pendingBufferedOrSuspendedSourceAndTargetFailBeforeOwner()throws Exception{for(String table:List.of("etf_daily",EtfMarketOverviewCachePublicationEnvelope.CACHE))for(String field:List.of("wal_pending_row_count","bufferedTxnSize","suspended","table_suspended")){var expected=envelope(true,true);var physical=physical(expected);physical.get(table).put(field,field.contains("suspended")?true:1L);var port=metadataPort(expected,physical);assertThrows(IOException.class,port::preflight);}}
    @Test void nullTargetMetadataNeedsIndependentCountZeroAndRemainsNullBeforeFirstWrite()throws Exception{
        var expected=nullTargetEnvelope();var physical=physical(expected);physical.get(EtfMarketOverviewCachePublicationEnvelope.CACHE).put("actual_count",0L);var port=metadataPort(expected,physical);port.preflight();assertTrue(port.walSettled());assertTrue(expected.targets().get(EtfMarketOverviewCachePublicationEnvelope.CACHE).path("physical").path("table_txn").isNull());
    }
    @Test void nullTargetCountNonzeroOrChangedMetadataRejectsBeforePublication()throws Exception{
        var expected=nullTargetEnvelope();var physical=physical(expected);var target=physical.get(EtfMarketOverviewCachePublicationEnvelope.CACHE);target.put("actual_count",1L);assertThrows(IllegalStateException.class,()->metadataPort(expected,physical).preflight());target.put("actual_count",0L);target.put("__change_after_count",true);assertThrows(IllegalStateException.class,()->metadataPort(expected,physical).preflight());
    }
    @Test void nullTargetCanBecomeNonNullButInitializedTargetCannotReturnToNull()throws Exception{
        var expected=nullTargetEnvelope();var physical=physical(expected);var target=physical.get(EtfMarketOverviewCachePublicationEnvelope.CACHE);target.put("table_txn",1L);target.put("table_row_count",1L);target.put("writerTxn",1L);target.put("sequencerTxn",1L);metadataPort(expected,physical).preflight();
        var initialized=envelope(true,true);var regressed=physical(initialized);var bad=regressed.get(EtfMarketOverviewCachePublicationEnvelope.CACHE);bad.put("table_txn",null);bad.put("table_row_count",null);bad.put("writerTxn",0L);bad.put("sequencerTxn",0L);bad.put("actual_count",0L);assertThrows(IllegalStateException.class,()->metadataPort(initialized,regressed).preflight());
    }
    private EtfMarketOverviewCachePublicationEnvelope nullTargetEnvelope()throws Exception{
        var expected=envelope(true,true);var json=expected.previewResponse();String table=EtfMarketOverviewCachePublicationEnvelope.CACHE;var row=(ObjectNode)json.path("target_snapshots").path(table);((ObjectNode)row.get("physical")).putNull("table_txn").putNull("table_row_count");((ObjectNode)row.get("wal")).put("sequencerTxn",0).put("writerTxn",0);((ObjectNode)json.get("target_actual_row_counts")).put(table,0);return new EtfMarketOverviewCacheTestFixtures.TestGateway(config(temp)).parsePreview(json,DAY,json.path("invocation_id").asText(),expected.previewPath(),SHA);
    }
    private static Map<String,Map<String,Object>> physical(EtfMarketOverviewCachePublicationEnvelope expected){var rows=new LinkedHashMap<String,Map<String,Object>>();var all=new LinkedHashMap<String,JsonNode>(expected.sources());all.putAll(expected.targets());for(var entry:all.entrySet()){var values=new HashMap<String,Object>();for(String group:List.of("physical","wal"))entry.getValue().path(group).fields().forEachRemaining(field->{JsonNode value=field.getValue();if(value.isIntegralNumber())values.put(field.getKey(),value.longValue());else if(value.isBoolean())values.put(field.getKey(),value.booleanValue());else if(value.isTextual())values.put(field.getKey(),value.textValue());});rows.put(entry.getKey(),values);}return rows;}
    @SuppressWarnings({"rawtypes","unchecked"}) private EtfMarketOverviewPublicationSession metadataPort(EtfMarketOverviewCachePublicationEnvelope expected,Map<String,Map<String,Object>> rows)throws Exception{
        JdbcTemplate jdbc=mock(JdbcTemplate.class);Connection connection=mock(Connection.class);var sql=new AtomicReference<String>();when(connection.prepareStatement(anyString())).thenAnswer(call->{sql.set(call.getArgument(0));return mock(PreparedStatement.class);});
        when(jdbc.query(any(PreparedStatementCreator.class),any(ResultSetExtractor.class))).thenAnswer(call->{PreparedStatementCreator create=call.getArgument(0);create.createPreparedStatement(connection);String table=rows.keySet().stream().filter(name->sql.get().endsWith("='"+name+"'")||sql.get().equals("SELECT count() AS actual_count FROM "+name)).findFirst().orElseThrow();var values=rows.get(table);boolean countQuery=sql.get().startsWith("SELECT count()");ResultSet rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,false);var nil=new AtomicBoolean();when(rs.getLong(anyString())).thenAnswer(arg->{Object value=values.get(arg.getArgument(0));nil.set(value==null);if(countQuery&&Boolean.TRUE.equals(values.get("__change_after_count"))){values.put("table_txn",1L);values.put("table_row_count",0L);values.put("writerTxn",1L);values.put("sequencerTxn",1L);}return value==null?0L:((Number)value).longValue();});when(rs.getBoolean(anyString())).thenAnswer(arg->{Object value=values.get(arg.getArgument(0));nil.set(value==null);return value!=null&&(Boolean)value;});when(rs.getString(anyString())).thenAnswer(arg->{Object value=values.get(arg.getArgument(0));nil.set(value==null);return (String)value;});when(rs.wasNull()).thenAnswer(arg->nil.get());return ((ResultSetExtractor)call.getArgument(1)).extractData(rs);});
        var gateway=mock(EtfMarketOverviewCacheOwnerGateway.class);
        var target=new QuestDbEtfMarketOverviewPublicationTarget(jdbc){@Override void requireJdbcTarget(){}@Override void verifySchemas(EtfMarketOverviewCachePublicationEnvelope expected){}};
        return new DefaultEtfMarketOverviewPublicationSession(gateway,gateway,target,expected);
    }
}
