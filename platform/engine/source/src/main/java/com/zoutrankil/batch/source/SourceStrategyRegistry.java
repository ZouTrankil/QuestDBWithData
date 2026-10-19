package com.zoutrankil.batch.source;

import java.util.*;
import java.util.stream.Collectors;

/** 从运行入口选择的市场库加载规则；缺失或重复注册直接失败，不猜测来源。 */
public final class SourceStrategyRegistry {
    private SourceStrategyRegistry() {}
    private static final List<SourcePolicyProvider> PROVIDERS = ServiceLoader.load(SourcePolicyProvider.class)
            .stream().map(ServiceLoader.Provider::get).toList();
    private static final Map<String,SourcePolicyProvider> OWNERS = owners();
    private static Map<String,SourcePolicyProvider> owners() {
        var result = new LinkedHashMap<String,SourcePolicyProvider>();
        for (var provider : PROVIDERS) {
            Objects.requireNonNull(provider.market());
            for (var entry : provider.strategies().entrySet()) {
                Objects.requireNonNull(entry.getValue());
                if (result.putIfAbsent(entry.getKey(),provider)!=null)
                    throw new IllegalStateException("Duplicate source owner: "+entry.getKey());
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static Set<String> datasets() { return OWNERS.keySet(); }
    public static SourcePolicyProvider provider(String dataset) {
        var owner = OWNERS.get(dataset);
        if (owner==null) throw new IllegalArgumentException("Unregistered source: "+dataset);
        return owner;
    }
    public static SourceStrategy require(String dataset) { return provider(dataset).strategies().get(dataset); }
    public static Set<String> monthlyDatasets() { return PROVIDERS.stream().flatMap(p->p.monthlyDatasets().stream()).collect(Collectors.toUnmodifiableSet()); }
    public static Set<String> quarterlyDatasets() { return PROVIDERS.stream().flatMap(p->p.quarterlyDatasets().stream()).collect(Collectors.toUnmodifiableSet()); }
    public static Set<String> registeredJobs() {
        var jobs = new HashSet<String>();
        datasets().forEach(dataset->jobs.add("source_"+dataset));
        PROVIDERS.forEach(provider->jobs.addAll(provider.applicationJobs()));
        return Set.copyOf(jobs);
    }
}
