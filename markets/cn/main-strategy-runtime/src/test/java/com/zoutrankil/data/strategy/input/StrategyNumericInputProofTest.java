package com.zoutrankil.data.strategy.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class StrategyNumericInputProofTest {
    @Test
    void serializesSourceContractsWithUnknownScaleAndKeepsActualModelValues() throws Exception {
        var mapper = JobDefinitionJson.mapper();
        var proof = mapper.readTree(mapper.writeValueAsBytes(StrategyNumericInputProof.describe()));
        var fields = proof.path("fields");
        var portfolio = new HashMap<String, JsonNode>();
        for (var field : fields)
            if (field.path("sourceDataset").asText().equals("etf_portfolio"))
                portfolio.put(field.path("logicalField").asText(), field.path("numeric"));
        assertEquals("股", portfolio.get("holding_quantity").path("baseUnit").asText());
        assertTrue(
                portfolio.get("holding_market_value_ratio").path("multiplierToBaseUnit").isNull());
        assertFalse(proof.path("valuesConverted").asBoolean());
        assertFalse(proof.path("unitContractsComplete").asBoolean());
        assertFalse(proof.path("modelAcceptanceVerified").asBoolean());
        var raw =
                List.<Map<String, Object>>of(
                        Map.of("amount", 123.0, "mkv", 456.0, "stk_mkv_ratio", 4.37));
        var standard = StrategyInputRows.standard("etf_portfolio", raw);
        assertEquals(123.0, standard.getFirst().get("holding_quantity"));
        assertEquals(456.0, standard.getFirst().get("holding_market_value"));
        assertEquals(4.37, standard.getFirst().get("holding_market_value_ratio"));
        assertEquals(raw, StrategyInputRows.legacyModel("etf_portfolio", standard));
    }
}
