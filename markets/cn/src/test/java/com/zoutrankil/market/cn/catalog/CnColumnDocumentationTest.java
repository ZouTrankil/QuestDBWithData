package com.zoutrankil.market.cn.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CnColumnDocumentationTest {
    @Test void documentedUnitsAndLocalRulesKeepTheirDifferentOrigins() {
        var all = CnColumnDocumentation.all();
        assertEquals("手", all.get("daily").get("vol").unit());
        assertEquals("千元", all.get("daily").get("amount").unit());
        assertEquals("亿元", all.get("sf_month").get("inc_month").unit());
        assertEquals("万亿元", all.get("sf_month").get("stk_endval").unit());
        assertEquals("元", all.get("margin_detail").get("rzye").unit());
        assertEquals("YYYYMMDD", all.get("daily").get("trade_date").sourceFormat());
        assertEquals("trade_date", all.get("etf_daily").get("timestamp").sourceField());
        assertEquals(DataCatalog.DocumentationStatus.LOCAL_RULE, all.get("cn_gdp").get("report_date").status());
        assertEquals(DataCatalog.DocumentationStatus.LOCAL_RULE, all.get("stk_st_daily").get("is_st").status());
        assertEquals(DataCatalog.DocumentationStatus.LOCAL_RULE, all.get("index_weight").get("con_name").status());
        assertEquals("", all.get("index_weight").get("con_name").sourceField());
        assertFalse(all.containsKey("cn_bond_yield_curve"));
        assertFalse(all.containsKey("l2_daily_features"));
    }
    @Test void changedOrMissingDocumentsNeverCertifyLegacySourceFields() {
        var all = CnColumnDocumentation.all();
        for (String name : List.of("dc_index", "moneyflow_hsgt", "rmb_index_daily"))
            assertTrue(all.get(name).values().stream().allMatch(field -> field.status() == DataCatalog.DocumentationStatus.UNVERIFIED), name);
        assertEquals(DataCatalog.DocumentationStatus.UNVERIFIED, all.get("etf_share").get("fund_type").status());
        assertEquals(DataCatalog.DocumentationStatus.UNVERIFIED, all.get("fina_indicator").get("yoy_equity").status());
    }
    @Test void resourceCoversEachReferencedRecordWithoutChangingItsSignature() throws Exception {
        try (var input = getClass().getResourceAsStream("/model-docs/collected-fields.json")) {
            var root = new ObjectMapper().readTree(input);
            for (var object : root.required("objects")) {
                var type = Class.forName(object.required("modelPackage").asText() + "." + object.required("modelClass").asText());
                assertTrue(type.isRecord());
                var components = Arrays.stream(type.getRecordComponents())
                        .collect(Collectors.toMap(java.lang.reflect.RecordComponent::getName, component -> component.getType().getSimpleName()));
                assertEquals(components.size(), object.required("fields").size(), type.getName());
                for (var field : object.required("fields")) {
                    assertEquals(components.get(field.required("javaField").asText()), field.required("javaType").asText());
                    var saved = CnColumnDocumentation.all().get(object.required("objectName").asText()).get(field.required("name").asText());
                    assertNotNull(saved);
                    assertFalse(saved.description().isBlank());
                    if (saved.status() == DataCatalog.DocumentationStatus.VERIFIED_SOURCE) {
                        assertTrue(saved.documentationUrl().matches("https://tushare\\.pro/document/2\\?doc_id=[0-9]+"));
                        assertFalse(saved.sourceField().isBlank());
                    }
                }
            }
        }
    }
}
