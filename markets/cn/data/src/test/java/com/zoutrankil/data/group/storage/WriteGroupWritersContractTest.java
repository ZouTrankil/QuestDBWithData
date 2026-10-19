package com.zoutrankil.data.group.storage;

import com.zoutrankil.data.group.port.WriteGroupWriters;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import io.questdb.client.QuestDB;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WriteGroupWritersContractTest {
    static Stream<Arguments> sessions() {
        return Stream.of(
                Arguments.of("stockBasic", "stock", "StockBasic", "java_tushare_stock_basic_qwp_test"),
                Arguments.of("exchangeCalendar", "calendar", "ExchangeCalendar", "trade_cal"),
                Arguments.of("daily", "stock", "Daily", "java_d007_daily_factory"),
                Arguments.of("dailyBasic", "stock", "DailyBasic", "daily_basic"),
                Arguments.of("stockFactor", "stock", "StockFactor", "stk_factor"),
                Arguments.of("stockLimit", "stock", "StockLimit", "java_d010_stk_limit_factory"),
                Arguments.of("etfDaily", "etf", "EtfDaily", "java_d014_etf_daily_factory"),
                Arguments.of("etfAdj", "etf", "EtfAdj", "java_d015_etf_adj_factory"),
                Arguments.of("etfShare", "etf", "EtfShare", "java_d016_etf_share_factory"),
                Arguments.of("etfFactor", "etf", "EtfFactor", "java_d017_etf_factor_factory"),
                Arguments.of("moneyflowDc", "flow", "MoneyflowDc", "java_d026_moneyflow_dc_factory"),
                Arguments.of("moneyflowThs", "flow", "MoneyflowThs", "java_d025_moneyflow_ths_factory"),
                Arguments.of("moneyflow", "flow", "Moneyflow", "java_d024_moneyflow_factory"),
                Arguments.of("indexDailyMarket", "index", "IndexDailyMarket", "java_d019_index_daily_market_factory"),
                Arguments.of("indexDailyBasic", "index", "IndexDailyBasic", "java_d020_index_daily_basic_factory"),
                Arguments.of("indexWeight", "index", "IndexWeight", "java_d021_index_weight_factory"),
                Arguments.of("etfPortfolio", "etf", "EtfPortfolio", "java_d018_etf_portfolio_factory"),
                Arguments.of("etfBasic", "etf", "EtfBasic", "java_d013_etf_basic_factory"),
                Arguments.of("indexMonthly", "index", "IndexMonthly", "java_d022_index_monthly_factory"),
                Arguments.of("dcIndex", "index", "DcIndex", "java_d023_dc_index_factory"),
                Arguments.of("moneyflowHsgt", "flow", "MoneyflowHsgt", "java_d027_moneyflow_hsgt_factory"),
                Arguments.of("stockStDaily", "stock", "StockStDaily", "java_d012_stk_st_daily_factory"),
                Arguments.of("stockSuspend", "stock", "StockSuspend", "java_d011_stk_suspend_factory"));
    }

    @ParameterizedTest @MethodSource("sessions")
    void eachInvocationCreatesTheOriginalWriterWithTheOriginalCodecAndArguments(
            String method, String family, String prefix, String table) throws Exception {
        var dataSource = mock(DataSource.class);
        var questdb = mock(QuestDB.class);
        var factory = new QuestDbWriteGroupWriters(new JdbcTemplate(dataSource), questdb);
        var entry = Arrays.stream(WriteGroupWriters.class.getMethods()).filter(value -> value.getName().equals(method)).findFirst().orElseThrow();
        String targetId = "static-v2-" + "a".repeat(64);
        Object[] arguments = entry.getParameterCount() == 1 ? new Object[]{table} : new Object[]{table, targetId};
        var first = (VerifiedWriteSession<?, ?>) entry.invoke(factory, arguments);
        var second = (VerifiedWriteSession<?, ?>) entry.invoke(factory, arguments);
        var expected = Class.forName("com.zoutrankil.data." + family + ".storage." + prefix + "WritePort");
        assertEquals(expected, first.getClass());
        assertEquals(expected, second.getClass());
        assertNotSame(first, second);
        assertSame(expected.getField("CODEC").get(null), first.codec());
        assertSame(first.codec(), second.codec());
        var strings = new ArrayList<String>();
        for (var field : expected.getDeclaredFields()) if (field.getType() == String.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
            field.setAccessible(true);
            strings.add((String) field.get(first));
        }
        assertTrue(strings.contains(table));
        if (arguments.length == 2) assertTrue(strings.contains(targetId));
        verifyNoInteractions(dataSource, questdb);
    }

    @Test void publicationTablesAreFreshAndConstructionHasNoIo() {
        var source = mock(DataSource.class);
        var questdb = mock(QuestDB.class);
        var factory = new QuestDbWriteGroupWriters(new JdbcTemplate(source), questdb);
        assertNotSame(factory.indexMonthlyTables(), factory.indexMonthlyTables());
        assertNotSame(factory.moneyflowHsgtTables(), factory.moneyflowHsgtTables());
        verifyNoInteractions(source, questdb);
    }
}
