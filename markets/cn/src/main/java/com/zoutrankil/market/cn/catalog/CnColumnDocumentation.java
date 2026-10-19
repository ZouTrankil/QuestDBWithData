package com.zoutrankil.market.cn.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import java.util.HashMap;
import java.util.Map;

/** 加载与模型注释共用的来源字段说明；不联网，也不初始化任何数据客户端。 */
public final class CnColumnDocumentation {
    private CnColumnDocumentation() {}
    private static final class Holder {
        static final Map<String, Map<String, DataCatalog.SourceField>> ALL = load();
    }
    public static Map<String, Map<String, DataCatalog.SourceField>> all() { return Holder.ALL; }

    private static Map<String, Map<String, DataCatalog.SourceField>> load() {
        try (var stream = CnColumnDocumentation.class.getResourceAsStream("/model-docs/collected-fields.json")) {
            if (stream == null) throw new IllegalStateException("缺少 A 股来源字段说明");
            var root = new ObjectMapper().readTree(stream);
            if (root.required("schemaVersion").asInt() != 1) throw new IllegalArgumentException("不支持的字段说明版本");
            var objects = new HashMap<String, Map<String, DataCatalog.SourceField>>();
            for (var object : root.required("objects")) {
                var fields = new HashMap<String, DataCatalog.SourceField>();
                for (var field : object.required("fields")) {
                    var documentation = new DataCatalog.SourceField(text(field, "provider"), text(field, "sourceField"),
                            text(field, "sourceType"), field.required("description").asText(), text(field, "unit"),
                            text(field, "sourceFormat"), text(field, "documentationUrl"), java.time.LocalDate.parse(text(field, "reviewedAt")),
                            DataCatalog.DocumentationStatus.valueOf(field.required("status").asText()), text(field, "notes"));
                    if (fields.putIfAbsent(field.required("name").asText(), documentation) != null)
                        throw new IllegalArgumentException("重复的字段说明");
                }
                if (objects.putIfAbsent(object.required("objectName").asText(), Map.copyOf(fields)) != null)
                    throw new IllegalArgumentException("重复的对象说明");
            }
            return Map.copyOf(objects);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("A 股来源字段说明不可读", failure);
        }
    }

    private static String text(JsonNode node, String key) { return node.path(key).asText(""); }
}
