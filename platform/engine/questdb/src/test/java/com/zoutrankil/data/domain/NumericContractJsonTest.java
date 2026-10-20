package com.zoutrankil.data.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class NumericContractJsonTest {
    @Test
    void legacyColumnEncodingRemainsByteIdenticalAndExplicitNumericProofStillSerializes()
            throws Exception {
        var column =
                new DatasetDefinition.Column(
                        "amount",
                        "amount",
                        "amount",
                        DatasetDefinition.StorageType.DOUBLE,
                        true,
                        "原值",
                        null);
        var numeric =
                new NumericContract(
                        "千元", "元", new BigDecimal("1000"), "CNY", "不适用", "不适用", "保留 null", "来源说明");
        for (var json :
                new ObjectMapper[] {
                    JobDefinitionJson.mapper(), JobDefinitionJson.canonicalMapper()
                }) {
            assertEquals(
                    json.writeValueAsString(column),
                    json.writeValueAsString(column.withNumeric(numeric)));
            assertFalse(json.readTree(json.writeValueAsBytes(column)).has("numeric"));
            var proof = json.readTree(json.writeValueAsBytes(numeric));
            assertEquals(1000, proof.path("multiplierToBaseUnit").asInt());
            assertEquals("CNY", proof.path("currency").asText());
        }
    }
}
