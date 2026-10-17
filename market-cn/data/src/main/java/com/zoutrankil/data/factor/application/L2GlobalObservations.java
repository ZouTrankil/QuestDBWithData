package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.MultiPoolFactorPipeline;
import java.time.*;
import java.util.*;

/** Python canonical adapter retains all_market raw rows, never pool-relative effective values. */
public final class L2GlobalObservations {
    private L2GlobalObservations() {}
    public static List<GlobalFactorObservation> fromPools(List<MultiPoolFactorPipeline.Output> sections,
            List<L2SourceScope.Decision> decisions,List<String> factorIds,FactorDefinitionCatalog catalog,
            LocalDateTime computedAt) {
        if(factorIds.isEmpty() || new HashSet<>(factorIds).size()!=factorIds.size() || decisions.isEmpty())
            throw new IllegalArgumentException("Explicit factors and L2 source decisions required");
        var dates=new HashSet<LocalDate>();
        for(var decision:decisions) {
            if(!decision.publishable() || !dates.add(decision.receipt().date()))
                throw new IllegalArgumentException("Blocked or duplicate L2 source scope");
        }
        for(String id:factorIds) {
            var d=catalog.require(id);
            if(!d.enabled() || !d.publishObservations() || !d.assetType().equals("stock") || !d.sourceGroup().equals("l2_microstructure"))
                throw new IllegalArgumentException("Enabled publishing L2 stock factor required");
        }
        var found=new HashSet<String>();var rows=new ArrayList<GlobalFactorObservation>();
        for(var section:sections) {
            if(!section.poolId().equals("all_market"))continue;
            var definition=section.definition();String id=definition.factorId();
            if(!factorIds.contains(id) || !catalog.require(id).equals(definition) || !dates.contains(section.date())
                    || !found.add(section.date()+"/"+id))
                throw new IllegalArgumentException("Unexpected or duplicate all_market L2 section");
            var values=section.values().raw();
            if(values.length!=section.symbols().size() || values.length==0)
                throw new IllegalArgumentException("Nonempty aligned all_market L2 values required");
            for(int i=0;i<values.length;i++) {
                if(rows.size()==100_000)throw new IllegalArgumentException("L2 observations exceed 100000; split factors or dates");
                rows.add(GlobalFactorObservations.raw(section.date(),section.symbols().get(i),definition,values[i],
                        null,computedAt,"ok","",null));
            }
        }
        if(found.size()!=dates.size()*factorIds.size())throw new IllegalArgumentException("Missing all_market L2 section");
        GlobalObservationCoverage.compare(rows,List.of());
        return List.copyOf(rows);
    }
}
