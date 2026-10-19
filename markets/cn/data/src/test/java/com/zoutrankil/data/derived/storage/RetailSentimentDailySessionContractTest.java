package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.domain.derived.RetailSentimentDailySnapshot;
import com.zoutrankil.data.domain.derived.RetailSentimentDaily;
import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.derived.application.RetailSentimentDailyMaterializeAdapter;
import com.zoutrankil.data.derived.domain.RetailSentimentDailyFullSourceScope;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.QuestDbBoundedReader;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Native refresh protocol contracts with mocked physical SQL; no process or database is started. */
class RetailSentimentDailySessionContractTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 17);
    private static final String TARGET = "questdb-" + "a".repeat(64);
    private static final RetailSentimentDaily ROW = new RetailSentimentDaily(DAY, 0.5, 1.2, 10.0, -2.0, 0.6, 2L, 1L, 0.2, 1L, 3L, 51.0, -1.0);

    @Test void configuredFactoryHasNoIoAndCreatesIndependentBindings() {
        var jdbc = mock(JdbcTemplate.class);
        var properties = new QuestDbProperties();
        var target = new QuestDbRetailSentimentDailyTarget(jdbc, properties, false, "  " + TARGET + "  ");
        var first = target.newSession();
        var second = target.newSession();
        assertNotSame(first, second);
        first.bind(DAY, DAY, snapshot(20));
        first.bind(DAY, DAY, snapshot(20));
        assertThrows(IllegalStateException.class, () -> first.bind(DAY.plusDays(1), DAY.plusDays(1), snapshot(20)));
        assertDoesNotThrow(() -> second.bind(DAY.plusDays(1), DAY.plusDays(1), snapshot(20)));
        assertFalse(first.unresolved());assertFalse(second.unresolved());
        assertNull(first.verifiedSnapshot());assertNull(second.verifiedSnapshot());
        verifyNoInteractions(jdbc);
    }

    @Test void expectedTargetSyntaxIsCheckedAtSessionCreationAndConfigurationKeepsItsReference() {
        var jdbc = mock(JdbcTemplate.class);
        var properties = new QuestDbProperties();
        var invalid = assertDoesNotThrow(() -> new QuestDbRetailSentimentDailyTarget(jdbc, properties, true, " bad "));
        assertEquals("D098 exact expected physical target identity required",
                assertThrows(IllegalArgumentException.class, invalid::newSession).getMessage());
        var target = new QuestDbRetailSentimentDailyTarget(jdbc, properties, true, null);
        var session = target.newSession();
        properties.setHost("192.0.2.1");
        assertEquals("D098 mutations require the explicitly enabled local private instance on 18822/19010",
                assertThrows(IllegalStateException.class, session::verifyPrivateInstance).getMessage());
        verifyNoInteractions(jdbc);
    }

    @Test void pureScopeAndCanonicalRowsKeepTheOriginalShapesAndBytes() throws Exception {
        var scope = new RetailSentimentDailyFullSourceScope(DAY, DAY, 2);
        var tree = JobDefinitionJson.mapper().valueToTree(scope);
        var names = new ArrayList<String>();tree.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("from", "to", "rawRows"), names);
        assertEquals(2, tree.path("rawRows").asLong());
        assertThrows(IllegalArgumentException.class, () -> new RetailSentimentDailyFullSourceScope(DAY, DAY, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetailSentimentDailyFullSourceScope(DAY, DAY.plusDays(31), 2));
        assertEquals("00000000000050e9013fe0000000000000013ff333333333333301402400000000000001c000000000000000013fe3333333333333010000000000000002010000000000000001013fc999999999999a01000000000000000101000000000000000301404980000000000001bff0000000000000", HexFormat.of().formatHex(RetailSentimentDailyMaterializeAdapter.CODEC.canonicalBytes(ROW)));
    }

    @Test void incrementalNoOpNeedsAcknowledgedRefreshAndActualStableReadback() {
        var jdbc = mock(JdbcTemplate.class);var port = ready(jdbc);
        doAnswer(call -> {assertTrue(port.unresolved());assertNull(port.verifiedSnapshot());return null;})
                .when(jdbc).execute("REFRESH MATERIALIZED VIEW mv_retail_sentiment_daily INCREMENTAL");
        port.send(List.of(ROW));
        assertTrue(port.unresolved());assertNull(port.verifiedSnapshot());
        assertEquals(List.of(ROW), port.readback(List.of(DAY)));
        assertTrue(port.walSettled());
        assertEquals(snapshot(20), port.verifiedSnapshot());assertFalse(port.unresolved());
        assertThrows(IllegalStateException.class, () -> port.send(List.of(ROW)));
        verify(jdbc, times(1)).execute("REFRESH MATERIALIZED VIEW mv_retail_sentiment_daily INCREMENTAL");
        verifyNoMoreInteractions(jdbc);
    }

    @Test void lostRefreshAcknowledgmentStaysUnresolvedDespiteMatchingValues() {
        var jdbc = mock(JdbcTemplate.class);var port = ready(jdbc);
        var failure = new IllegalStateException("connection lost after server accepted refresh");
        doThrow(failure).when(jdbc).execute("REFRESH MATERIALIZED VIEW mv_retail_sentiment_daily INCREMENTAL");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> port.send(List.of(ROW))));
        assertTrue(port.unresolved());assertFalse(port.uncertainSenderStopped());
        assertEquals(List.of(ROW), port.readback(List.of(DAY)));
        assertFalse(port.walSettled());assertNull(port.verifiedSnapshot());
        assertThrows(IllegalStateException.class, () -> port.send(List.of(ROW)));
        verify(jdbc, times(1)).execute("REFRESH MATERIALIZED VIEW mv_retail_sentiment_daily INCREMENTAL");
    }

    @Test void outputDriftDuringReadbackCannotCertifyTheSession() {
        var jdbc = mock(JdbcTemplate.class);var port = ready(jdbc);port.send(List.of(ROW));
        doReturn(snapshot(20), snapshot(21)).when(port).snapshot();
        assertEquals("D098 output changed during real readback",
                assertThrows(IllegalStateException.class, () -> port.readback(List.of(DAY))).getMessage());
        assertFalse(port.walSettled());assertTrue(port.unresolved());assertNull(port.verifiedSnapshot());
    }

    @Test void cancellationAfterSubmissionRetainsUncertaintyAndRestoresInterrupt() {
        var jdbc = mock(JdbcTemplate.class);var port = ready(jdbc);port.send(List.of(ROW));
        var cancelled = new AtomicBoolean(true);port.cancellationProbe(cancelled::get);
        try {
            assertEquals("D098 refresh cancelled after submission; reconcile before replay",
                    assertThrows(CancellationException.class, () -> port.readback(List.of(DAY))).getMessage());
            assertTrue(Thread.currentThread().isInterrupted());assertTrue(port.unresolved());
        } finally {Thread.interrupted();}
        cancelled.set(false);
        assertFalse(port.walSettled());assertNull(port.verifiedSnapshot());
    }

    @Test void eachCalendarReaderUsesTheExactSessionJdbcWithoutEagerQueries() {
        var jdbc = mock(JdbcTemplate.class);
        var port = new RetailSentimentDailyMaterializationPort(jdbc, new QuestDbProperties(), false);
        try (var readers = mockConstruction(QuestDbBoundedReader.class,
                (reader, context) -> assertSame(jdbc, context.arguments().getFirst()))) {
            var first = port.newCalendarReader();var second = port.newCalendarReader();
            assertNotSame(first, second);assertEquals(2, readers.constructed().size());
            readers.constructed().forEach(reader -> verifyNoInteractions(reader));
            verifyNoInteractions(jdbc);
        }
    }

    private static RetailSentimentDailyMaterializationPort ready(JdbcTemplate jdbc) {
        var port = spy(new RetailSentimentDailyMaterializationPort(jdbc, new QuestDbProperties(), true, TARGET));
        doReturn(TARGET).when(port).targetId();doReturn(snapshot(20)).when(port).snapshot();
        doReturn(List.of(ROW)).when(port).expected(DAY, DAY);doReturn(List.of(ROW)).when(port).actual(DAY, DAY);
        doReturn(2L).when(port).sourceRawRows(DAY, DAY);
        doNothing().when(port).requireSourceCoverage(eq(DAY), eq(DAY), anyLong(), anyList());
        port.bind(DAY, DAY, snapshot(20));return port;
    }
    private static RetailSentimentDailySnapshot snapshot(long mvTxn) {
        return new RetailSentimentDailySnapshot(12, "source~12", 8, 8, 8, true,
                22, "mv_retail_sentiment_daily~22", mvTxn, 20, 20, true, true, true,
                RetailSentimentDailyMaterializationPort.DEFINITION_SHA, "start", "finished", 8, 8, "DAY", "valid");
    }
}
