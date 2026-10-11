package com.zoutrankil.data.factor.engine;

import com.zoutrankil.data.factor.domain.FactorDefinition;
import java.util.Map;
import java.util.Set;

/** Raw-value stage. A source adapter must certify the input date and entity alignment. */
public final class FactorFormulaEngine {
    private static final Set<String> DAILY_GROUPS=Set.of("canonical_daily","daily_price_tech","global_structural_daily","fundamental_pit","global_structural_pit","event_pit");
    public static String inputColumn(FactorDefinition definition) {
        return definition.sourceGroup().equals("event_pit")?"event_pit:"+definition.sourceColumn():definition.sourceColumn();
    }

    public double[] compute(FactorDefinition definition, Map<String,double[]> columns) {
        if(!definition.enabled()) throw new IllegalArgumentException("Disabled factor: " + definition.factorId());
        double[] source=columns.get(inputColumn(definition));
        if(source==null) throw new IllegalArgumentException("Missing declared factor input: " + definition.sourceColumn());
        if(definition.sourceGroup().equals("l2_microstructure"))
            return FactorTransforms.apply(definition.transform(),source,columns.get("clean_amount"));
        if(DAILY_GROUPS.contains(definition.sourceGroup()) && definition.transform().equals("precomputed"))
            return FactorTransforms.apply("raw",source,null);
        if(definition.sourceGroup().equals("market_state") && definition.transform().equals("market_aggregate"))
            return FactorTransforms.apply("raw",source,null);
        throw new UnsupportedOperationException("Java factor producer not implemented: "
                + definition.sourceGroup() + "/" + definition.transform() + "/" + definition.factorId());
    }
}
