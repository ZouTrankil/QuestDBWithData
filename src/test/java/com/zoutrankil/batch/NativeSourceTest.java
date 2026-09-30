package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.service.PageExecutor;
import com.zoutrankil.questdbwithdata.domain.PageContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSourceTest {
    @TempDir Path archive;
    static Map<String,JsonNode> row(String dataset,String code) {
        if(dataset.equals("dividend")) {
            var dividend=new LinkedHashMap<String,JsonNode>();
            dividend.put("ts_code",Json.MAPPER.valueToTree(code));dividend.put("end_date",Json.MAPPER.nullNode());
            dividend.put("ann_date",Json.MAPPER.valueToTree("20260928"));dividend.put("div_proc",Json.MAPPER.valueToTree("预案"));
            dividend.put("stk_div",Json.MAPPER.valueToTree(0.0));dividend.put("stk_bo_rate",Json.MAPPER.nullNode());
            dividend.put("stk_co_rate",Json.MAPPER.nullNode());dividend.put("cash_div",Json.MAPPER.valueToTree(0.1));
            dividend.put("cash_div_tax",Json.MAPPER.valueToTree(0.1));
            for(String field:List.of("record_date","ex_date","pay_date","div_listdate","imp_ann_date","base_date")) dividend.put(field,Json.MAPPER.nullNode());
            dividend.put("base_share",Json.MAPPER.nullNode());return dividend;
        }
        if(dataset.equals("share_float")) {
            return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),"ann_date",Json.MAPPER.valueToTree("20260928"),
                    "float_date",Json.MAPPER.valueToTree("20261016"),"float_share",Json.MAPPER.valueToTree(250000.0),
                    "float_ratio",Json.MAPPER.valueToTree(1.25),"holder_name",Json.MAPPER.valueToTree("示例股东"),
                    "share_type",Json.MAPPER.valueToTree("首发原股东限售股份")));
        }
        if(dataset.equals("fina_audit")) {
            return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),"ann_date",Json.MAPPER.valueToTree("20260928"),
                    "end_date",Json.MAPPER.valueToTree("20251231"),"audit_result",Json.MAPPER.valueToTree("标准无保留意见"),
                    "audit_fees",Json.MAPPER.valueToTree(1000000.0),"audit_agency",Json.MAPPER.valueToTree("示例会计师事务所"),
                    "audit_sign",Json.MAPPER.valueToTree("测试会计师")));
        }
        if(dataset.equals("fut_mapping")) {
            return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),"trade_date",Json.MAPPER.valueToTree("20260928"),
                    "mapping_ts_code",Json.MAPPER.valueToTree("IF2610.CFX")));
        }
        if(dataset.equals("ft_limit")) {
            return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),"trade_date",Json.MAPPER.valueToTree("20260928"),
                    "name",Json.MAPPER.valueToTree("IF2610"),"up_limit",Json.MAPPER.valueToTree(4500.0),"down_limit",Json.MAPPER.valueToTree(3500.0),
                    "m_ratio",Json.MAPPER.valueToTree(12.0),"cont",Json.MAPPER.valueToTree("IF"),"exchange",Json.MAPPER.valueToTree("CFFEX")));
        }
        if(dataset.equals("fut_holding")) {
            var holding=new LinkedHashMap<String,JsonNode>();holding.put("trade_date",Json.MAPPER.valueToTree("20260928"));
            holding.put("symbol",Json.MAPPER.valueToTree(code));holding.put("broker",Json.MAPPER.valueToTree("测试期货"));
            holding.put("vol",Json.MAPPER.valueToTree(100));holding.put("vol_chg",Json.MAPPER.valueToTree(-2));
            holding.put("long_hld",Json.MAPPER.valueToTree(80));holding.put("long_chg",Json.MAPPER.valueToTree(4));
            holding.put("short_hld",Json.MAPPER.valueToTree(70));holding.put("short_chg",Json.MAPPER.valueToTree(-3));
            holding.put("exchange",Json.MAPPER.valueToTree("CFFEX"));return holding;
        }
        if(dataset.equals("etf_basic")) {
            var etf=new LinkedHashMap<String,JsonNode>();etf.put("ts_code",Json.MAPPER.valueToTree("510300.SH"));
            etf.put("name",Json.MAPPER.valueToTree("沪深300ETF"));etf.put("management",Json.MAPPER.valueToTree("示例基金"));
            etf.put("custodian",Json.MAPPER.valueToTree("示例银行"));etf.put("fund_type",Json.MAPPER.valueToTree("股票型"));
            for(String field:List.of("found_date","due_date","list_date","issue_date","delist_date","purc_startdate","redm_startdate")) etf.put(field,Json.MAPPER.valueToTree("20200101"));
            for(String field:List.of("issue_amount","m_fee","c_fee","duration_year","p_value","min_amount","exp_return")) etf.put(field,Json.MAPPER.valueToTree(1.0));
            etf.put("benchmark",Json.MAPPER.valueToTree("沪深300指数"));etf.put("status",Json.MAPPER.valueToTree("L"));
            etf.put("invest_type",Json.MAPPER.valueToTree("被动指数型"));etf.put("type",Json.MAPPER.valueToTree("ETF"));
            etf.put("trustee",Json.MAPPER.valueToTree("示例受托人"));etf.put("market",Json.MAPPER.valueToTree("E"));return etf;
        }
        if(dataset.equals("etf_share")) {
            return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree("510300.SH"),
                    "trade_date",Json.MAPPER.valueToTree("20260928"),"fd_share",Json.MAPPER.valueToTree(123456.75)));
        }
        if(dataset.equals("fut_basic")) {
            var basic=new LinkedHashMap<String,JsonNode>();basic.put("ts_code",Json.MAPPER.valueToTree("IF2610.CFX"));
            basic.put("symbol",Json.MAPPER.valueToTree("IF2610"));basic.put("exchange",Json.MAPPER.valueToTree("CFFEX"));
            basic.put("name",Json.MAPPER.valueToTree("沪深300股指期货2610"));basic.put("fut_code",Json.MAPPER.valueToTree("IF"));
            basic.put("multiplier",Json.MAPPER.valueToTree(300.0));basic.put("trade_unit",Json.MAPPER.valueToTree("每点300元"));
            basic.put("per_unit",Json.MAPPER.valueToTree(1.0));basic.put("quote_unit",Json.MAPPER.valueToTree("指数点"));
            basic.put("list_date",Json.MAPPER.valueToTree("20260717"));basic.put("delist_date",Json.MAPPER.valueToTree("20261016"));
            basic.put("d_month",Json.MAPPER.valueToTree("202610"));basic.put("last_ddate",Json.MAPPER.valueToTree("20261016"));return basic;
        }
        if(dataset.equals("disclosure_date")) {
            var disclosure=new LinkedHashMap<String,JsonNode>();disclosure.put("ts_code",Json.MAPPER.valueToTree("000001.SZ"));
            disclosure.put("ann_date",Json.MAPPER.valueToTree("20261015"));disclosure.put("end_date",Json.MAPPER.valueToTree("20260930"));
            disclosure.put("pre_date",Json.MAPPER.valueToTree("20261030"));disclosure.put("actual_date",Json.MAPPER.nullNode());
            disclosure.put("modify_date",Json.MAPPER.nullNode());return disclosure;
        }
        if(dataset.equals("shibor")) {
            var shibor=new LinkedHashMap<String,JsonNode>();shibor.put("date",Json.MAPPER.valueToTree("20260928"));
            shibor.put("on",Json.MAPPER.valueToTree(1.25));shibor.put("1w",Json.MAPPER.valueToTree(1.5));
            shibor.put("2w",Json.MAPPER.valueToTree(1.6));shibor.put("1m",Json.MAPPER.valueToTree(1.7));
            shibor.put("3m",Json.MAPPER.valueToTree(1.8));shibor.put("6m",Json.MAPPER.valueToTree(1.9));
            shibor.put("9m",Json.MAPPER.valueToTree(2.0));shibor.put("1y",Json.MAPPER.valueToTree(2.1));return shibor;
        }
        if(dataset.equals("shibor_lpr")) return new LinkedHashMap<>(Map.of("date",Json.MAPPER.valueToTree("20260928"),
                "1y",Json.MAPPER.valueToTree(3.0),"5y",Json.MAPPER.valueToTree(3.5)));
        if(dataset.equals("hibor")) {
            var hibor=new LinkedHashMap<String,JsonNode>();hibor.put("date",Json.MAPPER.valueToTree("20260928"));
            for(String field:List.of("on","1w","2w","1m","2m","3m","6m","12m")) hibor.put(field,Json.MAPPER.valueToTree(2.75));
            return hibor;
        }
        if(dataset.equals("us_tbr")) {
            var rates=new LinkedHashMap<String,JsonNode>();rates.put("date",Json.MAPPER.valueToTree("20260928"));
            for(String field:List.of("w4_bd","w4_ce","w8_bd","w8_ce","w13_bd","w13_ce","w17_bd","w17_ce","w26_bd","w26_ce","w52_bd","w52_ce"))
                rates.put(field,Json.MAPPER.valueToTree(4.25));
            return rates;
        }
        if(dataset.equals("cn_cpi")) {
            var cpi=new LinkedHashMap<String,JsonNode>();cpi.put("month",Json.MAPPER.valueToTree("202609"));
            for(String field:List.of("nt_val","nt_yoy","nt_mom","nt_accu","town_val","town_yoy","town_mom","town_accu","cnt_val","cnt_yoy","cnt_mom","cnt_accu"))
                cpi.put(field,Json.MAPPER.valueToTree(102.25));
            return cpi;
        }
        if(Set.of("cn_ppi","cn_pmi","cn_m").contains(dataset)) {
            var macro=new LinkedHashMap<String,JsonNode>();macro.put("month",Json.MAPPER.valueToTree("202609"));
            for(var column:SourceContract.load(dataset).businessColumns()) if(column.type().equals("DOUBLE"))
                macro.put(column.source(),Json.MAPPER.valueToTree(12.25));
            return macro;
        }
        if(dataset.equals("cn_gdp")) {
            var gdp=new LinkedHashMap<String,JsonNode>();gdp.put("quarter",Json.MAPPER.valueToTree("2026Q2"));
            for(String field:List.of("gdp","gdp_yoy","pi","pi_yoy","si","si_yoy","ti","ti_yoy")) gdp.put(field,Json.MAPPER.valueToTree(12.25));
            return gdp;
        }
        if(dataset.equals("fina_mainbz")) {
            var main=new LinkedHashMap<String,JsonNode>();main.put("ts_code",Json.MAPPER.valueToTree(code));
            main.put("end_date",Json.MAPPER.valueToTree("20260630"));main.put("bz_item",Json.MAPPER.valueToTree("测试主营"));
            main.put("bz_sales",Json.MAPPER.valueToTree(12.25));main.put("bz_profit",Json.MAPPER.nullNode());main.put("bz_cost",Json.MAPPER.valueToTree(7.5));
            main.put("curr_type",Json.MAPPER.valueToTree("CNY"));main.put("update_flag",Json.MAPPER.valueToTree("1"));
            main.put("bz_code",Json.MAPPER.valueToTree("P"));return main;
        }
        if(dataset.equals("exchange_calendar")) return new LinkedHashMap<>(Map.of("exchange",Json.MAPPER.valueToTree(code),
                "cal_date",Json.MAPPER.valueToTree("20260928"),"is_open",Json.MAPPER.valueToTree(1),
                "pretrade_date",Json.MAPPER.valueToTree("20260925")));
        if(dataset.equals("stk_suspend")) return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),
                "trade_date",Json.MAPPER.valueToTree("20260928"),"suspend_type",Json.MAPPER.valueToTree("S"),"suspend_timing",Json.MAPPER.nullNode()));
        if(dataset.equals("stk_st_daily")) return new LinkedHashMap<>(Map.of("ts_code",Json.MAPPER.valueToTree(code),"name",Json.MAPPER.valueToTree("ST示例"),
                "start_date",Json.MAPPER.valueToTree("20260901"),"end_date",Json.MAPPER.nullNode(),"ann_date",Json.MAPPER.valueToTree("20260901"),"change_reason",Json.MAPPER.valueToTree("风险警示")));
        var contract=SourceContract.load(dataset);var row=new LinkedHashMap<String,JsonNode>();
        for(var column:contract.businessColumns()) row.put(column.source(),Json.MAPPER.valueToTree(switch(column.type()) {
            case "LONG", "INT" -> 123L;
            case "STRING" -> "测试证券";
            case "SYMBOL" -> code;
            case "TIMESTAMP", "TIMESTAMP_NS" -> "20260928";
            default -> 12.25;
        }));
        if(Set.of("daily","daily_basic","etf_daily").contains(dataset)) row.put(switch(dataset) { case "daily" -> "ah_vol"; case "daily_basic" -> "pe_ttm"; default -> "pre_close"; },Json.MAPPER.nullNode());return row;
    }
    @Test void shiborUsesOneDayBoundsAndKeepsNumericTenorColumns() throws Exception {
        var contract=SourceContract.load("shibor");var request=request("shibor",Set.of());
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("shibor",provider.endpoint());assertEquals(Map.of("start_date","20260928","end_date","20260928"),params);
            assertEquals(List.of("date","on","1w","2w","1m","3m","6m","9m","1y"),provider.fields());
            return new PageExecutor.Page(List.of(row("shibor","ignored")),null,true,"fixture-v1");
        });
        assertTrue(collected.completeCoverage());assertEquals(1,collected.rows());
        var frozen=new SourceCollector(archive).read(collected.fingerprint());assertEquals("2026-09-28",frozen.rows().getFirst().get("timestamp"));
        assertTrue(SourceQuality.failures("shibor",frozen.rows()).isEmpty());
        var ilp=new String(QuestDbTablePort.encode(contract,"jdb_test_fixture",frozen.rows()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(ilp.contains("1w=1.5"));assertTrue(ilp.contains("1y=2.1"));
        var ddl=contract.createTableSql("jdb_test_fixture");assertTrue(ddl.contains("\"1w\" DOUBLE"));assertTrue(ddl.contains("\"timestamp\""));
    }
    @Test void usTbrUsesExactDateSnapshotAndPreservesAllLegacyRateFields() throws Exception {
        var contract=SourceContract.load("us_tbr");var request=request("us_tbr",Set.of());
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("us_tbr",provider.endpoint());assertEquals(Map.of("date","20260928"),params);
            assertEquals(contract.businessColumns().stream().map(SourceContract.Column::source).toList(),provider.fields());
            return new PageExecutor.Page(List.of(row("us_tbr","ignored")),null,true,"fixture-v1");
        });
        assertTrue(collected.completeCoverage());assertEquals(1,collected.rows());
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertEquals("2026-09-28",frozen.rows().getFirst().get("date"));
        assertTrue(SourceQuality.failures("us_tbr",frozen.rows()).isEmpty());
        assertEquals(13,contract.businessColumns().size());
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("TIMESTAMP(\"date\")"));

        var empty=new SourceCollector(archive).collect(request,(provider,params) -> new PageExecutor.Page(List.of(),null,true,"fixture-empty-v1"));
        assertTrue(empty.completeCoverage());assertEquals(BusinessState.VERIFYING,empty.state());
        assertTrue(SourceQuality.failures("us_tbr",List.of()).isEmpty());
        var allNull=row("us_tbr","ignored");
        for(String field:List.of("w4_bd","w4_ce","w8_bd","w8_ce","w13_bd","w13_ce","w17_bd","w17_ce","w26_bd","w26_ce","w52_bd","w52_ce")) allNull.put(field,Json.MAPPER.nullNode());
        var normalized=contract.normalize(allNull,LocalDate.of(2026,9,28),Set.of());
        assertTrue(SourceQuality.failures("us_tbr",List.of(normalized)).contains("completeness:us_tbr-rates"));
    }
    @Test void futuresDailyIsScopedToOneContractAndGeneratesLegacyUpdateTime() throws Exception {
        var contract=SourceContract.load("fut_daily");var request=request("fut_daily",Set.of("IF2610.CFX"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fut_daily",provider.endpoint());
            assertEquals(Map.of("trade_date","20260928","ts_code","IF2610.CFX"),params);
            assertFalse(provider.fields().contains("update_time"));assertTrue(provider.fields().contains("oi_chg"));
            return new PageExecutor.Page(List.of(row("fut_daily","IF2610.CFX")),null,true,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertTrue(collected.completeCoverage());assertEquals("2026-09-28",frozen.rows().getFirst().get("trade_date"));
        assertTrue(SourceQuality.failures("fut_daily",frozen.rows()).isEmpty());
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
        assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("TIMESTAMP(\"trade_date\")"));
        assertThrows(IllegalArgumentException.class,() -> request("fut_daily",Set.of("000001.SZ")));
    }
    @Test void futuresSettlementKeepsFeesAndMarginRatesAndGeneratesLegacyUpdateTime() throws Exception {
        var contract=SourceContract.load("fut_settle");var request=request("fut_settle",Set.of("IF2610.CFX"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fut_settle",provider.endpoint());
            assertEquals(Map.of("trade_date","20260928","ts_code","IF2610.CFX"),params);
            assertFalse(provider.fields().contains("update_time"));
            assertTrue(provider.fields().containsAll(List.of("settle","trading_fee_rate","long_margin_rate","exchange")));
            var settlement=row("fut_settle","IF2610.CFX");settlement.put("exchange",Json.MAPPER.valueToTree("CFFEX"));
            return new PageExecutor.Page(List.of(settlement),null,true,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertTrue(collected.completeCoverage());assertEquals("CFFEX",frozen.rows().getFirst().get("exchange"));
        assertTrue(SourceQuality.failures("fut_settle",frozen.rows()).isEmpty());
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
        assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("\"exchange\" SYMBOL"));
        assertThrows(IllegalArgumentException.class,() -> request("fut_settle",Set.of("000001.SZ")));
    }
    @Test void futuresMappingValidatesContinuousAndMappedContractScope() throws Exception {
        var contract=SourceContract.load("fut_mapping");var request=request("fut_mapping",Set.of("IF.CFX"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fut_mapping",provider.endpoint());
            assertEquals(Map.of("trade_date","20260928","ts_code","IF.CFX"),params);
            assertEquals(List.of("ts_code","trade_date","mapping_ts_code"),provider.fields());
            return new PageExecutor.Page(List.of(row("fut_mapping","IF.CFX")),null,true,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertEquals("IF2610.CFX",frozen.rows().getFirst().get("mapping_ts_code"));
        assertTrue(SourceQuality.failures("fut_mapping",frozen.rows()).isEmpty());
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
        assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
        assertThrows(PageExecutor.Incomplete.class,() -> new SourceCollector(archive).collect(request,(provider,params) -> {
            var invalid=new LinkedHashMap<>(row("fut_mapping","IF.CFX"));invalid.put("mapping_ts_code",Json.MAPPER.valueToTree("IF.CFX"));
            return new PageExecutor.Page(List.of(invalid),null,true,"fixture-v1");
        }));
    }
    @Test void futuresLimitIsScopedToOneMonthContractAndPreservesLegacyMetadata() throws Exception {
        var contract=SourceContract.load("ft_limit");var request=request("ft_limit",Set.of("IF2610.CFX"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("ft_limit",provider.endpoint());assertEquals(Map.of("trade_date","20260928","ts_code","IF2610.CFX"),params);
            assertEquals(List.of("trade_date","ts_code","name","up_limit","down_limit","m_ratio","cont","exchange"),provider.fields());
            return new PageExecutor.Page(List.of(row("ft_limit","IF2610.CFX")),null,true,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertTrue(collected.completeCoverage());assertTrue(SourceQuality.failures("ft_limit",frozen.rows()).isEmpty());
        assertEquals("IF",frozen.rows().getFirst().get("cont"));assertEquals("CFFEX",frozen.rows().getFirst().get("exchange"));
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
        assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
        var invalid=new LinkedHashMap<>(frozen.rows().getFirst());invalid.put("up_limit",-1.0);
        assertTrue(SourceQuality.failures("ft_limit",List.of(invalid)).contains("domain:up_limit"));
    }
    @Test void futuresHoldingIsScopedToOneProductAndPreservesNullableRankFieldsAndBusinessKey() throws Exception {
        var contract=SourceContract.load("fut_holding");var request=request("fut_holding",Set.of("IF"));
        var result=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fut_holding",provider.endpoint());
            assertEquals(Map.of("trade_date","20260928","symbol","IF","exchange","CFFEX"),params);
            assertEquals(List.of("trade_date","symbol","broker"),provider.businessKey());
            assertFalse(provider.fields().contains("update_time"));
            return new PageExecutor.Page(List.of(row("fut_holding","IF")),null,true,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(result.fingerprint());
        assertTrue(result.completeCoverage());assertEquals(Set.of("IF"),contract.observedCodes(frozen.rows()));
        assertTrue(SourceQuality.failures("fut_holding",frozen.rows()).isEmpty());
        assertEquals(-2L,((Number)frozen.rows().getFirst().get("vol_chg")).longValue());
        assertEquals("2026-09-29T00:00:00Z",contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z")).get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("DEDUP UPSERT KEYS(\"trade_date\",\"symbol\",\"broker\")"));
        var nullable=row("fut_holding","IF");nullable.put("vol",Json.MAPPER.nullNode());
        assertTrue(SourceQuality.failures("fut_holding",List.of(contract.normalize(nullable,request.logicalDate(),request.expectedCodes()))).isEmpty());
        var invalid=contract.normalize(row("fut_holding","IF"),request.logicalDate(),request.expectedCodes());
        var negative=new LinkedHashMap<>(invalid);negative.put("long_hld",-1L);
        assertTrue(SourceQuality.failures("fut_holding",List.of(negative)).contains("domain:long_hld"));
        assertThrows(IllegalArgumentException.class,() -> request("fut_holding",Set.of("IF2610.CFX")));
    }
    @Test void futuresBasicUsesExchangeSnapshotAndLegacyEpochTimestampKey() throws Exception {
        var contract=SourceContract.load("fut_basic");var request=request("fut_basic",Set.of("CFFEX"));
        var result=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fut_basic",provider.endpoint());assertEquals(Map.of("exchange","CFFEX","fut_type","1"),params);
            assertEquals(PageContract.Paging.NONE,provider.paging());assertEquals(10000,provider.sourceRowCap());
            assertEquals(List.of("ts_code"),provider.businessKey());assertFalse(provider.fields().contains("trade_time_desc"));
            var basic=row("fut_basic","CFFEX");basic.put("trade_time_desc",Json.MAPPER.nullNode());
            return new PageExecutor.Page(List.of(basic),null,false,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(result.fingerprint());
        assertTrue(result.completeCoverage());assertEquals(1,result.rows());assertEquals(Set.of("CFFEX"),contract.observedCodes(frozen.rows()));
        assertTrue(SourceQuality.failures("fut_basic",frozen.rows()).isEmpty());
        assertFalse(frozen.rows().getFirst().containsKey("timestamp"));
        Instant now=Instant.parse("2026-09-29T10:30:00Z");
        var run=new RunRequest("fut-basic-run","source_fut_basic",request.logicalDate(),request.logicalDate(),request.logicalDate(),contract.version(),
                "0",null,null,result.fingerprint(),"fixture-calendar-v1","Asia/Shanghai",now,now,result.scopeIdentity());
        assertEquals(BusinessState.BLOCKED,new SourceProductRunner(null,new SourceCollector(archive),archive,null,"").execute(run,"fut_basic").state());
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-30T00:00:00Z"));
        assertEquals("1970-01-01T00:00:00Z",physical.get("timestamp"));assertEquals("2026-09-30T00:00:00Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("PARTITION BY YEAR WAL DEDUP UPSERT KEYS(\"ts_code\",\"timestamp\")"));
        var capRows=new ArrayList<Map<String,JsonNode>>();for(int i=0;i<10000;i++){var item=row("fut_basic","CFFEX");item.put("ts_code",Json.MAPPER.valueToTree(String.format(Locale.ROOT,"IF%04d.CFX",i)));capRows.add(item);}
        assertThrows(PageExecutor.Truncated.class,() -> new PageExecutor().execute(contract.providerContract(),Map.of("exchange","CFFEX","fut_type","1"),
                params -> new PageExecutor.Page(capRows,null,false,"fixture-v1"),(page,receipt)->{},row->{},()->false));
        assertThrows(IllegalArgumentException.class,() -> request("fut_basic",Set.of("000001.SZ")));
    }
    @Test void etfBasicUsesBoundedStaticMarketSnapshotAndLegacyEpochTimestampKey() throws Exception {
        var contract=SourceContract.load("etf_basic");var request=request("etf_basic",Set.of("E"));
        var result=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fund_basic",provider.endpoint());assertEquals(Map.of("market","E"),params);
            assertEquals(PageContract.Paging.NONE,provider.paging());assertEquals(15000,provider.sourceRowCap());
            assertEquals(List.of("ts_code"),provider.businessKey());assertFalse(provider.fields().contains("timestamp"));assertFalse(provider.fields().contains("update_time"));
            return new PageExecutor.Page(List.of(row("etf_basic","E")),null,false,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(result.fingerprint());
        assertTrue(result.completeCoverage());assertEquals(1,result.rows());assertEquals(Set.of("E"),contract.observedCodes(frozen.rows()));
        assertTrue(SourceQuality.failures("etf_basic",frozen.rows()).isEmpty());assertFalse(frozen.rows().getFirst().containsKey("timestamp"));
        Instant now=Instant.parse("2026-09-30T00:00:00Z");
        var run=new RunRequest("etf-basic-run","source_etf_basic",request.logicalDate(),request.logicalDate(),request.logicalDate(),contract.version(),
                "0",null,null,result.fingerprint(),"fixture-calendar-v1","Asia/Shanghai",now,now,result.scopeIdentity());
        assertEquals(BusinessState.BLOCKED,new SourceProductRunner(null,new SourceCollector(archive),archive,null,"").execute(run,"etf_basic").state());
        var physical=contract.physicalRow(frozen.rows().getFirst(),now);
        assertEquals("1970-01-01T00:00:00Z",physical.get("timestamp"));assertEquals("2026-09-30T00:00:00Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("PARTITION BY YEAR WAL DEDUP UPSERT KEYS(\"ts_code\",\"timestamp\")"));
        var capRows=new ArrayList<Map<String,JsonNode>>();for(int i=0;i<15000;i++){var item=row("etf_basic","E");item.put("ts_code",Json.MAPPER.valueToTree(String.format(Locale.ROOT,"%06d.SH",i)));capRows.add(item);}
        assertThrows(PageExecutor.Truncated.class,() -> new PageExecutor().execute(contract.providerContract(),Map.of("market","E"),
                params -> new PageExecutor.Page(capRows,null,false,"fixture-v1"),(page,receipt)->{},row->{},()->false));
        assertThrows(IllegalArgumentException.class,() -> request("etf_basic",Set.of("510300.SH")));
    }
    @Test void thsIndexUsesOneUnfilteredBoundedStaticCatalogAndGeneratesPublicationTime() throws Exception {
        var contract=SourceContract.load("ths_index");var request=request("ths_index",Set.of());
        var collector=new SourceCollector(archive);
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("ths_index",provider.endpoint());assertTrue(params.isEmpty());
            assertEquals(List.of("ts_code","name","count","exchange","list_date","type"),provider.fields());
            assertEquals(List.of("ts_code"),provider.businessKey());assertEquals(5000,provider.sourceRowCap());
            return new PageExecutor.Page(List.of(row("ths_index","885001.TI"),row("ths_index","885002.TI")),null,false,"ths-fixture-v1");
        });
        var frozen=collector.read(result.fingerprint());assertTrue(result.completeCoverage());assertEquals(2,result.rows());
        assertTrue(SourceQuality.failures("ths_index",frozen.rows()).isEmpty());assertFalse(frozen.rows().getFirst().containsKey("update_time"));
        var published=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-30T00:00:00.123456Z"));
        assertEquals("2026-09-30T00:00:00.123456Z",published.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("TIMESTAMP(\"update_time\") PARTITION BY MONTH WAL DEDUP UPSERT KEYS(\"ts_code\",\"update_time\")"));
        var capRows=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<5000;i++) { var capRow=row("ths_index","885000.TI");capRow.put("ts_code",Json.MAPPER.valueToTree(String.format("%06d.TI",i)));capRows.add(capRow); }
        assertThrows(PageExecutor.Truncated.class,() -> collector.collect(request,(provider,params) ->
                new PageExecutor.Page(capRows,null,false,"ths-cap-fixture-v1")));
    }
    @Test void etfShareUsesOneBoundedTradeDateSnapshotAndPreservesLegacyTypedSchema() throws Exception {
        var contract=SourceContract.load("etf_share");var request=request("etf_share",Set.of());var collector=new SourceCollector(archive);
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("fund_share",provider.endpoint());assertEquals(Map.of("trade_date","20260928"),params);
            assertEquals(PageContract.Paging.NONE,provider.paging());assertEquals(2000,provider.sourceRowCap());
            assertEquals(List.of("ts_code","trade_date"),provider.businessKey());
            assertEquals(List.of("ts_code","trade_date","fd_share"),provider.fields());
            return new PageExecutor.Page(List.of(row("etf_share","510300.SH")),null,false,"etf-share-fixture-v1");
        });
        var frozen=collector.read(result.fingerprint());var value=frozen.rows().getFirst();
        assertTrue(result.completeCoverage());assertEquals(1,result.rows());assertEquals("2026-09-28",value.get("timestamp"));
        assertNull(value.get("fund_type"));assertNull(value.get("market"));assertTrue(SourceQuality.failures("etf_share",frozen.rows()).isEmpty());
        var physical=contract.physicalRow(value,Instant.parse("2026-09-29T01:02:03.123456Z"));
        assertEquals("2026-09-29T01:02:03.123456Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("PARTITION BY YEAR WAL DEDUP UPSERT KEYS(\"ts_code\",\"timestamp\")"));
        var capRows=new ArrayList<Map<String,JsonNode>>();for(int i=0;i<2000;i++) {var capRow=row("etf_share","510300.SH");capRow.put("ts_code",Json.MAPPER.valueToTree(String.format(Locale.ROOT,"%06d.SH",i)));capRows.add(capRow);}
        assertThrows(PageExecutor.Truncated.class,() -> new PageExecutor().execute(contract.providerContract(),Map.of("trade_date","20260928"),
                params -> new PageExecutor.Page(capRows,null,false,"etf-share-cap-fixture-v1"),(page,receipt)->{},row->{},()->false));
        var empty=collector.collect(request,(provider,params) -> new PageExecutor.Page(List.of(),null,false,"etf-share-empty-fixture-v1"));
        var emptyRows=collector.read(empty.fingerprint()).rows();
        assertFalse(empty.completeCoverage());assertFalse(SourceQuality.failures("etf_share",emptyRows).isEmpty());
    }
    @Test void disclosureDateUsesExactQuarterEndAndAnnouncementTimestampKey() throws Exception {
        var contract=SourceContract.load("disclosure_date");var request=request("disclosure_date",Set.of());
        assertDoesNotThrow(() -> contract.normalize(row("disclosure_date","ignored"),request.logicalDate(),Set.of()));
        var result=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("disclosure_date",provider.endpoint());assertEquals(Map.of("end_date","20260930"),params);
            assertEquals(PageContract.Paging.NONE,provider.paging());assertEquals(6000,provider.sourceRowCap());
            assertEquals(List.of("ts_code","ann_date","end_date"),provider.businessKey());assertFalse(provider.fields().contains("update_time"));
            return new PageExecutor.Page(List.of(row("disclosure_date","ignored")),null,false,"fixture-v1");
        });
        var frozen=new SourceCollector(archive).read(result.fingerprint());
        assertTrue(result.completeCoverage());assertEquals(1,result.rows());assertTrue(SourceQuality.failures("disclosure_date",frozen.rows()).isEmpty());
        assertEquals("2026-10-15",frozen.rows().getFirst().get("ann_date"));assertEquals("20260930",frozen.rows().getFirst().get("end_date"));
        Instant now=Instant.parse("2026-09-30T12:00:00Z");var physical=contract.physicalRow(frozen.rows().getFirst(),now);
        assertEquals("2026-10-15",physical.get("ann_date"));assertEquals("2026-09-30T12:00:00Z",physical.get("update_time"));
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("TIMESTAMP(\"ann_date\") PARTITION BY YEAR WAL DEDUP UPSERT KEYS(\"ts_code\",\"ann_date\",\"end_date\")"));
        var invalid=new LinkedHashMap<>(frozen.rows().getFirst());invalid.put("end_date","20260630");
        assertThrows(IllegalArgumentException.class,() -> contract.validateNormalized(invalid,request.logicalDate(),Set.of()));
        var empty=new SourceCollector(archive).collect(request,(provider,params) -> new PageExecutor.Page(List.of(),null,false,"fixture-v1"));
        assertTrue(empty.completeCoverage());assertEquals(0,empty.rows());assertEquals(BusinessState.VERIFYING,empty.state());
        assertFalse(contract.covers(List.of(),Set.of("000001.SZ")));
    }
    @Test void shareFloatCollectsSparseUnlockEventsForOneExplicitDate() throws Exception {
        var contract=SourceContract.load("share_float");var request=request("share_float",Set.of("000001.SZ"));
        var event=row("share_float","000001.SZ");event.put("ann_date",Json.MAPPER.valueToTree("20260928"));
        event.put("float_date",Json.MAPPER.valueToTree("20261016"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("share_float",provider.endpoint());assertEquals(Map.of("ann_date","20260928","ts_code","000001.SZ"),params);
            assertTrue(provider.fields().containsAll(List.of("ts_code","ann_date","float_date","float_share","float_ratio","holder_name","share_type")));
            return new PageExecutor.Page(List.of(event),null,true,"fixture-v1");
        });
        assertTrue(collected.completeCoverage());assertEquals(1,collected.rows());
        var frozen=new SourceCollector(archive).read(collected.fingerprint());
        assertEquals("20260928",frozen.rows().getFirst().get("ann_date"));
        assertEquals("2026-10-16",frozen.rows().getFirst().get("float_date"));
        assertTrue(SourceQuality.failures("share_float",frozen.rows()).isEmpty());
        assertTrue(contract.createTableSql("jdb_test_fixture").contains("TIMESTAMP(\"float_date\")"));
        Instant now=Instant.parse("2026-09-29T10:30:00Z");
        var run=new RunRequest("share-float-run","source_share_float",request.logicalDate(),request.logicalDate(),request.logicalDate(),contract.version(),
                "0",null,null,collected.fingerprint(),"fixture-calendar-v1","Asia/Shanghai",now,now,collected.scopeIdentity());
        assertEquals(BusinessState.BLOCKED,new SourceProductRunner(null,new SourceCollector(archive),archive,null,"").execute(run,"share_float").state());
        var empty=new SourceCollector(archive).collect(request("share_float",Set.of("000001.SZ")),(provider,params) ->
                new PageExecutor.Page(List.of(),null,false,"fixture-v1"));
        assertTrue(empty.completeCoverage());assertEquals(0,empty.rows());assertEquals(BusinessState.VERIFYING,empty.state());
        var wrongAnchor=row("share_float","000001.SZ");wrongAnchor.put("ann_date",Json.MAPPER.valueToTree("20260927"));
        assertThrows(PageExecutor.Incomplete.class,() -> new SourceCollector(archive).collect(request,(provider,params) ->
                new PageExecutor.Page(List.of(wrongAnchor),null,true,"fixture-v1")));
    }
    @Test void finaAuditPreservesAnnouncementRevisionKeyAndReportPeriod() throws Exception {
        var contract=SourceContract.load("fina_audit");var request=request("fina_audit",Set.of("600000.SH"));
        var collected=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("fina_audit",provider.endpoint());assertEquals(Map.of("ann_date","20260928","ts_code","600000.SH"),params);
            return new PageExecutor.Page(List.of(row("fina_audit","600000.SH")),null,true,"fixture-v1");
        });
        assertTrue(collected.completeCoverage());assertEquals(1,collected.rows());
        var frozen=new SourceCollector(archive).read(collected.fingerprint());assertEquals("20251231",frozen.rows().getFirst().get("end_date"));
        assertTrue(SourceQuality.failures("fina_audit",frozen.rows()).isEmpty());
        assertEquals(List.of("ts_code","ann_date","end_date"),contract.columns().stream().filter(SourceContract.Column::key).map(SourceContract.Column::target).toList());
        var wrongPeriod=row("fina_audit","600000.SH");wrongPeriod.put("end_date",Json.MAPPER.valueToTree("20271031"));
        assertThrows(PageExecutor.Incomplete.class,() -> new SourceCollector(archive).collect(request,(provider,params) ->
                new PageExecutor.Page(List.of(wrongPeriod),null,true,"fixture-v1")));
    }
    @Test void monthlyLprAllowsOnlyACompletedEmptyDateProbe() throws Exception {
        var collector=new SourceCollector(archive);var request=request("shibor_lpr",Set.of());
        var result=collector.collect(request,(provider,params) -> {
            assertEquals(Map.of("start_date","20260928","end_date","20260928"),params);
            return new PageExecutor.Page(List.of(),null,false,"fixture-v1");
        });
        assertTrue(result.completeCoverage());assertEquals(0,result.rows());assertEquals(BusinessState.VERIFYING,result.state());
        assertTrue(SourceQuality.failures("shibor_lpr",List.of()).isEmpty());
    }
    @Test void cpiUsesOneObservationMonthAndMapsMonthTimestampToLegacyFirstOfMonth() throws Exception {
        var contract=SourceContract.load("cn_cpi");var collector=new SourceCollector(archive);
        var request=new SourceCollector.Request("cn_cpi",LocalDate.of(2026,9,1),Set.of(),"cpi-month-v1",null);
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("cn_cpi",provider.endpoint());assertEquals(Map.of("m","202609"),params);
            assertEquals("202609",row("cn_cpi","ignored").get("month").asText());
            return new PageExecutor.Page(List.of(row("cn_cpi","ignored")),null,false,"cpi-fixture-v1");
        });
        assertEquals(BusinessState.VERIFYING,result.state());assertTrue(result.completeCoverage());
        var frozen=collector.read(result.fingerprint());assertEquals("2026-09-01",frozen.rows().getFirst().get("month"));
        assertTrue(SourceQuality.failures("cn_cpi",frozen.rows()).isEmpty());
        assertTrue(new String(QuestDbTablePort.encode(contract,"jdb_test_fixture",frozen.rows()),java.nio.charset.StandardCharsets.UTF_8)
                .contains("nt_yoy=102.25"));
        assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request("cn_cpi",LocalDate.of(2026,9,2),Set.of(),"cpi-month-v1",null));
        var empty=collector.collect(request,(provider,params) -> new PageExecutor.Page(List.of(),null,false,"cpi-empty-fixture-v1"));
        assertEquals(BusinessState.VERIFYING,empty.state());assertEquals(0,empty.rows());
    }
    @Test void monthlyMacroSourcesRetainTheirFullLegacySchemas() throws Exception {
        var collector=new SourceCollector(archive);
        for(String dataset:List.of("cn_ppi","cn_pmi","cn_m")) {
            var contract=SourceContract.load(dataset);var request=new SourceCollector.Request(dataset,LocalDate.of(2026,9,1),Set.of(),"macro-month-v1",null);
            var result=collector.collect(request,(provider,params) -> {
                assertEquals(dataset,provider.endpoint());assertEquals(Map.of("m","202609"),params);
                assertTrue(provider.fields().contains(switch(dataset) {case "cn_ppi" -> "ppi_yoy";case "cn_pmi" -> "pmi010000";default -> "m2_yoy";}));
                return new PageExecutor.Page(List.of(row(dataset,"ignored")),null,false,"macro-month-fixture-v1");
            });
            assertEquals(BusinessState.VERIFYING,result.state());assertTrue(result.completeCoverage());
            var frozen=collector.read(result.fingerprint());assertEquals("2026-09-01",frozen.rows().getFirst().get("month"));
            assertEquals(contract.columns().size(),frozen.rows().getFirst().size());
            assertTrue(SourceQuality.failures(dataset,frozen.rows()).isEmpty());
            assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request(dataset,LocalDate.of(2026,9,2),Set.of(),"macro-month-v1",null));
        }
    }
    @Test void monthlyRangesFreezeEveryMonthAndRejectGapsAsIncompleteCoverage() throws Exception {
        var collector=new SourceCollector(archive);var contract=SourceContract.load("cn_cpi");
        var request=new SourceCollector.Request("cn_cpi",LocalDate.of(2026,9,1),Set.of(),"cpi-range-v1",null,
                LocalDate.of(2026,7,1),LocalDate.of(2026,9,1));
        var result=collector.collect(request,(provider,params) -> {
            assertEquals(Map.of("start_m","202607","end_m","202609"),params);
            var rows=new ArrayList<Map<String,JsonNode>>();
            for(String month:List.of("202607","202608","202609")) {
                var row=row("cn_cpi","ignored");row.put("month",Json.MAPPER.valueToTree(month));rows.add(row);
            }
            return new PageExecutor.Page(rows,null,false,"cpi-range-fixture-v1");
        });
        assertEquals(BusinessState.VERIFYING,result.state());assertTrue(result.completeCoverage());assertEquals(3,result.rows());
        var frozen=collector.read(result.fingerprint());assertEquals(LocalDate.of(2026,7,1),frozen.rangeStart());
        assertEquals(List.of("2026-07-01","2026-08-01","2026-09-01"),frozen.rows().stream().map(r -> r.get("month")).toList());
        var gap=collector.collect(request,(provider,params) -> {
            var rows=new ArrayList<Map<String,JsonNode>>();
            for(String month:List.of("202607","202609")) { var row=row("cn_cpi","ignored");row.put("month",Json.MAPPER.valueToTree(month));rows.add(row); }
            return new PageExecutor.Page(rows,null,false,"cpi-gap-fixture-v1");
        });
        assertEquals(BusinessState.PARTIAL,gap.state());assertFalse(gap.completeCoverage());
        assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request("cn_cpi",LocalDate.of(2026,9,1),Set.of(),"cpi-range-v1",null,
                LocalDate.of(2026,7,2),LocalDate.of(2026,9,1)));
    }
    @Test void schemaOneFrozenSingleDateArchivesRemainReadable() throws Exception {
        String legacy="""
                {"schemaVersion":1,"dataset":"shibor","definitionVersion":"shibor-v1","logicalDate":"2026-09-28",
                 "expectedCodes":[],"universeVersion":"legacy-single-date-v1","sourceDocumentation":"fixture",
                 "rows":[],"sourcePages":1,"completeCoverage":true}
                """;
        var frozen=Json.MAPPER.readValue(legacy,SourceCollector.Frozen.class);
        assertEquals(1,frozen.schemaVersion());assertEquals(frozen.logicalDate(),frozen.rangeStart());assertEquals(frozen.logicalDate(),frozen.rangeEnd());
    }
    @Test void gdpQuarterIdentityMapsToExactLegacyQuarterEndTimestamp() throws Exception {
        var contract=SourceContract.load("cn_gdp");var collector=new SourceCollector(archive);
        var request=new SourceCollector.Request("cn_gdp",LocalDate.of(2026,6,30),Set.of(),"gdp-quarter-v1",null);
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("cn_gdp",provider.endpoint());assertEquals(Map.of("q","2026Q2"),params);
            assertEquals(List.of("quarter"),provider.businessKey());assertFalse(provider.fields().contains("report_date"));
            return new PageExecutor.Page(List.of(row("cn_gdp","ignored")),null,false,"gdp-fixture-v1");
        });
        assertEquals(BusinessState.VERIFYING,result.state());assertTrue(result.completeCoverage());
        var frozen=collector.read(result.fingerprint());var normalized=frozen.rows().getFirst();
        assertEquals("2026Q2",normalized.get("quarter"));assertEquals("2026-06-30",normalized.get("report_date"));
        assertTrue(SourceQuality.failures("cn_gdp",frozen.rows()).isEmpty());
        assertTrue(new String(QuestDbTablePort.encode(contract,"jdb_test_fixture",frozen.rows()),java.nio.charset.StandardCharsets.UTF_8).contains("gdp_yoy=12.25"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,(provider,params) -> {
            var mismatch=new LinkedHashMap<>(row("cn_gdp","ignored"));mismatch.put("quarter",Json.MAPPER.valueToTree("2026Q1"));
            return new PageExecutor.Page(List.of(mismatch),null,false,"wrong-quarter-fixture");
        }));
        assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request("cn_gdp",LocalDate.of(2026,6,29),Set.of(),"gdp-quarter-v1",null));
    }
    @Test void gdpHistoricalRangeRequiresEveryQuarterAndUsesQuarterEndKeys() throws Exception {
        var collector=new SourceCollector(archive);var start=LocalDate.of(2025,12,31);var end=LocalDate.of(2026,6,30);
        var request=new SourceCollector.Request("cn_gdp",end,Set.of(),"gdp-quarter-range-v1",null,start,end);
        var result=collector.collect(request,(provider,params) -> {
            assertEquals(Map.of("start_q","2025Q4","end_q","2026Q2"),params);
            var rows=new ArrayList<Map<String,JsonNode>>();for(String quarter:List.of("2026Q2","2026Q1","2025Q4")) {
                var row=new LinkedHashMap<>(row("cn_gdp","ignored"));row.put("quarter",Json.MAPPER.valueToTree(quarter));rows.add(row);
            }
            return new PageExecutor.Page(rows,null,false,"gdp-quarter-range-fixture-v1");
        });
        assertEquals(BusinessState.VERIFYING,result.state());assertTrue(result.completeCoverage());assertEquals(3,result.rows());
        var frozen=collector.read(result.fingerprint());assertTrue(SourceCollector.coversQuarters(frozen.rows(),start,end));
        assertEquals(List.of("2025-12-31","2026-03-31","2026-06-30"),frozen.rows().stream().map(r->r.get("report_date")).toList());
        var gap=collector.collect(request,(provider,params)->new PageExecutor.Page(
                List.of(rowQuarter("2025Q4"),rowQuarter("2026Q2")),null,false,"gdp-gap-fixture-v1"));
        assertEquals(BusinessState.PARTIAL,gap.state());assertFalse(gap.completeCoverage());
        assertThrows(IllegalArgumentException.class,()->new SourceCollector.Request("cn_gdp",end,Set.of(),"gdp-quarter-range-v1",null,
                LocalDate.of(2026,1,1),end));
    }
    private static Map<String,JsonNode> rowQuarter(String quarter) {
        var row=new LinkedHashMap<>(row("cn_gdp","ignored"));row.put("quarter",Json.MAPPER.valueToTree(quarter));return row;
    }
    @Test void dividendUsesAnnouncementDayScopeAndPreservesLegacyNullEndDateRule() throws Exception {
        var contract=SourceContract.load("dividend");var request=request("dividend",Set.of("000001.SZ"));
        var result=new SourceCollector(archive).collect(request,(provider,params) -> {
            assertEquals("dividend",provider.endpoint());assertEquals("20260928",params.get("ann_date"));
            assertEquals("000001.SZ",params.get("ts_code"));assertFalse(params.containsKey("limit"));
            return new PageExecutor.Page(List.of(row("dividend","000001.SZ")),null,true,"fixture-v1");
        });
        assertEquals(BusinessState.VERIFYING,result.state());
        var frozen=new SourceCollector(archive).read(result.fingerprint());
        assertEquals("unknown",frozen.rows().getFirst().get("end_date"));
        assertEquals("2026-09-28",frozen.rows().getFirst().get("ann_date"));
        assertTrue(SourceQuality.failures("dividend",frozen.rows()).isEmpty());
        var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
        assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
        assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request("dividend",LocalDate.of(2026,9,28),
                Set.of("000001.SZ","000002.SZ"),"universe-v1",null));
        assertThrows(com.zoutrankil.questdbwithdata.service.PageExecutor.Incomplete.class,() -> new SourceCollector(archive).collect(request,(provider,params) -> {
            var first=row("dividend","000001.SZ");first.put("end_date",Json.MAPPER.valueToTree("20251231"));
            var second=row("dividend","000001.SZ");second.put("end_date",Json.MAPPER.valueToTree("20251231"));
            second.put("div_proc",Json.MAPPER.valueToTree("实施"));
            return new PageExecutor.Page(List.of(first,second),null,true,"fixture-v1");
        }));
    }
    private static List<Map<String,JsonNode>> bondRows() {
        var rows=new ArrayList<Map<String,JsonNode>>();
        for(String name:List.of("中债国债收益率曲线","中债中短期票据收益率曲线(AAA)","中债商业银行普通债收益率曲线(AAA)")) {
            var row=new LinkedHashMap<String,JsonNode>();row.put("raw_trade_date",Json.MAPPER.valueToTree("2026-09-28"));row.put("raw_curve_name",Json.MAPPER.valueToTree(name));
            for(String tenor:List.of("3m","6m","1y","3y","5y","7y","10y","30y"))row.put("raw_"+tenor,Json.MAPPER.valueToTree(2.25));rows.add(row);
        }
        return rows;
    }
    @Test void stNamesUseEffectiveDatesAndCollapseToOnePositiveSnapshot() throws Exception {
        var collector=new SourceCollector(archive);var request=request("stk_st_daily",Set.of("000001.SZ"));
        var active=row("stk_st_daily","000001.SZ");
        var future=row("stk_st_daily","000001.SZ");future.put("start_date",Json.MAPPER.valueToTree("20261001"));
        var ended=row("stk_st_daily","000001.SZ");ended.put("start_date",Json.MAPPER.valueToTree("20260801"));ended.put("end_date",Json.MAPPER.valueToTree("20260927"));
        var nonSt=row("stk_st_daily","000001.SZ");nonSt.put("start_date",Json.MAPPER.valueToTree("20260701"));nonSt.put("name",Json.MAPPER.valueToTree("示例股份"));
        var result=collector.collect(request,p -> new PageExecutor.Page(List.of(active,future,ended,nonSt),null,true,null));
        assertEquals(1,result.rows());assertEquals(BusinessState.VERIFYING,result.state());
        var normalized=collector.read(result.fingerprint()).rows().getFirst();
        assertEquals("000001.SZ",normalized.get("ts_code"));assertEquals(1L,((Number)normalized.get("is_st")).longValue());
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(Map.of("ts_code",Json.MAPPER.valueToTree("000001.SZ")),new HashMap<>()),null,true,null)));
    }
    static SourceCollector.Request request(String dataset,Set<String> codes) {
        if(dataset.equals("exchange_calendar") && codes.isEmpty()) codes=Set.of("SSE");
        if(Set.of("disclosure_date","ths_index","etf_share").contains(dataset)) codes=Set.of();
        if(dataset.equals("fut_holding") && codes.isEmpty()) codes=Set.of("IF");
        if(dataset.equals("fut_basic") && codes.isEmpty()) codes=Set.of("CFFEX");
        if(Set.of("fut_daily","fut_settle","ft_limit").contains(dataset) && codes.isEmpty()) codes=Set.of("IF2610.CFX");
        if(dataset.equals("fut_mapping") && codes.isEmpty()) codes=Set.of("IF.CFX");
        var date=dataset.equals("fina_mainbz")||dataset.equals("cn_gdp")?LocalDate.of(2026,6,30):dataset.equals("disclosure_date")?LocalDate.of(2026,9,30):SourceContract.MONTHLY_AGGREGATES.contains(dataset)?LocalDate.of(2026,9,1):LocalDate.of(2026,9,28);
        return new SourceCollector.Request(dataset,date,codes,"frozen-universe-v1",codes.size()==1?codes.iterator().next():null);
    }
    @Test void allRegisteredMappingsAreTypedAndEtfKeepsItsDateColumn() throws Exception {
        var collector=new SourceCollector(archive);
        for(var dataset:SourceContract.SUPPORTED) {
            var fixture=dataset.equals("cn_bond_yield_curve")?bondRows():List.of(row(dataset,dataset.equals("exchange_calendar")?"SSE":dataset.equals("fut_basic")?"CFFEX":dataset.equals("etf_basic")?"E":Set.of("fut_daily","fut_settle","ft_limit").contains(dataset)?"IF2610.CFX":dataset.equals("fut_mapping")?"IF.CFX":dataset.equals("fut_holding")?"IF":"000001.SZ"));
            var result=collector.collect(request(dataset,dataset.equals("exchange_calendar")?Set.of("SSE"):
                    dataset.equals("fut_basic")?Set.of("CFFEX"):dataset.equals("etf_basic")?Set.of("E"):SourceContract.load(dataset).isMarketAggregate()?Set.of():Set.of("fut_daily","fut_settle","ft_limit").contains(dataset)?Set.of("IF2610.CFX"):dataset.equals("fut_mapping")?Set.of("IF.CFX"):dataset.equals("fut_holding")?Set.of("IF"):Set.of("000001.SZ")),params -> {
                var sourceRows=fixture;
                if(dataset.equals("fina_mainbz")) {
                    var main=row(dataset,"000001.SZ");String type=(String)params.get("type");
                    main.put("bz_code",Json.MAPPER.valueToTree(type));main.put("bz_item",Json.MAPPER.valueToTree("测试主营-"+type));
                    sourceRows=List.of(main);
                }
                return new PageExecutor.Page(sourceRows,null,dataset.equals("stk_st_daily"),null);
            });
            assertEquals(BusinessState.VERIFYING,result.state(),dataset+" rows="+result.rows()+" coverage="+result.completeCoverage());
            var frozen=collector.read(result.fingerprint());
            var contract=SourceContract.load(dataset); if(!Set.of("fut_basic","etf_basic","disclosure_date","ths_index").contains(dataset)) assertEquals(dataset.equals("fina_mainbz")||dataset.equals("cn_gdp")?"2026-06-30":SourceContract.MONTHLY_AGGREGATES.contains(dataset)?"2026-09-01":dataset.equals("share_float")?"2026-10-16":"2026-09-28",frozen.rows().getFirst().get(contract.timestampColumn()));
            if(dataset.equals("etf_daily")) assertFalse(frozen.rows().getFirst().containsKey("trade_date"));
            byte[] ilp=QuestDbTablePort.encode(contract,"jdb_test_fixture",frozen.rows().stream().map(r -> contract.physicalRow(r,Instant.parse("2026-09-29T00:00:00Z"))).toList());
            String field=switch(dataset) { case "exchange_calendar" -> "is_open"; case "fina_mainbz" -> "bz_sales"; case "fina_audit" -> "audit_fees"; case "dividend" -> "cash_div"; case "share_float" -> "float_share"; case "shibor" -> "on"; case "shibor_lpr" -> "1y"; case "hibor" -> "on"; case "us_tbr" -> "w4_bd"; case "cn_cpi" -> "nt_yoy"; case "cn_ppi" -> "ppi_yoy"; case "cn_pmi" -> "pmi010000"; case "cn_m" -> "m2_yoy"; case "cn_gdp" -> "gdp_yoy"; case "etf_portfolio" -> "mkv"; case "etf_basic" -> "issue_amount"; case "etf_share" -> "fd_share"; case "moneyflow_hsgt" -> "south_money"; case "margin_detail" -> "rzye"; case "moneyflow" -> "buy_sm_amount"; case "etf_adj" -> "adj_factor"; case "stk_limit", "ft_limit" -> "up_limit"; case "cn_bond_yield_curve" -> "yield_value"; case "cyq_perf" -> "winner_rate"; case "index_daily_basic" -> "total_mv"; case "fut_settle" -> "settle"; case "fut_mapping" -> "mapping_ts_code"; case "fut_holding" -> "long_hld"; case "fut_basic" -> "multiplier"; case "disclosure_date" -> "end_date"; case "ths_index" -> "count"; default -> "close"; };
            String expectedValue=dataset.equals("exchange_calendar")?"1i":dataset.equals("cn_bond_yield_curve")?"2.25":dataset.equals("dividend")?"0.1":dataset.equals("share_float")?"250000.0":dataset.equals("fina_audit")?"1000000.0":dataset.equals("shibor")?"1.25":dataset.equals("shibor_lpr")?"3.0":dataset.equals("hibor")?"2.75":dataset.equals("us_tbr")?"4.25":dataset.equals("cn_cpi")?"102.25":dataset.equals("etf_share")?"123456.75":dataset.equals("fut_mapping")?"IF2610.CFX":dataset.equals("ft_limit")?"4500.0":dataset.equals("fut_holding")?"80i":Set.of("fut_basic").contains(dataset)?"300.0":Set.of("etf_basic").contains(dataset)?"1.0":"12.25";
            assertTrue(new String(ilp,java.nio.charset.StandardCharsets.UTF_8).contains(dataset.equals("stk_suspend")?"is_suspended=1i":dataset.equals("stk_st_daily")?"is_st=1i":field+"="+(dataset.equals("disclosure_date")?"\"20260930\"":dataset.equals("ths_index")?"123i":expectedValue)));
            if(dataset.equals("index_daily_market")) {
                var physical=contract.physicalRow(frozen.rows().getFirst(),Instant.parse("2026-09-29T00:00:00Z"));
                assertEquals("2026-09-29T00:00:00Z",physical.get("update_time"));
                assertFalse(frozen.rows().getFirst().containsKey("update_time"));
            }
        }
    }
    @Test void finaMainbzPagesProductAndGeographyWithoutErasingTheLegacyKeyCollision() throws Exception {
        var contract=SourceContract.load("fina_mainbz");var request=request("fina_mainbz",Set.of("000001.SZ"));
        var collector=new SourceCollector(archive);var types=new ArrayList<String>();
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("fina_mainbz",provider.endpoint());assertEquals("20260630",params.get("period"));
            assertEquals("000001.SZ",params.get("ts_code"));assertEquals(100,params.get("limit"));
            String type=(String)params.get("type");types.add(type);var row=row("fina_mainbz","000001.SZ");
            row.put("bz_code",Json.MAPPER.valueToTree(type));row.put("bz_item",Json.MAPPER.valueToTree("segment-"+type));
            return new PageExecutor.Page(List.of(row),null,false,"mainbz-fixture-v1");
        });
        assertEquals(List.of("P","D"),types);assertEquals(2,result.rows());assertTrue(result.completeCoverage());
        assertEquals(BusinessState.VERIFYING,result.state());assertTrue(SourceQuality.failures("fina_mainbz",collector.read(result.fingerprint()).rows()).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> collector.collect(request,(provider,params) -> {
            var row=row("fina_mainbz","000001.SZ");row.put("bz_code",Json.MAPPER.valueToTree(params.get("type")));
            return new PageExecutor.Page(List.of(row),null,false,"mainbz-collision-fixture-v1");
        }));
        assertThrows(IllegalArgumentException.class,() -> new SourceCollector.Request("fina_mainbz",LocalDate.of(2026,6,30),
                Set.of("000001.SZ","000002.SZ"),"unbounded-mainbz-universe",null));
    }
    @Test void finaMainbzOffsetPagingIsRestartedIndependentlyForEachCategory() throws Exception {
        var request=request("fina_mainbz",Set.of("000001.SZ"));var collector=new SourceCollector(archive);
        var offsets=new ArrayList<String>();
        var result=collector.collect(request,(provider,params) -> {
            String type=(String)params.get("type");long offset=((Number)params.get("offset")).longValue();offsets.add(type+":"+offset);
            int count=offset==0?100:1;var rows=new ArrayList<Map<String,JsonNode>>();
            for(int i=0;i<count;i++) {
                int index=(int)offset+i;var row=row("fina_mainbz","000001.SZ");
                row.put("bz_code",Json.MAPPER.valueToTree(type));row.put("bz_item",Json.MAPPER.valueToTree(type+"-segment-"+index));rows.add(row);
            }
            return new PageExecutor.Page(rows,null,false,"mainbz-paging-fixture-v1");
        });
        assertEquals(List.of("P:0","P:100","D:0","D:100"),offsets);
        assertEquals(202,result.rows());assertEquals(4,result.pages());assertTrue(result.completeCoverage());
    }
    @Test void indexMarketAcceptsCanonicalCsiCodeButIndexBasicRemainsCoreUniverseScoped() {
        assertTrue(SourceContract.load("index_daily_market").validCode("000985.CSI"));
        assertFalse(SourceContract.load("index_daily_basic").validCode("000985.CSI"));
        assertTrue(SourceContract.load("index_daily_market").validCode("801010.SI"));
    }
    @Test void exchangeCalendarCollectsBothExchangesAsOneVerifiedDateScope() throws Exception {
        var contract=SourceContract.load("exchange_calendar");
        var expected=new TreeSet<>(List.of("SSE","SZSE"));
        var request=new SourceCollector.Request("exchange_calendar",LocalDate.of(2026,9,28),expected,"calendar-sse-szse-v1",null);
        var seen=new ArrayList<String>();var collector=new SourceCollector(archive);
        assertDoesNotThrow(() -> contract.normalize(row("exchange_calendar","SSE"),request.logicalDate(),expected));
        var result=collector.collect(request,(provider,params) -> {
            assertEquals("trade_cal",provider.endpoint());
            assertEquals("20260928",params.get("start_date"));assertEquals("20260928",params.get("end_date"));
            String exchange=(String)params.get("exchange");seen.add(exchange);
            var row=new LinkedHashMap<String,JsonNode>();row.put("exchange",Json.MAPPER.valueToTree(exchange));
            row.put("cal_date",Json.MAPPER.valueToTree("20260928"));row.put("is_open",Json.MAPPER.valueToTree(1));
            row.put("pretrade_date",Json.MAPPER.valueToTree("20260925"));
            return new PageExecutor.Page(List.of(row),null,false,"trade-cal-fixture-v1");
        });
        assertEquals(List.copyOf(expected),seen);assertEquals(2,result.rows());assertEquals(2,result.pages());
        assertTrue(result.completeCoverage());assertEquals(BusinessState.VERIFYING,result.state());
        var rows=collector.read(result.fingerprint()).rows();assertEquals(expected,contract.observedCodes(rows));
        assertTrue(rows.stream().allMatch(row -> row.get("cal_date").equals("2026-09-28")
                && ((Number)row.get("is_open")).longValue()==1 && row.get("pretrade_date").equals("20260925")));
        assertTrue(SourceQuality.failures("exchange_calendar",rows).isEmpty());
    }
    @Test void swIndexDailyRoutesToSwEndpointAndNormalizesOnlyItsPctFieldName() throws Exception {
        var contract=SourceContract.load("index_daily_market");var provider=contract.providerContract("801080.SI");
        assertEquals("sw_daily",provider.endpoint());assertTrue(provider.fields().contains("pct_change"));assertFalse(provider.fields().contains("pct_chg"));
        assertFalse(provider.fields().contains("pre_close"));
        var raw=new LinkedHashMap<>(row("index_daily_market","801080.SI"));raw.remove("pre_close");raw.put("pct_change",raw.remove("pct_chg"));
        var collector=new SourceCollector(archive);var request=request("index_daily_market",Set.of("801080.SI"));
        var result=collector.collect(request,(pageContract,params) -> {
            assertEquals("sw_daily",pageContract.endpoint());assertEquals("801080.SI",params.get("ts_code"));
            return new PageExecutor.Page(List.of(raw),null,false,"sw-daily-fixture-v1");
        });
        assertTrue(result.completeCoverage());assertEquals(BusinessState.VERIFYING,result.state());
        var frozen=collector.read(result.fingerprint());assertEquals(12.25,frozen.rows().getFirst().get("pct_chg"));
        assertFalse(frozen.rows().getFirst().containsKey("pct_change"));assertNull(frozen.rows().getFirst().get("pre_close"));
    }
    @Test void indexDailyContractsUseOneCodeAndRetainMarketUpdateTime() throws Exception {
        var collector=new SourceCollector(archive);
        for(String dataset:List.of("index_daily_market","index_daily_basic")) {
            var request=request(dataset,Set.of("000300.SH"));
            var result=collector.collect(request,params -> {
                assertEquals("20260928",params.get("trade_date"));assertEquals("000300.SH",params.get("ts_code"));
                assertFalse(params.containsKey("offset"));assertFalse(params.containsKey("limit"));
                return new PageExecutor.Page(List.of(row(dataset,"000300.SH")),null,false,"index-fixture-v1");
            });
            assertTrue(result.completeCoverage());assertEquals(BusinessState.VERIFYING,result.state());
        }
    }
    @Test void indexSourcesFanOutFrozenMultiCodeScopeWithoutCreatingPerCodeRevisions() throws Exception {
        var codes=new TreeSet<>(List.of("000300.SH","000985.CSI","000016.SH"));
        var request=new SourceCollector.Request("index_daily_market",LocalDate.of(2026,9,28),codes,"core-index-v1",null);
        var seen=new ArrayList<String>();var collector=new SourceCollector(archive);
        var result=collector.collect(request,params -> {
            String code=(String)params.get("ts_code");assertNotNull(code);seen.add(code);
            return new PageExecutor.Page(List.of(row("index_daily_market",code)),null,false,"index-fixture-v1");
        });
        assertEquals(List.copyOf(codes),seen);
        assertEquals(3,result.rows());assertEquals(3,result.pages());assertTrue(result.completeCoverage());
        assertEquals(codes,collector.read(result.fingerprint()).rows().stream().map(r -> r.get("ts_code").toString()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(result.scopeIdentity(),SourceCollector.scopeIdentity(collector.read(result.fingerprint())));
        assertNotEquals(result.scopeIdentity(),RunRequest.hash("index_daily_market","core-index-v1","000300.SH"));
    }
    @Test void cyqPerfPaginatesThroughTerminalShortPageAndChecksTheFrozenUniverse() throws Exception {
        var codes=new TreeSet<String>();var first=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<5001;i++) {
            String code=String.format(Locale.ROOT,"%06d.SH",i);codes.add(code);
            if(i<5000) first.add(row("cyq_perf",code));
        }
        var collector=new SourceCollector(archive);var request=request("cyq_perf",codes);var offsets=new ArrayList<Long>();
        var result=collector.collect(request,params -> {
            assertEquals("20260928",params.get("trade_date"));assertEquals(5000,params.get("limit"));
            long offset=((Number)params.get("offset")).longValue();offsets.add(offset);
            return new PageExecutor.Page(offset==0?first:List.of(row("cyq_perf","005000.SH")),null,false,"cyq-perf-fixture-v1");
        });
        assertEquals(List.of(0L,5000L),offsets);assertTrue(result.completeCoverage());
        assertEquals(5001,result.rows());assertEquals(2,result.pages());
        assertEquals(codes,collector.read(result.fingerprint()).rows().stream().map(r -> r.get("ts_code").toString()).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void sourceIdentityIsStableAcrossOrderAndProbeTime() throws Exception {
        var collector=new SourceCollector(archive);var request=request("daily",Set.of("000001.SZ","000002.SZ"));
        var rows=List.of(row("daily","000001.SZ"),row("daily","000002.SZ"));
        var first=collector.collect(request,p -> new PageExecutor.Page(rows,null,false,null));
        var second=collector.collect(request,p -> new PageExecutor.Page(rows.reversed(),null,false,null));
        assertEquals(first.fingerprint(),second.fingerprint());
        assertEquals(2,collector.read(first.fingerprint()).rows().size());
    }
    @Test void etfAdjustmentExhaustsPagesAndRetainsPhysicalTimestamp() throws Exception {
        var codes=new TreeSet<String>();var first=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<2001;i++) {
            String code=String.format(Locale.ROOT,"%06d.SH",i);codes.add(code);
            if(i<2000) first.add(row("etf_adj",code));
        }
        var offsets=new ArrayList<Object>();
        var collector=new SourceCollector(archive);
        var result=collector.collect(request("etf_adj",codes),params -> {
            offsets.add(params.get("offset"));
            assertEquals(2000,params.get("limit"));
            return new PageExecutor.Page(offsets.size()==1?first:List.of(row("etf_adj","002000.SH")),null,false,null);
        });
        assertEquals(List.of(0L,2000L),offsets);assertEquals(2001,result.rows());
        assertEquals(BusinessState.VERIFYING,result.state());
        var value=collector.read(result.fingerprint()).rows().getFirst();
        assertEquals("2026-09-28",value.get("timestamp"));assertFalse(value.containsKey("trade_date"));
    }
    @Test void limitResponseAtUndocumentedPagingBoundaryCannotClaimCompleteness() {
        var codes=new TreeSet<String>();var rows=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<5800;i++) {
            String code=String.format(Locale.ROOT,"%06d.SZ",i);codes.add(code);rows.add(row("stk_limit",code));
        }
        var collector=new SourceCollector(archive);
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request("stk_limit",codes),
                params -> new PageExecutor.Page(rows,null,false,null)));
    }
    @Test void missingEntityIsPartialAndEmptyDoesNotBecomeVerifiedEmpty() throws Exception {
        var collector=new SourceCollector(archive);
        var partial=collector.collect(request("daily",Set.of("000001.SZ","000002.SZ")),p -> new PageExecutor.Page(List.of(row("daily","000001.SZ")),null,false,null));
        assertEquals(BusinessState.PARTIAL,partial.state());assertFalse(partial.completeCoverage());
        var empty=collector.collect(request("daily",Set.of("000001.SZ")),p -> new PageExecutor.Page(List.of(),null,false,null));
        assertEquals(BusinessState.WAITING_SOURCE,empty.state());
    }
    @Test void wrongDateDuplicateKeysAndTypeDriftCannotBeFrozenAsComplete() {
        var collector=new SourceCollector(archive);var request=request("daily",Set.of("000001.SZ"));
        var wrong=row("daily","000001.SZ");wrong.put("trade_date",Json.MAPPER.valueToTree("20260927"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(wrong),null,false,null)));
        var duplicate=row("daily","000001.SZ");
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(duplicate,duplicate),null,false,null)));
        var drift=row("daily","000001.SZ");drift.put("close",Json.MAPPER.valueToTree("12.25"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(drift),null,false,null)));
    }
    @Test void changedFrozenFileIsRejected() throws Exception {
        var collector=new SourceCollector(archive);
        var result=collector.collect(request("daily",Set.of("000001.SZ")),p -> new PageExecutor.Page(List.of(row("daily","000001.SZ")),null,false,null));
        Files.writeString(Path.of(result.artifact()),"{}");
        assertThrows(IllegalArgumentException.class,() -> collector.read(result.fingerprint()));
    }
    @Test void portfolioSeparatesPublicationPeriodAndStableTechnicalTimestamp() throws Exception {
        var contract=SourceContract.load("etf_portfolio");var collector=new SourceCollector(archive);
        var first=row("etf_portfolio","510300.SH");first.put("end_date",Json.MAPPER.valueToTree("20260630"));
        first.put("symbol",Json.MAPPER.valueToTree("600000.SH"));
        var second=new LinkedHashMap<>(first);second.put("end_date",Json.MAPPER.valueToTree("20260331"));
        var request=request("etf_portfolio",Set.of("510300.SH"));
        var result=collector.collect(request,p -> new PageExecutor.Page(List.of(first,second),null,false,null));
        assertEquals(2,result.rows());assertEquals(BusinessState.VERIFYING,result.state());
        var repeated=collector.collect(request,p -> new PageExecutor.Page(List.of(second,first),null,false,null));
        assertEquals(result.fingerprint(),repeated.fingerprint());
        var rows=collector.read(result.fingerprint()).rows();
        assertFalse(rows.getFirst().containsKey("update_time"));
        assertNotEquals(contract.key(rows.getFirst()),contract.key(rows.getLast()));
        Instant created=Instant.parse("2026-09-29T00:00:00.123456789Z");
        var physical=rows.stream().map(r -> contract.physicalRow(r,created)).toList();
        assertEquals("2026-09-29T00:00:00.123456Z",physical.getFirst().get("update_time"));
        var encoded=QuestDbTablePort.encode(contract,"jdb_test_portfolio_fixture",physical);
        assertArrayEquals(encoded,QuestDbTablePort.encode(contract,"jdb_test_portfolio_fixture",rows.stream().map(r -> contract.physicalRow(r,created)).toList()));
        assertTrue(new String(encoded,java.nio.charset.StandardCharsets.UTF_8).contains("update_time=1790640000123456t"));
        var actual=Json.MAPPER.createArrayNode();
        for(var r:physical) {
            var values=actual.addArray();
            for(var c:contract.columns()) {
                Object value=r.get(c.target());
                if(c.type().startsWith("TIMESTAMP") && value.toString().length()==10) value=value+"T00:00:00Z";
                values.add(Json.MAPPER.valueToTree(value));
            }
        }
        assertTrue(QuestDbTablePort.matches(contract,physical,actual));
        actual.set(1,actual.get(0));assertFalse(QuestDbTablePort.matches(contract,physical,actual));
        first.put("end_date",Json.MAPPER.valueToTree("20260930"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(first),null,false,null)));
        assertTrue(contract.validCode("001753.OF"));assertFalse(SourceContract.load("daily").validCode("001753.OF"));
    }
    @Test void suspensionProbeCollapsesIntradayEventsAndAllowsOnlyProvenSparseEmpty() throws Exception {
        var collector=new SourceCollector(archive);var request=request("stk_suspend",Set.of("000001.SZ","000002.SZ"));
        var first=row("stk_suspend"," 000001.SZ ");var second=row("stk_suspend","000001.SZ");
        first.put("suspend_timing",Json.MAPPER.valueToTree("09:30-10:00"));
        second.put("suspend_timing",Json.MAPPER.valueToTree("13:00-14:00"));
        var resumed=row("stk_suspend","000002.SZ");resumed.put("suspend_type",Json.MAPPER.valueToTree("R"));
        var result=collector.collect(request,p -> {
            assertEquals("S",p.get("suspend_type"));return new PageExecutor.Page(List.of(first,second,resumed),null,false,null);
        });
        assertEquals(1,result.rows());assertTrue(result.completeCoverage());
        assertEquals(1L,((Number)collector.read(result.fingerprint()).rows().getFirst().get("is_suspended")).longValue());
        var empty=collector.collect(request,p -> new PageExecutor.Page(List.of(),null,false,null));
        assertTrue(empty.completeCoverage());assertEquals(BusinessState.VERIFYING,empty.state());
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> null));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> {throw new java.io.IOException("provider failed");}));
        var contract=SourceContract.load("stk_suspend");
        assertThrows(IllegalArgumentException.class,() -> contract.prepareRows(Collections.nCopies(5000,second),request.logicalDate(),request.expectedCodes()));
        second.put("trade_date",Json.MAPPER.valueToTree("20260927"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(second),null,false,null)));
    }
    @Test void marketAggregateHasDateCoverageWithoutInventingSecurities() throws Exception {
        var collector=new SourceCollector(archive);var request=request("moneyflow_hsgt",Set.of());
        var source=row("moneyflow_hsgt",null);source.put("north_money",Json.MAPPER.nullNode());
        source.put("south_money",Json.MAPPER.valueToTree("55308.23"));
        var result=collector.collect(request,params -> {
            assertEquals(Map.of("start_date","20260928","end_date","20260928"),params);
            return new PageExecutor.Page(List.of(source),null,false,null);
        });
        assertTrue(result.completeCoverage());assertEquals(BusinessState.VERIFYING,result.state());
        var frozen=collector.read(result.fingerprint());assertTrue(frozen.expectedCodes().isEmpty());
        assertFalse(frozen.rows().getFirst().containsKey("ts_code"));assertNull(frozen.rows().getFirst().get("north_money"));
        assertEquals(55308.23,frozen.rows().getFirst().get("south_money"));
        var empty=collector.collect(request,p -> new PageExecutor.Page(List.of(),null,false,null));
        assertFalse(empty.completeCoverage());assertEquals(BusinessState.WAITING_SOURCE,empty.state());
        assertThrows(IllegalArgumentException.class,() -> request("moneyflow_hsgt",Set.of("000001.SZ")));
        assertThrows(IllegalArgumentException.class,() -> request("daily",Set.of()));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(source,source),null,false,null)));
        source.put("south_money",Json.MAPPER.valueToTree("not-published"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(source),null,false,null)));
    }
    @Test void physicalMatchingUsesDateKeyAndChecksAllAggregateValues() {
        var contract=SourceContract.load("moneyflow_hsgt");
        var normalized=contract.normalize(row("moneyflow_hsgt",null),LocalDate.of(2026,9,28),Set.of());
        var actual=Json.MAPPER.createArrayNode();var values=actual.addArray();
        for(var column:contract.columns()) {
            if(column.key()) values.add("2026-09-28T00:00:00.000000Z");else values.add(12.25);
        }
        assertTrue(QuestDbTablePort.matches(contract,List.of(normalized),actual));
        values.set(0,Json.MAPPER.valueToTree("2026-09-27T00:00:00.000000Z"));
        assertFalse(QuestDbTablePort.matches(contract,List.of(normalized),actual));
        values.set(0,Json.MAPPER.valueToTree("2026-09-28T00:00:00.000000Z"));
        values.set(6,Json.MAPPER.valueToTree(12.26));
        assertFalse(QuestDbTablePort.matches(contract,List.of(normalized),actual));
    }
    private static Map<String,JsonNode> marginFooter() {
        var footer=row("margin_detail","日期：2026-09-28.BJ");
        SourceContract.load("margin_detail").columns().stream().filter(c -> c.type().equals("DOUBLE"))
                .forEach(c -> footer.put(c.source(),Json.MAPPER.nullNode()));return footer;
    }
    @Test void onlyEmptyMatchingMarginFooterIsFilteredAndRawEvidenceIsRetained() throws Exception {
        var collector=new SourceCollector(archive);var request=request("margin_detail",Set.of("000001.SZ"));
        var footer=marginFooter();
        var result=collector.collect(request,p -> new PageExecutor.Page(List.of(row("margin_detail","000001.SZ"),footer),null,false,null));
        assertEquals(1,result.rows());assertEquals(BusinessState.VERIFYING,result.state());
        try(var files=Files.list(archive.resolve("sources/raw"))) {
            var raw=Json.MAPPER.readTree(Files.readString(files.findFirst().orElseThrow()));
            assertEquals(2,raw.path("rows").size());assertEquals("日期：2026-09-28.BJ",raw.path("rows").get(1).path("ts_code").asText());
        }
        footer.put("rzye",Json.MAPPER.valueToTree(0.0));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(footer),null,false,null)));
        var wrongDate=marginFooter();wrongDate.put("trade_date",Json.MAPPER.valueToTree("20260927"));
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request,p -> new PageExecutor.Page(List.of(wrongDate),null,false,null)));
        var empty=collector.collect(request,p -> new PageExecutor.Page(List.of(marginFooter()),null,false,null));
        assertEquals(BusinessState.WAITING_SOURCE,empty.state());
    }
    @Test void filteringMarginFooterCannotHideSourceCap() {
        var codes=new TreeSet<String>();var rows=new ArrayList<Map<String,JsonNode>>();
        for(int i=0;i<5999;i++) {
            String code=String.format(Locale.ROOT,"%06d.SZ",i);codes.add(code);rows.add(row("margin_detail",code));
        }
        rows.add(marginFooter());var collector=new SourceCollector(archive);
        assertThrows(PageExecutor.Incomplete.class,() -> collector.collect(request("margin_detail",codes),
                p -> new PageExecutor.Page(rows,null,false,null)));
    }
    @Test void moneyflowPreservesLegacyVolumeNullPolicyWithoutChangingAmountsOrNetFlow() {
        var contract=SourceContract.load("moneyflow");var source=row("moneyflow","000001.SZ");
        source.put("buy_sm_vol",Json.MAPPER.nullNode());source.put("buy_sm_amount",Json.MAPPER.nullNode());
        source.put("net_mf_vol",Json.MAPPER.valueToTree(-987L));
        var result=contract.normalize(source,LocalDate.of(2026,9,28),Set.of("000001.SZ"));
        assertEquals(0L,result.get("buy_sm_vol"));assertNull(result.get("buy_sm_amount"));
        assertEquals(-987L,result.get("net_mf_vol"));
        assertTrue(new String(QuestDbTablePort.encode(contract,"jdb_test_moneyflow_fixture",List.of(result)),
                java.nio.charset.StandardCharsets.UTF_8).contains("net_mf_vol=-987i"));
        source.put("buy_sm_vol",Json.MAPPER.valueToTree(1.25));
        assertThrows(IllegalArgumentException.class,() -> contract.normalize(source,LocalDate.of(2026,9,28),Set.of("000001.SZ")));
    }
    @Test void etfFactorRetainsProviderIndicatorsAndNullableWarmupValues() {
        var contract=SourceContract.load("etf_factor");var source=row("etf_factor","510300.SH");
        source.put("ma_bfq_250",Json.MAPPER.nullNode());source.put("macd_bfq",Json.MAPPER.valueToTree(-0.031));
        var result=contract.normalize(source,LocalDate.of(2026,9,28),Set.of("510300.SH"));
        assertEquals(89,result.size());assertNull(result.get("ma_bfq_250"));
        assertEquals(-0.031,result.get("macd_bfq"));assertFalse(result.containsKey("trade_date_doris"));
    }
    @Test void qualityPreservesNullableFieldsAndRejectsRealRuleViolations() throws Exception {
        var collector=new SourceCollector(archive);var source=row("etf_daily","510300.SH");
        source.put("open",Json.MAPPER.valueToTree(0.0));
        var result=collector.collect(request("etf_daily",Set.of("510300.SH")),p -> new PageExecutor.Page(List.of(source),null,false,null));
        assertEquals(BusinessState.VERIFYING,result.state()); // Existing ETF rule gates close, not zero open.
        var bad=row("daily_basic","000001.SZ");bad.put("turnover_rate",Json.MAPPER.valueToTree(101.0));
        var blocked=collector.collect(request("daily_basic",Set.of("000001.SZ")),p -> new PageExecutor.Page(List.of(bad),null,false,null));
        assertEquals(BusinessState.BLOCKED,blocked.state());
        assertEquals(101.0,((Number)collector.read(blocked.fingerprint()).rows().getFirst().get("turnover_rate")).doubleValue());
    }

}
