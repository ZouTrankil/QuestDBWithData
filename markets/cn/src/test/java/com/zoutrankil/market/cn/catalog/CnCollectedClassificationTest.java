package com.zoutrankil.market.cn.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataObjectDescription.SourceType;

import org.junit.jupiter.api.Test;

class CnCollectedClassificationTest {
    @Test
    void locallyChainedCurrencyBasketIsNotCertifiedAsAnExternalCollectedIndex() {
        var basket =
                CnDataDescriptions.all().stream()
                        .filter(row -> row.objectName().equals("rmb_index_daily"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SourceType.DERIVED, basket.sourceType());
        assertTrue(basket.tags().contains(CatalogTag.DERIVED));
        assertFalse(basket.tags().contains(CatalogTag.COLLECTED));
        assertTrue(basket.notes().contains("本地篮子"));
    }
}
