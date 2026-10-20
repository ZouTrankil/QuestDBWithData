package com.zoutrankil.data.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 接口能力须由来源契约明确声明，不能靠静默删除不支持的参数推断。 */
public record PageContract(
        String endpoint,
        List<String> fields,
        List<String> businessKey,
        Set<String> allowedParameters,
        Paging paging,
        Completion completion,
        String limitParameter,
        String positionParameter,
        int pageSize,
        int sourceRowCap,
        int maxPages,
        int maxRows,
        String capabilityEvidence) {
    public enum Paging {
        NONE,
        OFFSET,
        CURSOR
    }

    public enum Completion {
        SHORT_PAGE,
        EXPLICIT_END
    }

    public PageContract {
        DatasetDefinition.identifier(endpoint);
        fields = List.copyOf(fields);
        businessKey = List.copyOf(businessKey);
        allowedParameters = Set.copyOf(allowedParameters);
        Objects.requireNonNull(paging);
        Objects.requireNonNull(completion);
        if (fields.isEmpty()
                || businessKey.isEmpty()
                || !fields.containsAll(businessKey)
                || new HashSet<>(fields).size() != fields.size()
                || new HashSet<>(businessKey).size() != businessKey.size()) {
            throw new IllegalArgumentException("Unique fields and complete business key required");
        }
        // 部分供应商字段以数字开头，例如 SHIBOR 期限 1w、3m。
        // 仍限定为受约束的 ASCII 标识符；写入 SQL 时对列名加引号。
        fields.forEach(
                field -> {
                    if (field == null || !field.matches("[A-Za-z0-9_][A-Za-z0-9_]*"))
                        throw new IllegalArgumentException("Invalid provider field identifier");
                });
        allowedParameters.forEach(DatasetDefinition::identifier);
        if (pageSize < 1
                || sourceRowCap < pageSize
                || sourceRowCap > 100000
                || maxPages < 1
                || maxPages > 1000
                || maxRows < pageSize
                || maxRows > 1000000
                || capabilityEvidence == null
                || capabilityEvidence.isBlank()) {
            throw new IllegalArgumentException(
                    "Finite page/row budgets and source capability evidence required");
        }
        if (paging == Paging.NONE && (limitParameter != null || positionParameter != null)) {
            throw new IllegalArgumentException(
                    "Nonpaged endpoint must not inject paging parameters");
        }
        if (paging != Paging.NONE
                && (limitParameter == null
                        || positionParameter == null
                        || limitParameter.equals(positionParameter)
                        || !allowedParameters.containsAll(
                                List.of(limitParameter, positionParameter)))) {
            throw new IllegalArgumentException("Paging parameters must be declared supported");
        }
        if (paging == Paging.CURSOR && completion != Completion.EXPLICIT_END) {
            throw new IllegalArgumentException("Cursor endpoint needs explicit terminal evidence");
        }
    }
}
