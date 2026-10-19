package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.derived.table.FactorDailyMetricRow;
import com.zoutrankil.data.factor.domain.GlobalFactorObservation;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** One shared source snapshot, streamed pool evaluations, and an explicit full output matrix. */
public final class DailyMetricPipeline {
    private DailyMetricPipeline() {}

    public record Result(List<FactorDailyMetricRow> rows, DailyMetricPoolRequests.Plan plan,
            Set<DailyMetricPublication.Key> missingKeys) {
        public Result { rows=List.copyOf(rows); missingKeys=Set.copyOf(missingKeys); }
        /** Report the complete declared matrix, including results still unavailable. */
        public DailyMetricPublication.Report publicationReport(LocalDate signal) {
            return DailyMetricPublication.inspect(signal,rows,plan.expected(),true,true);
        }
    }

    public static Result compute(Path poolEvidence, LocalDate signal, LocalDate maturity,
            String labelDefinition, List<DailyFactorLabels.Price> prices,
            List<DailyFactorLabels.Suspension> suspensions,
            List<GlobalFactorObservation> observations, FactorDefinitionCatalog catalog,
            Map<String,DailyFactorEvaluation.Exposure> exposures, String controlVersion,
            String l2SourceLineageDigest) throws Exception {
        var plan=DailyMetricPoolRequests.read(poolEvidence,signal,labelDefinition,catalog);
        var rows=new ArrayList<FactorDailyMetricRow>();
        var missing=new HashSet<>(plan.expected());
        if(!plan.availableRequests().isEmpty()) {
            DailyFactorEvaluation.evaluateStreaming(signal,maturity,labelDefinition,prices,suspensions,
                    observations,plan.availableRequests(),catalog,exposures,evaluated->{
                        var identity=evaluated.raw().identity();
                        var key=new DailyMetricPublication.Key(identity.universeId(),identity.factorId());
                        if(!missing.remove(key))throw new IllegalStateException("Unexpected daily metric section");
                        rows.add(DailyFactorMetricRows.map(evaluated,catalog.require(identity.factorId()),
                                maturity,controlVersion,l2SourceLineageDigest));
                    });
        }
        // Retain only compact metric rows; sample arrays are released after each callback.
        return new Result(rows,plan,missing);
    }
}
