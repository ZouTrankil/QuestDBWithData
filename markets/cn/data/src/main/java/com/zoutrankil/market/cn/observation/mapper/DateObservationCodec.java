package com.zoutrankil.market.cn.observation.mapper;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 逻辑日期键显式映射到原物理列，不能把 observation_date 当成已经迁过的数据库列。 */
public final class DateObservationCodec
        implements VerifiedBatchExecutor.Codec<Map<String, Object>, String> {
    private final DatasetDefinition definition;
    private final List<String> keyFields;

    public DateObservationCodec(DatasetDefinition definition) {
        this.definition = definition;
        var byLogical =
                definition.columns().stream()
                        .collect(
                                Collectors.toMap(
                                        DatasetDefinition.Column::logicalName, column -> column));
        keyFields =
                definition.businessKey().stream()
                        .map(name -> byLogical.get(name).storageName())
                        .toList();
    }

    public String key(Map<String, Object> row) {
        try {
            return JobDefinitionJson.canonicalMapper()
                    .writeValueAsString(keyFields.stream().map(row::get).toList());
        } catch (Exception failure) {
            throw new IllegalArgumentException("观测日期键无法编码", failure);
        }
    }

    public byte[] canonicalBytes(Map<String, Object> row) {
        var fields =
                definition.columns().stream()
                        .map(DatasetDefinition.Column::storageName)
                        .collect(Collectors.toSet());
        if (!row.keySet().equals(fields)) throw new IllegalArgumentException("观测值字段与定义不一致");
        var sorted = new TreeMap<>(row);
        for (var entry : sorted.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Double number) {
                if (!Double.isFinite(number)) throw new IllegalArgumentException("观测值不能为 NaN 或无穷值");
                if (number == 0d) entry.setValue(0d);
            }
        }
        try {
            return JobDefinitionJson.canonicalMapper().writeValueAsBytes(sorted);
        } catch (Exception failure) {
            throw new IllegalArgumentException("观测值无法编码", failure);
        }
    }
}
