package com.zoutrankil.questdbwithdata.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Immutable single-dataset contract. This definition does not start a schedule or source request. */
public record SyncJobDefinition(
        String jobId, int version, String datasetId, int datasetVersion, String owner,
        Set<Mode> supportedModes, Mode defaultMode, Map<String, Parameter> parameters,
        String ratePolicyRef, String slicePolicyRef, String verificationPolicyRef,
        RetryPolicy retry, Duration timeout, Budget budget, int revisionDays,
        List<JobRef> dependencies, Frequency frequency, ZoneId zone,
        boolean enabled, boolean dailyEligible) {

    public enum Mode { INCREMENTAL, BACKFILL, SNAPSHOT, RECONCILE, INGEST, MATERIALIZE }
    public enum Frequency { MANUAL, DAILY, WEEKLY, MONTHLY, EVENT }
    public enum ParameterType { STRING, INTEGER, BOOLEAN, DATE, STRING_LIST }
    public record JobRef(String jobId, int version) {
        public JobRef { name(jobId); if (version < 1) throw new IllegalArgumentException("Positive job version required"); }
    }
    public record RetryPolicy(int maxAttempts, Duration backoff, Duration maxElapsed) {
        public RetryPolicy {
            if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalArgumentException("Retry attempt budget required");
            positive(backoff, "backoff"); positive(maxElapsed, "retry deadline");
            if (backoff.compareTo(maxElapsed) > 0) throw new IllegalArgumentException("Backoff exceeds retry deadline");
        }
    }
    public record Budget(int maxWindowDays, int maxSlices, int maxPages, int maxRows, int maxBatchBytes) {
        public Budget {
            if (maxWindowDays < 1 || maxWindowDays > 36600 || maxSlices < 1 || maxSlices > 1000000
                    || maxPages < 1 || maxPages > 100000 || maxRows < 1 || maxRows > 10000000
                    || maxBatchBytes < 1 || maxBatchBytes > 32 * 1024 * 1024) {
                throw new IllegalArgumentException("Finite job budgets required");
            }
        }
    }
    public record Parameter(ParameterType type, boolean required, int maxLength,
                            int maxItems, Set<String> choices) {
        public Parameter {
            Objects.requireNonNull(type);
            if (maxLength < 1 || maxLength > 4096 || maxItems < 1 || maxItems > 10000)
                throw new IllegalArgumentException("Bounded parameter size required");
            choices = Set.copyOf(choices);
            if (!choices.isEmpty() && type != ParameterType.STRING && type != ParameterType.STRING_LIST)
                throw new IllegalArgumentException("Choices apply only to strings");
            if (choices.stream().anyMatch(s -> s.isBlank() || s.length() > maxLength))
                throw new IllegalArgumentException("Invalid parameter choices");
        }
        Object validate(Object value) {
            Objects.requireNonNull(value, "Null parameter value");
            return switch (type) {
                case STRING -> string(value);
                case INTEGER -> {
                    if (!(value instanceof Integer)) throw new IllegalArgumentException("Integer parameter required");
                    yield value;
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) throw new IllegalArgumentException("Boolean parameter required");
                    yield value;
                }
                case DATE -> {
                    if (!(value instanceof LocalDate)) throw new IllegalArgumentException("LocalDate parameter required");
                    yield value;
                }
                case STRING_LIST -> {
                    if (!(value instanceof List<?> items) || items.isEmpty() || items.size() > maxItems)
                        throw new IllegalArgumentException("Bounded nonempty parameter list required");
                    var copy = items.stream().map(this::string).toList();
                    if (new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException("Duplicate list values");
                    yield copy;
                }
            };
        }
        private String string(Object value) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > maxLength
                    || (!choices.isEmpty() && !choices.contains(text)))
                throw new IllegalArgumentException("Invalid string parameter");
            return text;
        }
    }

    public SyncJobDefinition {
        name(jobId); name(datasetId); name(owner);
        if (version < 1 || datasetVersion < 1) throw new IllegalArgumentException("Positive definition versions required");
        supportedModes = Set.copyOf(supportedModes);
        if (!supportedModes.contains(Objects.requireNonNull(defaultMode)))
            throw new IllegalArgumentException("Default mode must be supported");
        if (supportedModes.contains(Mode.INCREMENTAL) && defaultMode != Mode.INCREMENTAL)
            throw new IllegalArgumentException("Incremental must be the default when supported");
        parameters = Map.copyOf(parameters);
        parameters.keySet().forEach(SyncJobDefinition::name);
        name(ratePolicyRef); name(slicePolicyRef); name(verificationPolicyRef);
        Objects.requireNonNull(retry); Objects.requireNonNull(budget);
        positive(timeout, "timeout");
        if (timeout.compareTo(Duration.ofDays(1)) > 0 || retry.maxElapsed().compareTo(timeout) > 0)
            throw new IllegalArgumentException("Retry deadline must fit bounded job timeout");
        if (revisionDays < 0 || revisionDays > budget.maxWindowDays())
            throw new IllegalArgumentException("Revision overlap exceeds window budget");
        dependencies = List.copyOf(dependencies);
        if (new HashSet<>(dependencies).size() != dependencies.size()
                || dependencies.stream().anyMatch(d -> d.jobId().equals(jobId)))
            throw new IllegalArgumentException("Duplicate or self dependency");
        Objects.requireNonNull(frequency); Objects.requireNonNull(zone);
    }

    /** Returned request freezes both definition and normalized parameters for later run persistence. */
    public FrozenRequest freeze(Mode requestedMode, Map<String, ?> values,
                                LocalDate from, LocalDate to, LocalDate logicalDate) {
        Mode mode = requestedMode == null ? defaultMode : requestedMode;
        if (!supportedModes.contains(mode)) throw new IllegalArgumentException("Unsupported job mode");
        Objects.requireNonNull(logicalDate, "Frozen logical date required");
        if ((from == null) != (to == null)) throw new IllegalArgumentException("Both window bounds required");
        if ((mode == Mode.INCREMENTAL || mode == Mode.BACKFILL || mode == Mode.RECONCILE
                || mode == Mode.MATERIALIZE) && from == null)
            throw new IllegalArgumentException("Bounded execution window required");
        if (from != null && (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > budget.maxWindowDays()))
            throw new IllegalArgumentException("Execution window exceeds job budget");
        if (!parameters.keySet().containsAll(values.keySet())) throw new IllegalArgumentException("Unknown job parameter");
        var normalized = new TreeMap<String, Object>();
        parameters.forEach((key, spec) -> {
            if (values.containsKey(key)) normalized.put(key, spec.validate(values.get(key)));
            else if (spec.required()) throw new IllegalArgumentException("Missing required parameter: " + key);
        });
        return new FrozenRequest(this, mode, Collections.unmodifiableMap(normalized), from, to, logicalDate);
    }

    public static final class FrozenRequest {
        private final SyncJobDefinition definition;
        private final Mode mode;
        private final Map<String, Object> parameters;
        private final LocalDate from, to, logicalDate;
        private FrozenRequest(SyncJobDefinition definition, Mode mode, Map<String, Object> parameters,
                              LocalDate from, LocalDate to, LocalDate logicalDate) {
            this.definition = definition; this.mode = mode; this.parameters = parameters;
            this.from = from; this.to = to; this.logicalDate = logicalDate;
        }
        public SyncJobDefinition definition() { return definition; }
        public Mode mode() { return mode; }
        public Map<String, Object> parameters() { return parameters; }
        public LocalDate from() { return from; }
        public LocalDate to() { return to; }
        public LocalDate logicalDate() { return logicalDate; }
    }

    public static void name(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
            throw new IllegalArgumentException("Invalid job identifier");
    }
    private static void positive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException("Positive " + label + " required");
    }
}
