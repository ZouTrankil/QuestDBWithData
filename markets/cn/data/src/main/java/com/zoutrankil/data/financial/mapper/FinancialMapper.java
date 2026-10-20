package com.zoutrankil.data.financial.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.financial.domain.FinancialContract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 显式映射来源字段，拒绝跨范围、空键、隐式精度截断和报表类别混写。 */
public final class FinancialMapper {
    private final FinancialContract contract;

    public FinancialMapper(FinancialContract contract) {
        this.contract = contract;
    }

    /** 官方 update_flag=1 表示修订版；同优先级冲突不能靠返回顺序挑一条。 */
    public List<Map<String, JsonNode>> revisions(
            List<Map<String, JsonNode>> raw, String code, LocalDate period, LocalDate logical) {
        var selected = new LinkedHashMap<FinancialContract.Key, Map<String, JsonNode>>();
        var typed = new HashMap<FinancialContract.Key, FinancialContract.Row>();
        for (var item : raw) {
            var row = normalize(item, code, period, logical);
            priority(row);
            var previous = typed.get(row.key());
            if (previous == null) {
                typed.put(row.key(), row);
                selected.put(row.key(), item);
                continue;
            }
            int before = priority(previous), after = priority(row);
            if (before == after && !previous.equals(row))
                throw new IllegalArgumentException("同公告和报告期存在无法消解的财务修订");
            if (after > before) {
                typed.put(row.key(), row);
                selected.put(row.key(), item);
            }
        }
        return List.copyOf(selected.values());
    }

    private static int priority(FinancialContract.Row row) {
        Object value = row.values().get("update_flag");
        if (value == null) return 0;
        if (!Set.of("0", "1").contains(value.toString()))
            throw new IllegalArgumentException("未知财务修订标记");
        return Integer.parseInt(value.toString());
    }

    public FinancialContract.Row normalize(
            Map<String, JsonNode> source, String code, LocalDate period, LocalDate logicalDate) {
        var values = new TreeMap<String, Object>();
        for (var f : contract.fields()) {
            if (!f.collected()) {
                values.put(f.name(), null);
                continue;
            }
            if (!source.containsKey(f.source()))
                throw new IllegalArgumentException("来源缺字段：" + f.source());
            var v = source.get(f.source());
            Object value = null;
            if (v != null && !v.isNull())
                value =
                        switch (f.type()) {
                            case "TIMESTAMP" ->
                                    LocalDate.parse(text(v), DateTimeFormatter.BASIC_ISO_DATE);
                            case "SYMBOL", "STRING" -> text(v);
                            case "INT" -> decimal(v).intValueExact();
                            case "LONG" -> decimal(v).longValueExact();
                            case "DOUBLE" -> {
                                double d = decimal(v).doubleValue();
                                if (!Double.isFinite(d))
                                    throw new IllegalArgumentException("财务值溢出");
                                yield d == 0d ? 0d : d;
                            }
                            default -> throw new IllegalArgumentException("未登记财务类型");
                        };
            if (f.key() && value == null) throw new IllegalArgumentException("财务业务键为空");
            values.put(f.name(), value);
        }
        var row = new FinancialContract.Row(values);
        if (!row.key().code().equals(code)
                || !row.key().period().equals(period.format(DateTimeFormatter.BASIC_ISO_DATE))
                || row.key().announcement().isAfter(logicalDate))
            throw new IllegalArgumentException("财务响应超出冻结范围");
        if (Set.of("cashflow", "income").contains(contract.dataset())
                && !"1".equals(values.get("report_type")))
            throw new IllegalArgumentException("现有业务键不能容纳多个报表类别");
        return row;
    }

    private static String text(JsonNode v) {
        if (!v.isTextual()) throw new IllegalArgumentException("财务文本字段类型错误");
        return v.textValue();
    }

    private static BigDecimal decimal(JsonNode v) {
        if (!v.isNumber() && !v.isTextual()) throw new IllegalArgumentException("财务数值类型错误");
        return new BigDecimal(v.asText());
    }
}
