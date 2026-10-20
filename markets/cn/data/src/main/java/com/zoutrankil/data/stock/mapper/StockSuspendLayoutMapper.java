package com.zoutrankil.data.stock.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.market.cn.model.CnTradeDateLayout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 物理布局另行绑定；逻辑 trade_date 行编码未改，摘要相同也不能跨版认领。 */
public final class StockSuspendLayoutMapper {
    private StockSuspendLayoutMapper() {}

    public static Map<String, ?> bind(Map<String, ?> proof, CnTradeDateLayout layout) {
        Objects.requireNonNull(layout);
        Objects.requireNonNull(proof);
        if (layout == CnTradeDateLayout.LEGACY_V1) return proof;
        var result = new LinkedHashMap<String, Object>(proof);
        result.put("protocolVersion", 2);
        result.put("datasetSchemaVersion", 2);
        result.put("rowEncoding", "trade_date-v1");
        return result;
    }

    public static void require(JsonNode proof, CnTradeDateLayout layout) {
        Objects.requireNonNull(layout);
        if (proof == null || !proof.isObject()) throw new IllegalStateException("停牌布局凭证必须是对象");
        if (layout == CnTradeDateLayout.LEGACY_V1) {
            if (proof.has("protocolVersion")
                    || proof.has("datasetSchemaVersion")
                    || proof.has("rowEncoding")) throw new IllegalStateException("旧停牌凭证不能带新版布局标记");
        } else if (!exactTwo(proof.path("protocolVersion"))
                || !exactTwo(proof.path("datasetSchemaVersion"))
                || !"trade_date-v1".equals(proof.path("rowEncoding").asText())) {
            throw new IllegalStateException("停牌凭证缺少原物理布局与逻辑行编码");
        }
    }

    private static boolean exactTwo(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == 2;
    }
}
