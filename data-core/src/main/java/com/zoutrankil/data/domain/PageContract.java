package com.zoutrankil.data.domain;

import java.util.*;

/** Capability evidence is a source-specific contract, never learned by silently dropping parameters. */
public record PageContract(String endpoint, List<String> fields, List<String> businessKey,
                           Set<String> allowedParameters, Paging paging, Completion completion,
                           String limitParameter, String positionParameter, int pageSize, int sourceRowCap,
                           int maxPages, int maxRows, String capabilityEvidence) {
    public enum Paging { NONE, OFFSET, CURSOR }
    public enum Completion { SHORT_PAGE, EXPLICIT_END }
    public PageContract {
        DatasetDefinition.identifier(endpoint);
        fields = List.copyOf(fields);
        businessKey = List.copyOf(businessKey);
        allowedParameters = Set.copyOf(allowedParameters);
        Objects.requireNonNull(paging);
        Objects.requireNonNull(completion);
        if (fields.isEmpty() || businessKey.isEmpty() || !fields.containsAll(businessKey)
                || new HashSet<>(fields).size() != fields.size() || new HashSet<>(businessKey).size() != businessKey.size()) {
            throw new IllegalArgumentException("Unique fields and complete business key required");
        }
        // Some provider schemas (e.g. SHIBOR tenors `1w`/`3m`) begin with digits.
        // They stay in the same restricted ASCII identifier alphabet; SQL sinks quote them.
        fields.forEach(field -> {
            if(field==null || !field.matches("[A-Za-z0-9_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid provider field identifier");
        });
        allowedParameters.forEach(DatasetDefinition::identifier);
        if (pageSize < 1 || sourceRowCap < pageSize || sourceRowCap > 100000 || maxPages < 1 || maxPages > 1000
                || maxRows < pageSize || maxRows > 1000000 || capabilityEvidence == null || capabilityEvidence.isBlank()) {
            throw new IllegalArgumentException("Finite page/row budgets and source capability evidence required");
        }
        if (paging == Paging.NONE && (limitParameter != null || positionParameter != null)) {
            throw new IllegalArgumentException("Nonpaged endpoint must not inject paging parameters");
        }
        if (paging != Paging.NONE && (limitParameter == null || positionParameter == null
                || limitParameter.equals(positionParameter)
                || !allowedParameters.containsAll(List.of(limitParameter, positionParameter)))) {
            throw new IllegalArgumentException("Paging parameters must be declared supported");
        }
        if (paging == Paging.CURSOR && completion != Completion.EXPLICIT_END) {
            throw new IllegalArgumentException("Cursor endpoint needs explicit terminal evidence");
        }
    }
}
