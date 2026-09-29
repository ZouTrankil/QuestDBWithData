package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition;
import com.zoutrankil.questdbwithdata.domain.SyncJobDefinition.Mode;
import java.time.LocalDate;
import java.util.*;

/** One immutable catalog generation. Replacing it cannot mutate existing frozen requests. */
public class SyncJobRegistry {
    public record Policies(Set<String> rate, Set<String> slice, Set<String> verification) {
        public Policies {
            rate = Set.copyOf(rate); slice = Set.copyOf(slice); verification = Set.copyOf(verification);
        }
    }
    private final Map<String, SyncJobDefinition> jobs;

    /** adapterModes must come from actual adapters; declarations alone do not enable execution. */
    public SyncJobRegistry(Collection<SyncJobDefinition> definitions, DatasetRegistry datasets,
                           Map<String, Set<Mode>> adapterModes, Policies policies) {
        var entries = new LinkedHashMap<String, SyncJobDefinition>();
        for (var job : definitions) {
            Objects.requireNonNull(job);
            if (entries.putIfAbsent(job.jobId(), job) != null)
                throw new IllegalArgumentException("Duplicate job ID: " + job.jobId());
            var dataset = datasets.require(job.datasetId()).definition();
            if (dataset.schemaVersion() != job.datasetVersion())
                throw new IllegalArgumentException("Dataset version differs from job binding");
            if (!adapterModes.getOrDefault(job.datasetId(), Set.of()).containsAll(job.supportedModes()))
                throw new IllegalArgumentException("Job declares modes absent from its adapter");
            if (!policies.rate().contains(job.ratePolicyRef()) || !policies.slice().contains(job.slicePolicyRef())
                    || !policies.verification().contains(job.verificationPolicyRef()))
                throw new IllegalArgumentException("Unknown job policy reference");
        }
        for (var job : entries.values()) {
            for (var dependency : job.dependencies()) {
                var target = entries.get(dependency.jobId());
                if (target == null || target.version() != dependency.version())
                    throw new IllegalArgumentException("Unknown dependency or dependency version");
            }
        }
        var visited = new HashSet<String>();
        for (String id : entries.keySet()) visit(id, entries, new HashSet<>(), visited);
        jobs = Collections.unmodifiableMap(entries);
    }
    private static void visit(String id, Map<String, SyncJobDefinition> jobs, Set<String> path, Set<String> visited) {
        if (visited.contains(id)) return;
        if (!path.add(id)) throw new IllegalArgumentException("Job dependency cycle: " + id);
        for (var dependency : jobs.get(id).dependencies()) visit(dependency.jobId(), jobs, path, visited);
        path.remove(id); visited.add(id);
    }
    public SyncJobDefinition require(String id, int version) {
        var job = jobs.get(id);
        if (job == null || job.version() != version) throw new IllegalArgumentException("Unknown job or version");
        return job;
    }
    public List<SyncJobDefinition> definitions() { return List.copyOf(jobs.values()); }
    public List<SyncJobDefinition> dailyJobs() {
        return jobs.values().stream().filter(j -> j.enabled() && j.dailyEligible()).toList();
    }
    public SyncJobDefinition.FrozenRequest prepare(String id, int version, Mode mode,
            Map<String, ?> parameters, LocalDate from, LocalDate to, LocalDate logicalDate) {
        var job = require(id, version);
        if (!job.enabled()) throw new IllegalArgumentException("Job is disabled");
        return job.freeze(mode, parameters, from, to, logicalDate);
    }
}
