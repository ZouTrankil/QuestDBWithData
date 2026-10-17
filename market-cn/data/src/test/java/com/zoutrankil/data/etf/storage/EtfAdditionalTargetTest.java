package com.zoutrankil.data.etf.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.EtfBasicDataset;
import com.zoutrankil.data.domain.EtfPortfolio;
import com.zoutrankil.data.domain.EtfPortfolioDataset;
import com.zoutrankil.data.domain.EtfShareDataset;
import com.zoutrankil.data.etf.domain.EtfTargetRange;
import com.zoutrankil.data.etf.port.EtfTarget;
import com.zoutrankil.data.etf.port.EtfWriteTarget;
import com.zoutrankil.data.repository.QuestDbWriteChecks;
import com.zoutrankil.data.repository.StaticTargetIdentity;
import io.questdb.client.QuestDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtfAdditionalTargetTest {
    private static final String FROZEN_ID = "static-v2-" + "a".repeat(64);

    private enum Family {
        BASIC("java_d013_etf_basic_t15", null,
                "Exact isolated etf_basic QuestDB target identity required", null, null, null),
        PORTFOLIO("java_d018_etf_portfolio_t15", "ann_date",
                "Exact isolated etf_portfolio target identity required",
                "QuestDB did not return etf_portfolio announcement range",
                "Invalid etf_portfolio physical announcement-date range",
                "Invalid physical etf_portfolio announcement-date range"),
        SHARE("java_d016_etf_share_t15", "timestamp",
                "Exact D016 isolated etf_share QuestDB target identity required",
                "QuestDB did not return etf_share date range",
                "QuestDB etf_share date range has invalid types", "Invalid etf_share physical range");

        final String table, column, identityError, missingError, typeError, orderError;
        Family(String table, String column, String identityError, String missingError,
               String typeError, String orderError) {
            this.table = table; this.column = column; this.identityError = identityError;
            this.missingError = missingError; this.typeError = typeError; this.orderError = orderError;
        }
        EtfWriteTarget<?, ?> target(JdbcTemplate jdbc, QuestDB questdb) { return target(table, jdbc, questdb); }
        EtfWriteTarget<?, ?> target(String name, JdbcTemplate jdbc, QuestDB questdb) {
            return switch (this) {
                case BASIC -> new QuestDbEtfBasicTarget(name, jdbc, questdb);
                case PORTFOLIO -> new QuestDbEtfPortfolioTarget(name, jdbc, questdb);
                case SHARE -> new QuestDbEtfShareTarget(name, jdbc, questdb);
            };
        }
        EtfTarget<?, ?> datedTarget(JdbcTemplate jdbc) { return (EtfTarget<?, ?>) target(jdbc, mock(QuestDB.class)); }
        DatasetDefinition definition() {
            return switch (this) {
                case BASIC -> EtfBasicDataset.definition(table);
                case PORTFOLIO -> EtfPortfolioDataset.definition(table);
                case SHARE -> EtfShareDataset.definition(table);
            };
        }
        String identitySql() {
            return "SELECT id,directoryName FROM tables() WHERE table_name" + (this == SHARE ? " = ?" : "=?");
        }
        String rangeSql() {
            return "SELECT cast(min(" + column + ") AS long) AS min_micros, cast(max(" + column
                    + ") AS long) AS max_micros FROM \"" + table + "\"";
        }
        Object codec() {
            return switch (this) {
                case BASIC -> EtfBasicWritePort.CODEC;
                case PORTFOLIO -> EtfPortfolioWritePort.CODEC;
                case SHARE -> EtfShareWritePort.CODEC;
            };
        }
        int maxRows() { return switch (this) { case BASIC -> 251; case PORTFOLIO -> 1_000_001; case SHARE -> 5998; }; }
    }

    @ParameterizedTest @EnumSource(Family.class)
    void constructionDoesNoIoAndWritersHaveSeparateStateAndJdbcSettings(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class); var source = mock(DataSource.class);
        var target = family.target(jdbc, questdb);
        assertEquals(family.table, target.tableName());
        verifyNoInteractions(jdbc, questdb, source);
        when(jdbc.getDataSource()).thenReturn(source);
        var first = target.newWriter(FROZEN_ID); var second = target.newWriter(FROZEN_ID);
        assertNotSame(first, second);
        assertSame(family.codec(), first.codec()); assertSame(first.codec(), second.codec());
        var field = first.getClass().getDeclaredField("jdbc"); field.setAccessible(true);
        var firstJdbc = (JdbcTemplate) field.get(first); var secondJdbc = (JdbcTemplate) field.get(second);
        assertNotSame(firstJdbc, secondJdbc); assertNotSame(jdbc, firstJdbc);
        assertSame(source, firstJdbc.getDataSource()); assertSame(source, secondJdbc.getDataSource());
        assertEquals(20, firstJdbc.getQueryTimeout()); assertEquals(family.maxRows(), firstJdbc.getMaxRows());
        assertEquals(20, secondJdbc.getQueryTimeout()); assertEquals(family.maxRows(), secondJdbc.getMaxRows());
        assertFalse(first.uncertainSenderStopped()); assertFalse(second.uncertainSenderStopped());
        assertThrows(IllegalArgumentException.class, () -> target.newWriter("static-v2-short"));
        verify(jdbc, times(2)).getDataSource(); verifyNoInteractions(questdb, source);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void identityPreservesPreflightOrderSqlAndPhysicalGeneration(Family family) {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class);
        var calls = new ArrayList<String>();
        when(jdbc.queryForList(family.identitySql(), family.table)).thenAnswer(invocation -> {
            calls.add("query"); return List.of(Map.of("id", 37L, "directoryName", "generation-37"));
        });
        try (var checks = mockStatic(QuestDbWriteChecks.class); var identity = mockStatic(StaticTargetIdentity.class)) {
            checks.when(() -> QuestDbWriteChecks.preflight(jdbc, family.table, family.definition()))
                    .thenAnswer(invocation -> { calls.add("preflight"); return null; });
            identity.when(() -> StaticTargetIdentity.identify(jdbc, family.table, 37L, "generation-37"))
                    .thenAnswer(invocation -> { calls.add("identity"); return FROZEN_ID; });
            assertEquals(FROZEN_ID, family.target(jdbc, questdb).targetId());
            assertEquals(family == Family.SHARE ? List.of("query", "identity")
                    : List.of("preflight", "query", "identity"), calls);
            if (family == Family.SHARE) checks.verifyNoInteractions();
            else checks.verify(() -> QuestDbWriteChecks.preflight(jdbc, family.table, family.definition()));
            identity.verify(() -> StaticTargetIdentity.identify(jdbc, family.table, 37L, "generation-37"));
        }
        verify(jdbc).queryForList(family.identitySql(), family.table); verifyNoInteractions(questdb);
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"BASIC", "PORTFOLIO"})
    void schemaFailureStillPrecedesTheIdentityQuery(Family family) {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class);
        var failure = new IllegalStateException("physical schema rejected");
        try (var checks = mockStatic(QuestDbWriteChecks.class); var identity = mockStatic(StaticTargetIdentity.class)) {
            checks.when(() -> QuestDbWriteChecks.preflight(jdbc, family.table, family.definition())).thenThrow(failure);
            assertSame(failure, assertThrows(IllegalStateException.class, family.target(jdbc, questdb)::targetId));
            identity.verifyNoInteractions();
        }
        verifyNoInteractions(jdbc, questdb);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void malformedIdentityKeepsItsOriginalFamilyError(Family family) {
        var jdbc = mock(JdbcTemplate.class); var target = family.target(jdbc, mock(QuestDB.class));
        var valid = Map.<String, Object>of("id", 37, "directoryName", "generation-37");
        List<List<Map<String, Object>>> cases = List.of(List.of(), List.of(valid, valid),
                List.of(Map.of("id", "37", "directoryName", "generation-37")),
                List.of(Map.of("id", 37, "directoryName", 42)), List.of(Map.of("id", 37)));
        try (var checks = mockStatic(QuestDbWriteChecks.class); var identity = mockStatic(StaticTargetIdentity.class)) {
            for (var rows : cases) {
                when(jdbc.queryForList(family.identitySql(), family.table)).thenReturn(rows);
                assertEquals(family.identityError, assertThrows(IllegalStateException.class, target::targetId).getMessage());
            }
            identity.verifyNoInteractions();
        }
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"PORTFOLIO", "SHARE"})
    void rangeUsesOriginalBusinessDateColumnAndMicrosecondsIncludingPreEpochDates(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc);
        var min = LocalDate.of(1969, 12, 31); var max = LocalDate.of(2026, 10, 8);
        when(rs.getObject("min_micros")).thenReturn(micros(min));
        when(rs.getObject("max_micros")).thenReturn(micros(max));
        assertEquals(new EtfTargetRange(min, max), family.datedTarget(jdbc).range());
        verify(jdbc).query(eq(family.rangeSql()), any(ResultSetExtractor.class));
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"PORTFOLIO", "SHARE"})
    void emptyPartialAndNonNumericRangeBoundsKeepTheirOriginalMeaning(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc); var target = family.datedTarget(jdbc);
        assertEquals(new EtfTargetRange(null, null), target.range());
        Object[][] invalid = {{null, 0L}, {0L, null}, {"0", 0L}, {0L, "0"}};
        for (var bounds : invalid) {
            when(rs.getObject("min_micros")).thenReturn(bounds[0]);
            when(rs.getObject("max_micros")).thenReturn(bounds[1]);
            assertEquals(family.typeError, assertThrows(IllegalStateException.class, target::range).getMessage());
        }
    }

    @ParameterizedTest @EnumSource(value = Family.class, names = {"PORTFOLIO", "SHARE"})
    void missingReversedAndNonMidnightRangeBoundsFailAtTheSameBoundary(Family family) throws Exception {
        var jdbc = mock(JdbcTemplate.class); var rs = rangeRows(jdbc); var target = family.datedTarget(jdbc);
        when(rs.next()).thenReturn(false);
        assertEquals(family.missingError, assertThrows(IllegalStateException.class, target::range).getMessage());
        when(rs.next()).thenReturn(true);
        long start = micros(LocalDate.of(2026, 10, 8));
        when(rs.getObject("min_micros")).thenReturn(start + 86_400_000_000L);
        when(rs.getObject("max_micros")).thenReturn(start);
        assertEquals(family.orderError, assertThrows(IllegalStateException.class, target::range).getMessage());
        when(rs.getObject("min_micros")).thenReturn(start + 1);
        when(rs.getObject("max_micros")).thenReturn(start + 1);
        assertThrows(IllegalArgumentException.class, target::range);
    }

    @ParameterizedTest @EnumSource(Family.class)
    void onlyPortfolioAdmitsItsExactFormalNameAndInjectedNamesAreRejectedWithoutIo(Family family) {
        var jdbc = mock(JdbcTemplate.class); var questdb = mock(QuestDB.class);
        for (var table : List.of("etf_basic", "etf_portfolio", "etf_share", "unrelated")) {
            if (family == Family.PORTFOLIO && table.equals("etf_portfolio"))
                assertEquals(table, family.target(table, jdbc, questdb).tableName());
            else assertThrows(IllegalStateException.class, () -> family.target(table, jdbc, questdb));
        }
        assertThrows(IllegalArgumentException.class, () -> family.target(family.table + "\";DROP TABLE x", jdbc, questdb));
        verifyNoInteractions(jdbc, questdb);
    }

    @Test
    void portfolioDateInterfaceDelegatesToExistingAnnouncementOperationsWithoutCopying() {
        var writer = mock(EtfPortfolioWritePort.class);
        var date = LocalDate.of(2026, 10, 8); var dates = List.of(date); List<EtfPortfolio> rows = new ArrayList<>();
        when(writer.readExistingAnnouncementDates()).thenReturn(dates);
        when(writer.readAnnouncementDate(date)).thenReturn(rows);
        when(writer.readExistingDates()).thenCallRealMethod(); when(writer.readDate(date)).thenCallRealMethod();
        assertSame(dates, writer.readExistingDates()); assertSame(rows, writer.readDate(date));
        verify(writer).readExistingAnnouncementDates(); verify(writer).readAnnouncementDate(date);
        var failure = new IllegalStateException("original announcement failure");
        when(writer.readAnnouncementDate(date)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> writer.readDate(date)));
    }

    @Test
    void sharedCompletenessCapsRetainTheFrozenValues() {
        assertEquals(1_000_000, EtfPortfolioDataset.MAX_ROWS_PER_ANN_DATE);
        assertEquals(EtfPortfolioDataset.MAX_ROWS_PER_ANN_DATE, EtfPortfolioWritePort.MAX_ROWS_PER_ANN_DATE);
        assertEquals(3 * (2000 - 1), EtfShareDataset.MAX_ROWS_PER_DATE);
        assertEquals(EtfShareDataset.MAX_ROWS_PER_DATE, EtfShareWritePort.MAX_ROWS_PER_DATE);
    }

    private static long micros(LocalDate date) {
        return Math.multiplyExact(date.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 1_000_000L);
    }

    @SuppressWarnings("unchecked")
    private static ResultSet rangeRows(JdbcTemplate jdbc) throws Exception {
        var rs = mock(ResultSet.class); when(rs.next()).thenReturn(true);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class))).thenAnswer(invocation ->
                ((ResultSetExtractor<?>) invocation.getArgument(1)).extractData(rs));
        return rs;
    }
}
