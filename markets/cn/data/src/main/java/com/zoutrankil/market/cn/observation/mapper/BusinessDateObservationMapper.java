package com.zoutrankil.market.cn.observation.mapper;

import com.zoutrankil.data.domain.DatasetDefinition;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 来源归档保留 LocalDate 文本；入旧表时显式编码成 UTC 午夜日期标记，不转换成事件时间。 */
public final class BusinessDateObservationMapper {
    private BusinessDateObservationMapper() {}

    public static Map<String, Object> physical(
            DatasetDefinition definition, Map<String, Object> source) {
        var dateColumn =
                definition.columns().stream()
                        .filter(
                                column ->
                                        column.storageName()
                                                .equals(definition.designatedTimestamp()))
                        .findFirst()
                        .orElseThrow();
        if (dateColumn.temporal() == null
                || dateColumn.temporal().kind() != DatasetDefinition.TemporalKind.BUSINESS_DATE)
            throw new IllegalArgumentException("日期观测映射不能转换真实事件时刻");
        var fields =
                definition.columns().stream()
                        .map(DatasetDefinition.Column::storageName)
                        .collect(Collectors.toSet());
        if (!fields.equals(source.keySet())) throw new IllegalArgumentException("观测来源字段集合与正式定义不符");
        var result = new TreeMap<>(source);
        String name = definition.designatedTimestamp();
        LocalDate date = LocalDate.parse((String) source.get(name));
        result.put(name, date.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        return Collections.unmodifiableMap(result);
    }
}
