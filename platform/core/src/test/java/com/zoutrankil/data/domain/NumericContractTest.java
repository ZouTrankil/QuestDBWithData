package com.zoutrankil.data.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class NumericContractTest {
    @Test
    void missingScaleCannotBecomeAnImplicitOneOrPercent() {
        assertNull(contract("UNVERIFIED", null).multiplierToBaseUnit());
        assertThrows(IllegalArgumentException.class, () -> contract("百分数", null));
        assertThrows(IllegalArgumentException.class, () -> contract("UNVERIFIED", BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> contract("元", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> contract("元", BigDecimal.ONE.negate()));
    }

    @Test
    void numericMetadataPreservesColumnMappingAndCannotAttachToText() {
        var old =
                new DatasetDefinition.Column(
                        "amount",
                        "amount",
                        "amount",
                        DatasetDefinition.StorageType.DOUBLE,
                        true,
                        "原值",
                        null);
        var enriched = old.withNumeric(contract("千元", new BigDecimal("1000")));
        assertEquals(old.sourceName(), enriched.sourceName());
        assertEquals(old.logicalName(), enriched.logicalName());
        assertEquals(old.storageName(), enriched.storageName());
        assertEquals(old.storageType(), enriched.storageType());
        assertNull(old.numeric());
        var text =
                new DatasetDefinition.Column(
                        "code",
                        "code",
                        "code",
                        DatasetDefinition.StorageType.SYMBOL,
                        false,
                        "代码",
                        null);
        assertThrows(IllegalArgumentException.class, () -> text.withNumeric(enriched.numeric()));
    }

    private static NumericContract contract(String unit, BigDecimal scale) {
        return new NumericContract(unit, "元", scale, "CNY", "不适用", "不适用", "保留 null", "测试依据");
    }
}
