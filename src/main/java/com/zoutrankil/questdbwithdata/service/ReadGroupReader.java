package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.repository.QuestDbBoundedReader;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** Sequential independent reads. A failed member is never represented as an empty successful page. */
public final class ReadGroupReader {
    public record Binding<T>(DatasetDefinition definition, Class<T> rowType,
                             Function<DatasetValues,T> mapper, Supplier<String> sourceVersion) {
        public Binding {
            Objects.requireNonNull(definition); Objects.requireNonNull(rowType);
            Objects.requireNonNull(mapper); Objects.requireNonNull(sourceVersion);
            definition.requireCapability(DatasetDefinition.Capability.READ);
        }
        DatasetReadPage<T> read(QuestDbBoundedReader reader, DatasetReadQuery query) {
            return reader.read(definition, query, sourceVersion.get(), values ->
                    rowType.cast(Objects.requireNonNull(mapper.apply(values), "Null typed row")));
        }
    }
    public enum Status { READ, FAILED, CANCELLED, DEADLINE_EXCEEDED }
    public record MemberResult(String memberId, String datasetId, int definitionVersion, Class<?> rowType,
                               Status status, DatasetReadPage<?> page, String errorCode) {
        public <T> DatasetReadPage<T> typedPage(Class<T> expectedType) {
            if (status != Status.READ || page == null) throw new IllegalStateException("Member has no successful page");
            if (!expectedType.equals(rowType)) throw new IllegalArgumentException("Different member row type requested");
            return new DatasetReadPage<>(page.datasetId(), page.definitionVersion(), page.sourceVersion(),
                    page.observedAt(), page.rows().stream().map(expectedType::cast).toList(), page.nextCursor());
        }
    }
    public record Result(Instant startedAt, Instant finishedAt, List<MemberResult> members) {
        public Result { members = List.copyOf(members); }
        public boolean complete() { return members.stream().allMatch(m -> m.status() == Status.READ); }
        public boolean atomicSnapshot() { return false; }
        public MemberResult require(String memberId) {
            return members.stream().filter(m -> m.memberId().equals(memberId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown result member"));
        }
    }
    private final QuestDbBoundedReader reader;
    private final DatasetRegistry datasets;
    private final Map<String, Binding<?>> bindings;
    public ReadGroupReader(DatasetRegistry datasets, QuestDbBoundedReader reader, Collection<Binding<?>> bindings) {
        this.reader = Objects.requireNonNull(reader);
        this.datasets = Objects.requireNonNull(datasets);
        var found = new LinkedHashMap<String, Binding<?>>();
        for (var binding : bindings) {
            var definition = binding.definition();
            if (!datasets.require(definition.datasetId()).definition().equals(definition))
                throw new IllegalArgumentException("Read binding differs from registered definition");
            if (found.putIfAbsent(definition.datasetId(), binding) != null)
                throw new IllegalArgumentException("Duplicate read binding");
        }
        this.bindings = Map.copyOf(found);
    }
    public ReadGroupRequest readRequest(java.nio.file.Path path) throws Exception {
        return new ReadGroupJson(datasets, reader).read(path);
    }
    public Result read(ReadGroupRequest request, BooleanSupplier cancelled) {
        Objects.requireNonNull(request); Objects.requireNonNull(cancelled);
        Instant startedAt = Instant.now(); long start = System.nanoTime();
        var results = new ArrayList<MemberResult>();
        for (var member : request.members()) {
            var binding = bindings.get(member.datasetId());
            Status status; String error;
            try {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    status = Status.CANCELLED; error = "READ_CANCELLED";
                } else if (System.nanoTime() - start >= request.timeout().toNanos()) {
                    status = Status.DEADLINE_EXCEEDED; error = "READ_DEADLINE_EXCEEDED";
                } else {
                    if (binding == null) throw new IllegalArgumentException("No read adapter for dataset");
                    if (binding.definition().schemaVersion() != member.definitionVersion())
                        throw new IllegalArgumentException("Read definition version changed");
                    var page = binding.read(reader, member.query());
                    results.add(new MemberResult(member.memberId(), member.datasetId(), member.definitionVersion(),
                            binding.rowType(), Status.READ, page, null));
                    continue;
                }
            } catch (RuntimeException failure) {
                status = Status.FAILED;
                error = failure.getClass().getSimpleName(); // Never expose SQL, credentials, or raw provider messages.
            }
            results.add(new MemberResult(member.memberId(), member.datasetId(), member.definitionVersion(),
                    binding == null ? null : binding.rowType(), status, null, error));
        }
        return new Result(startedAt, Instant.now(), results);
    }
}
